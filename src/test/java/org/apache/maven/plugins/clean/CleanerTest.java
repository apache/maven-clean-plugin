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
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.testing.SilentLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createFile;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.getPosixFilePermissions;
import static java.nio.file.Files.setPosixFilePermissions;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanerTest {

    private boolean warnEnabled;

    /**
     * Use a {@code LinkedHashMap} to preserve the order of the logged warnings.
     */
    private final Map<CharSequence, Throwable> warnings = new LinkedHashMap<>();

    /**
     * Ideally we should use a mocking framework such as Mockito for this, but alas, this project has no such dependency.
     */
    private final Log log = new SilentLog() {

        @Override
        public boolean isWarnEnabled() {
            return warnEnabled;
        }

        @Override
        public void warn(CharSequence content, Throwable error) {
            warnings.put(content, error);
        }
    };

    @Test
    void deleteSucceedsDeeply(@TempDir Path tempDir) throws Exception {
        final Path basedir = createDirectory(tempDir.resolve("target"));
        final Path file = createFile(basedir.resolve("file"));
        final Cleaner cleaner = new Cleaner(null, log, false, null, null);
        cleaner.delete(basedir.toFile(), null, false, true, false);
        assertFalse(exists(basedir));
        assertFalse(exists(file));
    }

    @Test
    void deleteFailsWithoutRetryWhenNoPermission(@TempDir Path tempDir) throws Exception {
        warnEnabled = true;
        final Path basedir = createDirectory(tempDir.resolve("target"));
        createFile(basedir.resolve("file"));
        final Set<PosixFilePermission> initialPermissions = getPosixFilePermissions(basedir);
        final String rwxrwxr_x = PosixFilePermissions.toString(initialPermissions);
        // Prevent directory listing, which will result in a DirectoryNotEmptyException.
        final String rw_rw_r__ = rwxrwxr_x.replace('x', '-');
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(rw_rw_r__);
        setPosixFilePermissions(basedir, permissions);
        try {
            final Cleaner cleaner = new Cleaner(null, log, false, null, null);
            final IOException exception =
                    assertThrows(IOException.class, () -> cleaner.delete(basedir.toFile(), null, false, true, false));
            assertTrue(warnings.isEmpty());
            assertEquals("Failed to delete " + basedir, exception.getMessage());
            final DirectoryNotEmptyException cause =
                    assertInstanceOf(DirectoryNotEmptyException.class, exception.getCause());
            assertEquals(basedir.toString(), cause.getMessage());
        } finally {
            // Allow the tempDir to be cleared by the @TempDir extension.
            setPosixFilePermissions(basedir, initialPermissions);
        }
    }

    @Test
    void deleteFailsAfterRetryWhenNoPermission(@TempDir Path tempDir) throws Exception {
        final Path basedir = createDirectory(tempDir.resolve("target"));
        createFile(basedir.resolve("file"));
        final Set<PosixFilePermission> initialPermissions = getPosixFilePermissions(basedir);
        final String rwxrwxr_x = PosixFilePermissions.toString(initialPermissions);
        // Prevent directory listing, which will result in a DirectoryNotEmptyException.
        final String rw_rw_r__ = rwxrwxr_x.replace('x', '-');
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(rw_rw_r__);
        setPosixFilePermissions(basedir, permissions);
        try {
            final Cleaner cleaner = new Cleaner(null, log, false, null, null);
            final IOException exception =
                    assertThrows(IOException.class, () -> cleaner.delete(basedir.toFile(), null, false, true, true));
            assertEquals("Failed to delete " + basedir, exception.getMessage());
            final DirectoryNotEmptyException cause =
                    assertInstanceOf(DirectoryNotEmptyException.class, exception.getCause());
            assertEquals(basedir.toString(), cause.getMessage());
        } finally {
            // Allow the tempDir to be cleared by the @TempDir extension.
            setPosixFilePermissions(basedir, initialPermissions);
        }
    }

    @Test
    void deleteLogsWarningWithoutRetryWhenNoPermission(@TempDir Path tempDir) throws Exception {
        warnEnabled = true;
        final Path basedir = createDirectory(tempDir.resolve("target"));
        final Path file = createFile(basedir.resolve("file"));
        final Set<PosixFilePermission> initialPermissions = getPosixFilePermissions(basedir);
        final String rwxrwxr_x = PosixFilePermissions.toString(initialPermissions);
        final String r_xr_xr_x = rwxrwxr_x.replace('w', '-');
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(r_xr_xr_x);
        setPosixFilePermissions(basedir, permissions);
        try {
            final Cleaner cleaner = new Cleaner(null, log, false, null, null);
            assertDoesNotThrow(() -> cleaner.delete(basedir.toFile(), null, false, false, false));
            assertEquals(2, warnings.size());
            final Iterator<Entry<CharSequence, Throwable>> it =
                    warnings.entrySet().iterator();
            final Entry<CharSequence, Throwable> warning1 = it.next();
            assertEquals("Failed to delete " + file, warning1.getKey());
            final AccessDeniedException cause1 = assertInstanceOf(AccessDeniedException.class, warning1.getValue());
            assertEquals(file.toString(), cause1.getMessage());
            final Entry<CharSequence, Throwable> warning2 = it.next();
            assertEquals("Failed to delete " + basedir, warning2.getKey());
            final DirectoryNotEmptyException cause2 =
                    assertInstanceOf(DirectoryNotEmptyException.class, warning2.getValue());
            assertEquals(basedir.toString(), cause2.getMessage());
        } finally {
            setPosixFilePermissions(basedir, initialPermissions);
        }
    }

    @Test
    void deleteDoesNotLogAnythingWhenNoPermissionAndWarnDisabled(@TempDir Path tempDir) throws Exception {
        warnEnabled = false;
        final Path basedir = createDirectory(tempDir.resolve("target"));
        createFile(basedir.resolve("file"));
        final Set<PosixFilePermission> initialPermissions = getPosixFilePermissions(basedir);
        final String rwxrwxr_x = PosixFilePermissions.toString(initialPermissions);
        final String r_xr_xr_x = rwxrwxr_x.replace('w', '-');
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(r_xr_xr_x);
        setPosixFilePermissions(basedir, permissions);
        try {
            final Cleaner cleaner = new Cleaner(null, log, false, null, null);
            assertDoesNotThrow(() -> cleaner.delete(basedir.toFile(), null, false, false, false));
            assertTrue(warnings.isEmpty());
        } finally {
            setPosixFilePermissions(basedir, initialPermissions);
        }
    }
}
