/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.clean;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.maven.api.Event;
import org.apache.maven.api.EventType;
import org.apache.maven.api.Listener;
import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createFile;
import static java.nio.file.Files.exists;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BackgroundCleaner}'s new batch-retry, session-scoping,
 * and leftover-scan logic introduced in the fast-clean refactor.
 */
class BackgroundCleanerTest {

    /**
     * Minimal in-memory {@link SessionData} that supports {@link #computeIfAbsent}.
     */
    private static class FakeSessionData implements SessionData {
        private final Map<Key<?>, Object> store = new HashMap<>();

        @SuppressWarnings("unchecked")
        @Override
        public <T> T get(Key<T> key) {
            return (T) store.get(key);
        }

        @Override
        public <T> void set(Key<T> key, T value) {
            store.put(key, value);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T computeIfAbsent(Key<T> key, Supplier<T> supplier) {
            return (T) store.computeIfAbsent(key, k -> supplier.get());
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> boolean replace(Key<T> key, T expected, T value) {
            T current = (T) store.get(key);
            if (current == expected) {
                store.put(key, value);
                return true;
            }
            return false;
        }
    }

    /**
     * Creates a mock {@link Session} backed by a real {@link FakeSessionData} so that
     * {@code computeIfAbsent} works correctly across multiple calls to {@code getOrCreate}.
     *
     * <p>The returned captor can be used to retrieve the registered {@link Listener} and
     * fire synthetic events at it.</p>
     */
    private Session mockSession(ArgumentCaptor<Listener> listenerCaptor) {
        Session session = mock(Session.class);
        FakeSessionData data = new FakeSessionData();
        when(session.getData()).thenReturn(data);
        if (listenerCaptor != null) {
            // Capture any listener registered during BackgroundCleaner construction.
            org.mockito.Mockito.doNothing().when(session).registerListener(listenerCaptor.capture());
        }
        return session;
    }

    // -----------------------------------------------------------------------
    // getOrCreate — session-scoping
    // -----------------------------------------------------------------------

    /**
     * Two calls to {@link BackgroundCleaner#getOrCreate} with the same parameters must
     * return the same instance (session-scoped singleton).
     */
    @Test
    void getOrCreateReturnsSameInstance(@TempDir Path tempDir) throws IOException {
        Path fastDir = tempDir.resolve(".clean");
        Log log = mock(Log.class);
        ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
        Session session = mockSession(captor);

        BackgroundCleaner bc1 = BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        BackgroundCleaner bc2 = BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);

        assertSame(bc1, bc2, "getOrCreate must return the same instance for the same session");
        // Constructor registers exactly one listener
        verify(session, atLeastOnce()).registerListener(any(Listener.class));
    }

    /**
     * When a second subproject calls {@link BackgroundCleaner#getOrCreate} with a different
     * {@code fastMode}, a warn-level message must be emitted (first-wins semantics).
     */
    @Test
    void getOrCreateLogsWarnOnConfigMismatch(@TempDir Path tempDir) throws IOException {
        Path fastDir = tempDir.resolve(".clean");
        Log log = mock(Log.class);
        Session session = mockSession(null);

        BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        // Second call with different fastMode — should log a warn message.
        BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.AT_END);

        verify(log, atLeastOnce()).warn(any(CharSequence.class));
    }

    /**
     * When a second subproject calls {@link BackgroundCleaner#getOrCreate} with the same
     * parameters, no warn message about a mismatch must be emitted.
     */
    @Test
    void getOrCreateNoWarnWhenSameConfig(@TempDir Path tempDir) throws IOException {
        Path fastDir = tempDir.resolve(".clean");
        Log log = mock(Log.class);
        Session session = mockSession(null);

        BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);

