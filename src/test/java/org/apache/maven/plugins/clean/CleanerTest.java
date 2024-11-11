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
import java.util.Set;

import org.apache.maven.plugin.logging.Log;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CleanerTest {

    private final Log log = mock();

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteSucceedsDeeply(@TempDir Path tempDir) throws Exception {
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        final Path file = createFile(basedir.resolve("file"));
        final Cleaner cleaner = new Cleaner(null, log, false, null, null);
        cleaner.delete(basedir.toFile(), null, false, true, false);
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
        final Cleaner cleaner = new Cleaner(null, log, false, null, null);
        final IOException exception =
                assertThrows(IOException.class, () -> cleaner.delete(basedir.toFile(), null, false, true, false));
        verify(log, never()).warn(any(CharSequence.class), any(Throwable.class));
        assertEquals("Failed to delete " + basedir, exception.getMessage());
        final DirectoryNotEmptyException cause =
                assertInstanceOf(DirectoryNotEmptyException.class, exception.getCause());
        assertEquals(basedir.toString(), cause.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteFailsAfterRetryWhenNoPermission(@TempDir Path tempDir) throws Exception {
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        createFile(basedir.resolve("file"));
        // Remove the executable flag to prevent directory listing, which will result in a DirectoryNotEmptyException.
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-rw-r--");
        setPosixFilePermissions(basedir, permissions);
        final Cleaner cleaner = new Cleaner(null, log, false, null, null);
        final IOException exception =
                assertThrows(IOException.class, () -> cleaner.delete(basedir.toFile(), null, false, true, true));
        assertEquals("Failed to delete " + basedir, exception.getMessage());
        final DirectoryNotEmptyException cause =
                assertInstanceOf(DirectoryNotEmptyException.class, exception.getCause());
        assertEquals(basedir.toString(), cause.getMessage());
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
        final Cleaner cleaner = new Cleaner(null, log, false, null, null);
        assertDoesNotThrow(() -> cleaner.delete(basedir.toFile(), null, false, false, false));
        verify(log, times(2)).warn(any(CharSequence.class), any(Throwable.class));
        InOrder inOrder = inOrder(log);
        ArgumentCaptor<AccessDeniedException> cause1 = ArgumentCaptor.forClass(AccessDeniedException.class);
        inOrder.verify(log).warn(eq("Failed to delete " + file), cause1.capture());
        assertEquals(file.toString(), cause1.getValue().getMessage());
        ArgumentCaptor<DirectoryNotEmptyException> cause2 = ArgumentCaptor.forClass(DirectoryNotEmptyException.class);
        inOrder.verify(log).warn(eq("Failed to delete " + basedir), cause2.capture());
        assertEquals(basedir.toString(), cause2.getValue().getMessage());
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
        final Cleaner cleaner = new Cleaner(null, log, false, null, null);
        assertDoesNotThrow(() -> cleaner.delete(basedir.toFile(), null, false, false, false));
        verify(log, never()).warn(any(CharSequence.class), any(Throwable.class));
    }
}
