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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.testing.Basedir;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.services.PathMatcherFactory;
import org.apache.maven.impl.DefaultPathMatcherFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.apache.maven.api.plugin.testing.MojoExtension.getBasedir;
import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Test the clean mojo.
 */
@MojoTest
class CleanMojoTest {

    private final Log log = mock(Log.class);

    /**
     * The factory to use for creating patch matcher.
     * The actual implementation is used rather than a mock because filtering is an important part
     * of this plugin and is tedious to test. Therefore, it is hard to guarantee that the tests of
     * {@code PathMatcherFactory} in Maven core are sufficient, and we want this plugin to test it
     * more.
     */
    private final PathMatcherFactory matcherFactory = new DefaultPathMatcherFactory();

    /**
     * Tests the simple removal of directories
     *
     * @throws Exception in case of an error.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/basic-clean-test")
    @InjectMojo(goal = "clean")
    void basicClean(CleanMojo mojo) throws Exception {
        mojo.execute();

        assertFalse(checkExists("buildDirectory"), "Directory exists");
        assertFalse(checkExists("buildOutputDirectory"), "Directory exists");
        assertFalse(checkExists("buildTestDirectory"), "Directory exists");
    }

    /**
     * Tests the removal of files and nested directories
     *
     * @throws Exception in case of an error.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/nested-clean-test")
    @InjectMojo(goal = "clean")
    void cleanNestedStructure(CleanMojo mojo) throws Exception {
        mojo.execute();

        assertFalse(checkExists("target"));
        assertFalse(checkExists("target/classes"));
        assertFalse(checkExists("target/test-classes"));
    }

    /**
     * Tests that no exception is thrown when all internal variables are empty and that it doesn't
     * just remove what's there
     *
     * @throws Exception in case of an error.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/empty-clean-test")
    @InjectMojo(goal = "clean")
    void cleanEmptyDirectories(CleanMojo mojo) throws Exception {
        mojo.execute();

        assertTrue(checkExists("testDirectoryStructure"));
        assertTrue(checkExists("testDirectoryStructure/file.txt"));
        assertTrue(checkExists("testDirectoryStructure/outputDirectory"));
        assertTrue(checkExists("testDirectoryStructure/outputDirectory/file.txt"));
    }

    /**
     * Tests the removal of files using fileset
     *
     * @throws Exception in case of an error.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/fileset-clean-test")
    @InjectMojo(goal = "clean")
    void filesetsClean(CleanMojo mojo) throws Exception {
        mojo.execute();

        // fileset 1
        assertTrue(checkExists("target"));
        assertTrue(checkExists("target/classes"));
        assertFalse(checkExists("target/test-classes"));
        assertTrue(checkExists("target/subdir"));
        assertFalse(checkExists("target/classes/file.txt"));
        assertTrue(checkEmpty("target/classes"));
        assertFalse(checkEmpty("target/subdir"));
        assertTrue(checkExists("target/subdir/file.txt"));

        // fileset 2
        assertTrue(checkExists("buildOutputDirectory"));
        assertFalse(checkExists("buildOutputDirectory/file.txt"));
    }

    /**
     * Tests the removal of a directory as file
     *
     * @throws Exception in case of an error.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/invalid-directory-test")
    @InjectMojo(goal = "clean")
    void cleanInvalidDirectory(CleanMojo mojo) throws Exception {
        assertThrows(MojoException.class, mojo::execute, "Should fail to delete a file treated as a directory");
    }

    /**
     * Tests the removal of a missing directory
     *
     * @throws Exception in case of an error.
     */
    @Test
    @Basedir("${basedir}/target/test-classes/unit/missing-directory-test")
    @InjectMojo(goal = "clean")
    void missingDirectory(CleanMojo mojo) throws Exception {
        mojo.execute();

        assertFalse(checkExists("does-not-exist"));
    }

