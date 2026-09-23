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
import java.nio.file.Files;
import java.nio.file.Path;
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
@DisabledOnOs(OS.WINDOWS)
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
     * {@code fastMode}, a debug-level warning must be emitted (first-wins semantics).
     */
    @Test
    void getOrCreateLogsDebugOnConfigMismatch(@TempDir Path tempDir) throws IOException {
        Path fastDir = tempDir.resolve(".clean");
        Log log = mock(Log.class);
        Session session = mockSession(null);

        BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        // Second call with different fastMode — should log a debug message.
        BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.AT_END);

        verify(log, atLeastOnce()).debug(any(CharSequence.class));
    }

    /**
     * When a second subproject calls {@link BackgroundCleaner#getOrCreate} with the same
     * parameters, no debug warning about a mismatch must be emitted.
     */
    @Test
    void getOrCreateNoDebugWhenSameConfig(@TempDir Path tempDir) throws IOException {
        Path fastDir = tempDir.resolve(".clean");
        Log log = mock(Log.class);
        Session session = mockSession(null);

        BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);

        verify(log, never()).debug(any(CharSequence.class));
    }

    // -----------------------------------------------------------------------
    // deleteInBackground — basic deletion
    // -----------------------------------------------------------------------

    /**
     * Files placed in the staging area via {@link BackgroundCleaner#fastDelete} must be
     * deleted in the background before the session ends.
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

        // Fire SESSION_ENDED to flush the executor.
        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.SESSION_ENDED);
        captor.getValue().onEvent(event);

        // Give the background thread a moment to finish (onEvent waits up to 1 h, but it
        // returns as soon as the executor terminates — in practice this is milliseconds).
        assertFalse(exists(target), "target directory must have been deleted");
    }

    // -----------------------------------------------------------------------
    // deleteInBackground — batch retry with force
    // -----------------------------------------------------------------------

    /**
     * With {@code force=true}, a read-only file that would fail a plain
     * {@link Files#deleteIfExists} must still be deleted: the batch-retry path
     * must call {@code tryDeleteOnce(path, force)} (which makes the file writable)
     * rather than raw {@code Files.deleteIfExists}.
     */
    @Test
    void batchRetryWithForceDeletesReadOnlyFile(@TempDir Path tempDir) throws Exception {
        Path fastDir = tempDir.resolve(".clean");
        Path target = createDirectory(tempDir.resolve("target"));
        Path readOnly = createFile(target.resolve("ro.txt"));
        // Make the file read-only so the first pass fails.
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r--r--r--"));

        Log log = mock(Log.class);
        ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
        Session session = mockSession(captor);

        BackgroundCleaner bc = BackgroundCleaner.getOrCreate(session, log, fastDir, FastMode.BACKGROUND);
        // force=true, retryOnError=true — retry must make the file writable.
        assertTrue(bc.fastDelete(target, true, true));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.SESSION_ENDED);
        captor.getValue().onEvent(event);

        assertFalse(exists(target), "target with read-only file must be deleted when force=true");
        assertFalse(exists(readOnly), "read-only file must be deleted when force=true");
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
        assertTrue(bc != null);
    }
}
