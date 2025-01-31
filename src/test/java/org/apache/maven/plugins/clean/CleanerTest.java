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

import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
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
        cleaner.delete(basedir, null, false, true, false);
        assertFalse(exists(basedir));
        assertFalse(exists(file));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteFailsWithoutRetryWhenNoPermission(@TempDir Path tempDir) throws Exception {
        when(log.isWarnEnabled()).thenReturn(TRUE);
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        createFile(basedir.resolve("file"));
        // Remove the executable flag to prevent directory listing, which will result in a DirectoryNotEmptyException.
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-rw-r--");
        setPosixFilePermissions(basedir, permissions);
        final Cleaner cleaner = new Cleaner(null, log, false, null, null);
        final IOException exception =
                assertThrows(IOException.class, () -> cleaner.delete(basedir, null, false, true, false));
        verify(log, never()).warn(any(CharSequence.class), any(Throwable.class));
        assertEquals("Failed to delete " + basedir, exception.getMessage());
        // MCLEAN-124 Fixed on the 3.x branch: wrapper IOException has cause DirectoryNotEmptyException, the latter
        // being the accurate reason of failure.
        // On the 4.x branch it behaves differently: wrapper IOException has cause which is another IOException which
        // has suppressed DirectoryNotEmptyException.
        // So on 3.x one needed to get the cause to get the accurate reason of failure. Simple.
        //        final DirectoryNotEmptyException cause =
        //                assertInstanceOf(DirectoryNotEmptyException.class, exception.getCause());
        // On 4.x, one now needs to get the suppressed exception from the cause for that. Slightly more complicated.
        final DirectoryNotEmptyException suppressed = assertInstanceOf(
                DirectoryNotEmptyException.class, exception.getCause().getSuppressed()[0]);
        assertEquals(basedir.toString(), suppressed.getMessage());
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
                assertThrows(IOException.class, () -> cleaner.delete(basedir, null, false, true, true));
        assertEquals("Failed to delete " + basedir, exception.getMessage());
        // MCLEAN-124 Similar different in 3.x versus 4.x behavior as explained above.
        final DirectoryNotEmptyException suppressed = assertInstanceOf(
                DirectoryNotEmptyException.class, exception.getCause().getSuppressed()[0]);
        assertEquals(basedir.toString(), suppressed.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteLogsWarningWithoutRetryWhenNoPermission(@TempDir Path tempDir) throws Exception {
        when(log.isWarnEnabled()).thenReturn(TRUE);
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        final Path file = createFile(basedir.resolve("file"));
        // Remove the writable flag to prevent deletion of the file, which will result in an AccessDeniedException.
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("r-xr-xr-x");
        setPosixFilePermissions(basedir, permissions);
        final Cleaner cleaner = new Cleaner(null, log, false, null, null);
        assertDoesNotThrow(() -> cleaner.delete(basedir, null, false, false, false));
        verify(log, times(2)).warn(any(CharSequence.class), any(Throwable.class));
        InOrder inOrder = inOrder(log);
        // MCLEAN-124 Similar different in 3.x versus 4.x behavior as explained above.
        ArgumentCaptor<IOException> captor1 = ArgumentCaptor.forClass(IOException.class);
        inOrder.verify(log).warn(eq("Failed to delete " + file), captor1.capture());
        final AccessDeniedException cause1 =
                assertInstanceOf(AccessDeniedException.class, captor1.getValue().getSuppressed()[0]);
        assertEquals(file.toString(), cause1.getMessage());
        ArgumentCaptor<IOException> captor2 = ArgumentCaptor.forClass(IOException.class);
        inOrder.verify(log).warn(eq("Failed to delete " + basedir), captor2.capture());
        final DirectoryNotEmptyException cause2 = assertInstanceOf(
                DirectoryNotEmptyException.class, captor2.getValue().getSuppressed()[0]);
        assertEquals(basedir.toString(), cause2.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void deleteDoesNotLogAnythingWhenNoPermissionAndWarnDisabled(@TempDir Path tempDir) throws Exception {
        when(log.isWarnEnabled()).thenReturn(FALSE);
        final Path basedir = createDirectory(tempDir.resolve("target")).toRealPath();
        createFile(basedir.resolve("file"));
        // Remove the writable flag to prevent deletion of the file, which will result in an AccessDeniedException.
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("r-xr-xr-x");
        setPosixFilePermissions(basedir, permissions);
        final Cleaner cleaner = new Cleaner(null, log, false, null, null);
        assertDoesNotThrow(() -> cleaner.delete(basedir, null, false, false, false));
        verify(log, never()).warn(any(CharSequence.class), any(Throwable.class));
    }
}