    /**
     * Test the removal of a locked file on Windows systems.
     * <p>
     * Note: Unix systems doesn't lock any files.
     * </p>
     *
     * @throws Exception in case of an error.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Basedir("${basedir}/target/test-classes/unit/locked-file-test")
    @InjectMojo(goal = "clean")
    void cleanLockedFile(CleanMojo mojo) throws Exception {
        File f = new File(getBasedir(), "buildDirectory/file.txt");
        try (FileChannel channel = new RandomAccessFile(f, "rw").getChannel();
                FileLock ignored = channel.lock()) {
            assertThrows(MojoException.class, () -> mojo.execute());
        }
    }

    /**
     * Test the removal of a locked file on Windows systems.
     * <p>
     * Note: Unix systems doesn't lock any files.
     * </p>
     *
     * @throws Exception in case of an error.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Basedir("${basedir}/target/test-classes/unit/locked-file-test")
    @InjectMojo(goal = "clean")
    void cleanLockedFileWithNoError(CleanMojo mojo) throws Exception {
        setVariableValueToObject(mojo, "failOnError", Boolean.FALSE);
        assertNotNull(mojo);

        File f = new File(getBasedir(), "buildDirectory/file.txt");
        try (FileChannel channel = new RandomAccessFile(f, "rw").getChannel();
                FileLock ignored = channel.lock()) {
            mojo.execute();
        }
    }

    /**
     * Test the followLink option with windows junctions
     * @throws Exception
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void followLinksWithWindowsJunction() throws Exception {
        testSymlink((link, target) -> {
            Process process = new ProcessBuilder()
                    .directory(link.getParent().toFile())
                    .command("cmd", "/c", "mklink", "/j", link.getFileName().toString(), target.toString())
                    .start();
            process.waitFor();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            process.getInputStream().transferTo(baos);
            process.getErrorStream().transferTo(baos);
            if (!Files.exists(link)) {
                throw new IOException("Unable to create junction: " + baos);
            }
        });
    }

    /**
     * Test the followLink option with sym link
     * @throws Exception
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void followLinksWithSymLinkOnPosix() throws Exception {
        testSymlink((link, target) -> {
            try {
                Files.createSymbolicLink(link, target);
            } catch (IOException e) {
                throw new IOException("Unable to create symbolic link", e);
            }
        });
    }

    @FunctionalInterface
    interface LinkCreator {
        void createLink(Path link, Path target) throws Exception;
    }

    private void testSymlink(LinkCreator linkCreator) throws Exception {
        Cleaner cleaner = new Cleaner(null, matcherFactory, log, false, null, null, false, false, true, false);
        Path testDir = Paths.get("target/test-classes/unit/test-dir").toAbsolutePath();
        Path dirWithLnk = testDir.resolve("dir");
        Path orgDir = testDir.resolve("org-dir");
        Path jctDir = dirWithLnk.resolve("jct-dir");
        Path file = orgDir.resolve("file.txt");

        // create directories, links and file
        Files.createDirectories(dirWithLnk);
        Files.createDirectories(orgDir);
        Files.write(file, Collections.singleton("Hello world"));
        linkCreator.createLink(jctDir, orgDir);
        // delete
        cleaner.delete(dirWithLnk);
        // verify
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(jctDir));
        assertTrue(Files.exists(orgDir));
        assertFalse(Files.exists(dirWithLnk));

        // create directories, links and file
        Files.createDirectories(dirWithLnk);
        Files.createDirectories(orgDir);
        Files.write(file, Collections.singleton("Hello world"));
        linkCreator.createLink(jctDir, orgDir);
        // delete
        cleaner = new Cleaner(null, matcherFactory, log, false, null, null, true, false, true, false);
        cleaner.delete(dirWithLnk);
        // verify
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(jctDir));
        assertTrue(Files.exists(orgDir));
        assertFalse(Files.exists(dirWithLnk));
    }

    /**
     * @param dir a dir or a file
     * @return true if a file/dir exists, false otherwise
     */
    private static boolean checkExists(String dir) {
        return Files.exists(Path.of(getBasedir(), dir));
    }

    /**
     * @param dir a directory
     * @return true if a dir is empty, false otherwise
     */
    private static boolean checkEmpty(String dir) {
        Path path = Path.of(getBasedir(), dir);
        File[] files = path.toFile().listFiles();
        return files == null || files.length == 0;
    }
}
