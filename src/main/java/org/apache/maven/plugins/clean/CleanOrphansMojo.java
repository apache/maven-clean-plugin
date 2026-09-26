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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.PathMatcherFactory;

/**
 * Removes orphaned build output directories left behind by sub-projects that have been
 * deleted or renamed since the last build.
 *
 * <p>When a sub-project is removed from the reactor (e.g. after a {@code git pull} or a branch
 * switch), its {@code target/} directory may remain on disk even though its {@code pom.xml} is
 * gone. Because the sub-project is no longer part of the reactor, {@code mvn clean} cannot know
 * about it and will skip it. This goal detects such orphaned directories and removes them.</p>
 *
 * <p>Detection heuristic: a direct child directory of the current project's {@code basedir} is
 * considered orphaned when its <em>only non-hidden child</em> is the build output directory
 * (typically {@code target/}). A freshly checked-out or live sub-project always has at least a
 * {@code pom.xml} alongside its build directory, so a directory whose sole visible content is a
 * {@code target/} folder can safely be assumed to be a leftover.</p>
 *
 * @since 3.5.1
 */
@Mojo(name = "purge-check", defaultPhase = "initialize")
public class CleanOrphansMojo implements org.apache.maven.api.plugin.Mojo {

    /**
     * The logger where to send information about what the plugin is doing.
     */
    @Inject
    private Log logger;

    /**
     * The current project instance, used to resolve {@code basedir}.
     */
    @Inject
    private Project project;

    /**
     * The build output directory of the current project. Used only to determine the directory
     * name (e.g. {@code target}) so that the same name is recognised in sibling directories.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private Path directory;

    /**
     * The current session.
     */
    @Inject
    private Session session;

    /**
     * The service to use for creating include and exclude filters (shared with {@link CleanMojo}).
     */
    @Inject
    private PathMatcherFactory matcherFactory;

    /**
     * Whether to force the deletion of read-only files inside orphaned build directories.
     *
     * @since 3.5.1
     */
    @Parameter(property = "maven.clean.force", defaultValue = "false")
    private boolean force;

    /**
     * Indicates whether the build will continue even if there are errors while deleting orphaned
     * build directories.
     *
     * @since 3.5.1
     */
    @Parameter(property = "maven.clean.failOnError", defaultValue = "true")
    private boolean failOnError;

    /**
     * Indicates whether the plugin should undertake additional attempts (after a short delay) to
     * delete a file if the first attempt failed.
     *
     * @since 3.5.1
     */
    @Parameter(property = "maven.clean.retryOnError", defaultValue = "true")
    private boolean retryOnError;

    /**
     * Disables the plugin execution.
     *
     * @since 3.5.1
     */
    @Parameter(property = "maven.clean.purgeCheck.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Sets whether the plugin runs in verbose mode.
     *
     * @since 3.5.1
     */
    @Parameter(property = "maven.clean.verbose")
    private Boolean verbose;

    /**
     * Scans direct children of the project {@code basedir} for orphaned build output directories
     * and deletes them.
     *
     * @throws MojoException if an orphaned directory cannot be deleted and {@link #failOnError} is
     *                       {@code true}
     */
    @Override
    public void execute() {
        if (skip) {
            logger.info("Orphan build directory check is skipped.");
            return;
        }

        Path basedir = project.getBasedir();
        String buildDirName = directory.getFileName().toString();

        List<Path> orphans = findOrphanBuildDirectories(basedir, buildDirName);
        if (orphans.isEmpty()) {
            return;
        }

        Cleaner cleaner = new Cleaner(matcherFactory, logger, isVerbose(), false, force, failOnError, retryOnError);
        try {
            for (Path orphan : orphans) {
                logger.info("Removing orphaned build directory: " + orphan);
                cleaner.delete(orphan);
            }
        } catch (IOException e) {
            throw new MojoException("Failed to remove orphaned build directories: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the list of orphaned build directories found as direct children of {@code basedir}.
     *
     * <p>A direct child directory is considered orphaned when its only non-hidden child entry is a
     * directory whose name matches {@code buildDirName}.</p>
     *
     * @param  basedir      the directory to scan
     * @param  buildDirName the name of the build output directory (e.g. {@code target})
     * @return              a possibly-empty list of build directories to delete
     */
    private List<Path> findOrphanBuildDirectories(Path basedir, String buildDirName) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(basedir)) {
            return result;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(basedir, Files::isDirectory)) {
            for (Path child : children) {
                Path buildDir = orphanBuildDir(child, buildDirName);
                if (buildDir != null) {
                    result.add(buildDir);
                }
            }
        } catch (IOException e) {
            logger.warn("Could not scan " + basedir + " for orphaned build directories: " + e.getMessage());
        }
        return result;
    }

    /**
     * Returns the build directory inside {@code child} if {@code child} is an orphaned sub-project
     * directory, or {@code null} otherwise.
     *
     * <p>A single {@link DirectoryStream} is opened on {@code child}: if its only non-hidden entry
     * is a directory named {@code buildDirName} then {@code child} is considered orphaned and that
     * entry is returned; any other content (or a missing / non-directory build dir) returns
     * {@code null}.</p>
     *
     * @param  child        the candidate sub-directory to inspect
     * @param  buildDirName the name of the build output directory (e.g. {@code target})
     * @return              the orphaned build directory, or {@code null}
     */
    private Path orphanBuildDir(Path child, String buildDirName) throws IOException {
        Path sole = null;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(child, this::isVisible)) {
            for (Path entry : entries) {
                if (sole != null) {
                    // More than one visible entry — not orphaned.
                    return null;
                }
                sole = entry;
            }
        }
        if (sole != null
                && Files.isDirectory(sole)
                && sole.getFileName().toString().equals(buildDirName)) {
            return sole;
        }
        return null;
    }

    /**
     * Returns {@code true} if the path is not a hidden file (i.e. its name does not start with a
     * dot). Symbolic links are not followed — if the link itself is hidden, it is excluded.
     */
    private boolean isVisible(Path path) {
        String name = path.getFileName().toString();
        return !name.startsWith(".");
    }

    /**
     * Indicates whether verbose output is enabled.
     */
    private boolean isVerbose() {
        return (verbose != null) ? verbose : logger.isDebugEnabled();
    }
}
