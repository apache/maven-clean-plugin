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

import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.PathMatcherFactory;
import org.apache.maven.impl.DefaultPathMatcherFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createFile;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.setPosixFilePermissions;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CleanerTest {

    private final Log log = mock(Log.class);

    /**
     * The factory to use for creating patch matcher.
     * The actual implementation is used rather than a mock because filtering is an important part
     * of this plugin and is tedious to test. Therefore, it is hard to guarantee that the tests of
     * {@code PathMatcherFactory} in Maven core are sufficient, and we want this plugin to test it
     * more.
     */
    private final PathMatcherFactory matcherFactory = new DefaultPathMatcherFactory();

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteSucceedsDeeply(@TempDir Path tempDir) throws Exception {
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        final Path file = createFile(basedir.resolve("file"));
        final var cleaner = new Cleaner(matcherFactory, log, false, false, false, true, false);
        cleaner.delete(basedir);
        assertFalse(exists(basedir));
        assertFalse(exists(file));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteFailsWithoutRetryWhenNoPermission(@TempDir Path tempDir) throws Exception {
        when(log.isWarnEnabled()).thenReturn(true);
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        createFile(basedir.resolve("file"));
        // Remove the executable flag to prevent directory listing, which will result in a DirectoryNotEmptyException.
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-rw-r--");
        setPosixFilePermissions(basedir, permissions);
        final var cleaner = new Cleaner(matcherFactory, log, false, false, false, true, false);
        final var exception = assertThrows(AccessDeniedException.class, () -> cleaner.delete(basedir));
        verify(log).warn(any(CharSequence.class), any(Throwable.class));
        assertTrue(exception.getMessage().contains(basedir.toString()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteFailsAfterRetryWhenNoPermission(@TempDir Path tempDir) throws Exception {
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        createFile(basedir.resolve("file"));
        // Remove the executable flag to prevent directory listing, which will result in a DirectoryNotEmptyException.
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-rw-r--");
        setPosixFilePermissions(basedir, permissions);
        final var cleaner = new Cleaner(matcherFactory, log, false, false, false, true, true);
        final var exception = assertThrows(AccessDeniedException.class, () -> cleaner.delete(basedir));
        assertTrue(exception.getMessage().contains(basedir.toString()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteLogsWarningWithoutRetryWhenNoPermission(@TempDir Path tempDir) throws Exception {
        when(log.isWarnEnabled()).thenReturn(true);
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        final Path file = createFile(basedir.resolve("file"));
        // Remove the writable flag to prevent deletion of the file, which will result in an AccessDeniedException.
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("r-xr-xr-x");
        setPosixFilePermissions(basedir, permissions);
        final var cleaner = new Cleaner(matcherFactory, log, false, false, false, false, false);
        assertDoesNotThrow(() -> cleaner.delete(basedir));
        verify(log).warn(any(CharSequence.class), any(Throwable.class));
        InOrder inOrder = inOrder(log);
        ArgumentCaptor<AccessDeniedException> cause1 = ArgumentCaptor.forClass(AccessDeniedException.class);
        inOrder.verify(log).warn(eq("Failed to delete " + file), cause1.capture());
        assertEquals(file.toString(), cause1.getValue().getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteDoesNotLogAnythingWhenNoPermissionAndWarnDisabled(@TempDir Path tempDir) throws Exception {
        when(log.isWarnEnabled()).thenReturn(false);
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        createFile(basedir.resolve("file"));
        // Remove the writable flag to prevent deletion of the file, which will result in an AccessDeniedException.
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("r-xr-xr-x");
        setPosixFilePermissions(basedir, permissions);
        final var cleaner = new Cleaner(matcherFactory, log, false, false, false, false, false);
        assertDoesNotThrow(() -> cleaner.delete(basedir));
        verify(log, never()).warn(any(CharSequence.class), any(Throwable.class));
    }

    /**
     * Verifies the batch retry strategy: a file that fails during the tree walk but becomes
     * deletable before the batch retry completes is deleted without any error being reported.
     *
     * <p>This test simulates the Windows scenario where a file is temporarily locked by an
     * external process (virus scanner, search indexer): the directory is made non-writable during
     * the walk, then a background thread restores write permission during the
     * {@value Cleaner#BATCH_RETRY_DELAY_MS}ms batch-retry sleep, allowing the retry to succeed.
     * No {@code System.gc()} is called between attempts — that was the anti-pattern removed by
     * this fix (see issue #281).</p>
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void batchRetrySucceedsWhenFileBecomesAvailableDuringDelay(@TempDir Path tempDir) throws Exception {
        when(log.isWarnEnabled()).thenReturn(true);
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        final Path file = createFile(basedir.resolve("file"));

        // Make the directory non-writable so the first deletion attempt on the file fails.
        final Set<PosixFilePermission> noWrite = PosixFilePermissions.fromString("r-xr-xr-x");
        final Set<PosixFilePermission> writable = PosixFilePermissions.fromString("rwxrwxr-x");
        setPosixFilePermissions(basedir, noWrite);

        // Schedule a background thread to restore write permission during the batch-retry sleep.
        // The batch sleep is BATCH_RETRY_DELAY_MS (250ms); we restore after 50ms to give plenty of margin.
        final CountDownLatch walkStarted = new CountDownLatch(1);
        Thread restorer = new Thread(() -> {
            try {
                walkStarted.await(5, TimeUnit.SECONDS);
                Thread.sleep(50);
                setPosixFilePermissions(basedir, writable);
            } catch (Exception ignored) {
            }
        });
        restorer.setDaemon(true);
        restorer.start();

        // Signal the restorer thread immediately before starting the delete.
        walkStarted.countDown();

        // With retryOnError=true, the cleaner should collect the failure and retry after the batch sleep.
        final var cleaner = new Cleaner(matcherFactory, log, false, false, false, false, true);
        assertDoesNotThrow(() -> cleaner.delete(basedir));

        // The retry should have succeeded: no warning logged, files gone.
        verify(log, never()).warn(any(CharSequence.class), any(Throwable.class));
        assertFalse(exists(file), "File should have been deleted by the batch retry");
        assertFalse(exists(basedir), "Base directory should have been deleted by the batch retry");

        restorer.join(2000);
    }

    /**
     * Verifies that when {@code retryOnError=true} and a file cannot be deleted even after the
     * batch retry, a warning is logged (once, after the retry — not during the walk).
     * The batch retry delay ({@value Cleaner#BATCH_RETRY_DELAY_MS}ms) is the only sleep;
     * there is no per-file sleep and no {@code System.gc()} call.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void batchRetryLogsWarningAfterRetryWhenStillFailing(@TempDir Path tempDir) throws Exception {
        when(log.isWarnEnabled()).thenReturn(true);
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        final Path file = createFile(basedir.resolve("file"));

        // Make the directory non-writable throughout — the retry will also fail.
        final Set<PosixFilePermission> noWrite = PosixFilePermissions.fromString("r-xr-xr-x");
        setPosixFilePermissions(basedir, noWrite);

        // retryOnError=true, failOnError=false: should not throw but should warn after retry.
        final var cleaner = new Cleaner(matcherFactory, log, false, false, false, false, true);
        assertDoesNotThrow(() -> cleaner.delete(basedir));

        // Warnings are logged for each path that still fails after the batch retry.
        // With retryOnError=true: both the file and the directory are queued and both fail,
        // so at least one (and typically two) warnings are expected.
        verify(log, org.mockito.Mockito.atLeastOnce()).warn(any(CharSequence.class), any(Throwable.class));
        // The original file should still exist because it could not be deleted.
        assertTrue(exists(file), "File should still exist when batch retry also fails");
    }
}