        verify(log, never()).warn(any(CharSequence.class));
    }

    // -----------------------------------------------------------------------
    // deleteInBackground — basic deletion
    // -----------------------------------------------------------------------

    /**
     * Files placed in the staging area via {@link BackgroundCleaner#fastDelete} must be
     * deleted in the background before the session ends.
     *
     * <p><b>Note on assertion strategy:</b> {@code fastDelete} moves {@code target} to a staging
     * directory under {@code fastDir} <em>synchronously</em>, so {@code exists(target)} becomes
     * {@code false} immediately — before any background deletion runs. The meaningful assertion is
     * that the staging area inside {@code fastDir} is empty after {@code onEvent} has drained the
     * executor (i.e. background deletion actually completed).</p>
     */
    @Test
    void fastDeleteRemovesDirectoryInBackground(@TempDir Path tempDir) throws Exception {
        Path fastDir = tempDir.resolve(".clean");
        Path target = createDirectory(tempDir.resolve("target"));
        createFile(target.resolve("file.txt"));

        Log log = mock(Log.class);
        ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
        Session session = mockSession(captor);

        BackgroundCleaner bc = BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        assertTrue(bc.fastDelete(target, false, true));

        // After fastDelete(), target has been moved to fastDir — the staging area exists.
        assertTrue(exists(fastDir), "staging directory must exist after fastDelete");

        // Fire SESSION_ENDED: onEvent shuts down the executor and waits for completion.
        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.SESSION_ENDED);
        captor.getValue().onEvent(event);

        // After onEvent returns, the background thread has finished and run() has cleaned up.
        // fastDir itself is deleted by run() when it is empty (directoriesToDeleteIfEmpty).
        assertFalse(exists(fastDir), "staging directory must be deleted after background clean completes");
    }

    // -----------------------------------------------------------------------
    // deleteInBackground — batch retry with force
    // -----------------------------------------------------------------------

    /**
     * With {@code force=true}, {@code tryDeleteOnce} must handle a read-only <em>directory</em>
     * containing a writable file by calling {@link Cleaner#setWritable} with the correct depth
     * to walk up to the parent, so that the file is successfully deleted in the background.
     *
     * <p><b>Note on what this test covers:</b> On POSIX, {@code Files.deleteIfExists} can delete
     * a read-only <em>file</em> as long as its parent directory is writable. The force-delete branch
     * is only reached when the <em>directory</em> is read-only (causing {@code AccessDeniedException}).
     * This test makes the parent directory read-only to exercise that branch.</p>
     *
     * <p><b>Note on assertion strategy:</b> {@code fastDelete} moves the entire {@code target}
     * tree (including the read-only directory) to a staging directory under {@code fastDir}
     * <em>synchronously</em>. After that move, neither {@code target} nor its contents
     * exist at their original paths — the assertions would pass trivially. The meaningful
     * check is that the staging area itself is empty after {@code onEvent} completes, which
     * proves that {@code tryDeleteOnce(path, true, depth)} successfully handled the read-only
     * directory inside the staging tree.</p>
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void forceDeleteHandlesReadOnlyDirectory(@TempDir Path tempDir) throws Exception {
        Path fastDir = tempDir.resolve(".clean");
        Path target = createDirectory(tempDir.resolve("target"));
        Path subDir = createDirectory(target.resolve("subdir"));
        createFile(subDir.resolve("file.txt"));
        // Make the directory read-only so Files.deleteIfExists on its children
        // throws AccessDeniedException — this is the case force=true handles.
        Files.setPosixFilePermissions(subDir, PosixFilePermissions.fromString("r-xr-xr-x"));

        Log log = mock(Log.class);
        ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
        Session session = mockSession(captor);

        BackgroundCleaner bc = BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        // force=true, retryOnError=true — must make the directory writable to delete its contents.
        assertTrue(bc.fastDelete(target, true, true));

        assertTrue(exists(fastDir), "staging directory must exist after fastDelete");

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.SESSION_ENDED);
        captor.getValue().onEvent(event);

        assertFalse(exists(fastDir), "staging directory must be deleted: read-only directory must be force-deleted");
    }

    /**
     * With {@code force=true}, {@code tryDeleteOnce} must handle <em>both</em> a read-only file
     * <em>and</em> its read-only parent directory by looping through {@link Cleaner#setWritable}
     * calls — first making the file writable (which is not enough to delete it, because the parent
     * directory is still read-only), then making the parent directory writable on the next iteration.
     *
     * <p>This is the scenario that slawekjaranowski verified against {@code src/it/read-only/setup.groovy}:
     * a file at {@code r--r--r--} inside a directory at {@code dr-xr-xr-x}. The foreground
     * {@link Cleaner#tryDelete} handles this via its {@code while (madeWritable.add(setWritable(...)))}
     * loop; this test verifies the background path has the same loop.</p>
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void forceDeleteHandlesReadOnlyFileAndDirectory(@TempDir Path tempDir) throws Exception {
        Path fastDir = tempDir.resolve(".clean");
        Path target = createDirectory(tempDir.resolve("target"));
        Path subDir = createDirectory(target.resolve("subdir"));
        Path file = createFile(subDir.resolve("file.txt"));
        // Make the file read-only AND the directory read-only — the double read-only case.
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        Files.setPosixFilePermissions(subDir, PosixFilePermissions.fromString("r-xr-xr-x"));

        Log log = mock(Log.class);
        ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
        Session session = mockSession(captor);

        BackgroundCleaner bc = BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        assertTrue(bc.fastDelete(target, true, true));

        assertTrue(exists(fastDir), "staging directory must exist after fastDelete");

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.SESSION_ENDED);
        captor.getValue().onEvent(event);

        assertFalse(
                exists(fastDir),
                "staging directory must be deleted: read-only file in read-only directory must be force-deleted");
    }

    // -----------------------------------------------------------------------
    // scanForLeftovers — leftover directories are cleaned on next build
    // -----------------------------------------------------------------------

    /**
     * When the staging directory already contains subdirectories left by a killed previous build,
     * they must be queued for deletion during construction (via {@link BackgroundCleaner#scanForLeftovers}).
     */
    @Test
    void scanForLeftoversDeletesOrphanedDirectories(@TempDir Path tempDir) throws Exception {
        Path fastDir = createDirectory(tempDir.resolve(".clean"));
        // Simulate a leftover from a previous build.
        Path leftover = createDirectory(fastDir.resolve("module-1234567890"));
        createFile(leftover.resolve("stale.class"));

        Log log = mock(Log.class);
        ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
        Session session = mockSession(captor);

        // Construction triggers scanForLeftovers.
        BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.SESSION_ENDED);
        captor.getValue().onEvent(event);

        assertFalse(exists(leftover), "leftover directory from previous build must be deleted");
    }

    /**
     * When the staging directory does not exist, {@code scanForLeftovers} must not throw
     * and the constructor must complete normally.
     */
    @Test
    void scanForLeftoversIsNoOpWhenFastDirAbsent(@TempDir Path tempDir) throws IOException {
        Path fastDir = tempDir.resolve(".clean"); // does NOT exist
        Log log = mock(Log.class);
        Session session = mockSession(null);

        // Must not throw.
        BackgroundCleaner bc = BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        // Can still be used normally afterwards.
        assertNotNull(bc);
    }

    // -----------------------------------------------------------------------
    // failOnError — background path cannot structurally fail the build
    // -----------------------------------------------------------------------

    /**
     * Background deletion failures must be logged as warnings without throwing.
     *
     * <p>{@code failOnError} has no effect when {@code fast=true}: a session-end listener cannot
     * structurally fail the build — Maven catches whatever a listener throws and downgrades it to
     * a warning. This test verifies that errors are reported as warnings and that {@code onEvent}
     * returns normally.</p>
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void backgroundDeletionFailuresAreLoggedAsWarnings(@TempDir Path tempDir) throws Exception {
        Path fastDir = tempDir.resolve(".clean");
        Path target = createDirectory(tempDir.resolve("target"));
        Path subDir = createDirectory(target.resolve("subdir"));
        createFile(subDir.resolve("file.txt"));
        Files.setPosixFilePermissions(subDir, PosixFilePermissions.fromString("r-xr-xr-x"));

        Log log = mock(Log.class);
        ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
        Session session = mockSession(captor);

        BackgroundCleaner bc = BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        // force=false, retryOnError=false — deletion will fail and must be logged as a warning.
        assertTrue(bc.fastDelete(target, false, false));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.SESSION_ENDED);

        try {
            // Must not throw — errors are logged as warnings, not propagated.
            captor.getValue().onEvent(event);
            // Verify a warning was logged.
            verify(log, atLeastOnce()).warn(any(CharSequence.class), any(Throwable.class));
        } finally {
            makeWritableRecursively(tempDir);
        }
    }

    /**
     * Recursively makes all files and directories under {@code root} writable,
     * so that {@code @TempDir} cleanup can delete them even after tests that
     * intentionally set read-only permissions.
     */
    private static void makeWritableRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
