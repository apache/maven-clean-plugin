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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.PathMatcherFactory;
import org.apache.maven.api.services.ProjectManager;

/**
 * Goal which cleans the build.
 * This attempts to clean a project's working directory of the files that were generated at build-time.
 * By default, it discovers and deletes the directories configured in {@code project.build.directory},
 * {@code project.build.outputDirectory}, {@code project.build.testOutputDirectory}, and
 * {@code project.reporting.outputDirectory}.
 *
 * <p>
 * Files outside the default may also be included in the deletion by configuring the {@code filesets} tag.
 * </p>
 *
 * @author <a href="mailto:evenisse@maven.org">Emmanuel Venisse</a>
 * @see Fileset
 * @since 2.0
 */
@Mojo(name = "clean")
public class CleanMojo implements org.apache.maven.api.plugin.Mojo {

    public static final String FAST_MODE_BACKGROUND = "background";

    public static final String FAST_MODE_AT_END = "at-end";

    public static final String FAST_MODE_DEFER = "defer";

    /**
     * The logger where to send information about what the plugin is doing.
     */
    @Inject
    private Log logger;

    /**
     * The directory where build results went.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private Path directory;

    /**
     * The directory where compiled main classes went. This is usually a sub-directory
     * of {@link #directory}, but could be configured by the user to another location.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private Path outputDirectory;

    /**
     * The directory where compiled test classes went. This is usually a sub-directory
     * of {@link #directory}, but could be configured by the user to another location.
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", readonly = true, required = true)
    private Path testOutputDirectory;

    /**
     * Sets whether the plugin runs in verbose mode. As of plugin version 2.3, the default value is derived from Maven's
     * global debug flag (compare command line switch {@code -X}).
     *
     * <p>Starting with <b>3.0.0</b> the property has been renamed from {@code clean.verbose} to
     * {@code maven.clean.verbose}.</p>
     *
     * @since 2.1
     */
    @Parameter(property = "maven.clean.verbose")
    private Boolean verbose;

    /**
     * The list of file sets to delete, in addition to the default directories. For example:
     *
     * <pre>
     * &lt;filesets&gt;
     *   &lt;fileset&gt;
     *     &lt;directory&gt;src/main/generated&lt;/directory&gt;
     *     &lt;followSymlinks&gt;false&lt;/followSymlinks&gt;
     *     &lt;useDefaultExcludes&gt;true&lt;/useDefaultExcludes&gt;
     *     &lt;includes&gt;
     *       &lt;include&gt;*.java&lt;/include&gt;
     *     &lt;/includes&gt;
     *     &lt;excludes&gt;
     *       &lt;exclude&gt;Template*&lt;/exclude&gt;
     *     &lt;/excludes&gt;
     *   &lt;/fileset&gt;
     * &lt;/filesets&gt;
     * </pre>
     *
     * @since 2.1
     */
    @Parameter
    private Fileset[] filesets;

    /**
     * Sets whether the plugin should follow symbolic links while deleting files from the default output directories of
     * the project.
     *
     * <p>Starting with <b>3.0.0</b> the property has been renamed from {@code clean.followSymLinks} to
     * {@code maven.clean.followSymLinks}.</p>
     *
     * @since 2.1
     */
    @Parameter(property = "maven.clean.followSymLinks", defaultValue = "false")
    private boolean followSymLinks;

    /**
     * Whether to force the deletion of read-only files.
     *
     * @since 3.4.2
     */
    @Parameter(property = "maven.clean.force", defaultValue = "false")
    private boolean force;

    /**
     * Disables the plugin execution.
     *
     * <p>Starting with <b>3.0.0</b> the property has been renamed from {@code clean.skip} to
     * {@code maven.clean.skip}.</p>
     *
     * @since 2.2
     */
    @Parameter(property = "maven.clean.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Indicates whether the build will continue even if there are clean errors.
     *
     * @since 2.2
     */
    @Parameter(property = "maven.clean.failOnError", defaultValue = "true")
    private boolean failOnError;

    /**
     * Indicates whether the plugin should undertake additional attempts (after a short delay) to delete a file if the
     * first attempt failed. This is meant to help deleting files that are temporarily locked by third-party tools like
     * virus scanners or search indexing.
     *
     * @since 2.4.2
     */
    @Parameter(property = "maven.clean.retryOnError", defaultValue = "true")
    private boolean retryOnError;

    /**
     * Disables the deletion of the default output directories configured for a project. If set to {@code true},
     * only the files/directories selected via the parameter {@link #filesets} will be deleted.
     *
     * <p>Starting with <b>3.0.0</b> the property has been renamed from {@code clean.excludeDefaultDirectories}
     * to {@code maven.clean.excludeDefaultDirectories}.</p>
     *
     * @since 2.3
     */
    @Parameter(property = "maven.clean.excludeDefaultDirectories", defaultValue = "false")
    private boolean excludeDefaultDirectories;

    /**
     * Enables fast clean if possible. If set to {@code true}, when the plugin is executed, a directory to
     * be deleted will be atomically moved inside the {@code maven.clean.fastDir} directory and a thread will
     * be launched to delete the needed files in the background.  When the build is completed, maven will wait
     * until all the files have been deleted.  If any problem occurs during the atomic move of the directories,
     * the plugin will default to the traditional deletion mechanism.
     *
     * @since 3.2
     */
    @Parameter(property = "maven.clean.fast", defaultValue = "false")
    private boolean fast;

    /**
     * When fast clean is specified, the {@code fastDir} property will be used as the location where directories
     * to be deleted will be moved prior to background deletion.  If not specified, the
     * {@code ${maven.multiModuleProjectDirectory}/target/.clean} directory will be used.
     * If the {@code ${build.directory}} has been modified, you'll have to adjust this property explicitly.
     * In order for fast clean to work correctly, this directory and the various directories that will be deleted
     * should usually reside on the same volume.  The exact conditions are system-dependent though, but if an atomic
     * move is not supported, the standard deletion mechanism will be used.
     *
     * @since 3.2
     * @see #fast
     */
    @Parameter(property = "maven.clean.fastDir")
    private Path fastDir;

    /**
     * Mode to use when using fast clean.  Values are: {@code background} to start deletion immediately and
     * waiting for all files to be deleted when the session ends, {@code at-end} to indicate that the actual
     * deletion should be performed synchronously when the session ends, or {@code defer} to specify that
     * the actual file deletion should be started in the background when the session ends.
     * This should only be used when maven is embedded in a long-running process.
     *
     * @since 3.2
     * @see #fast
     */
    @Parameter(property = "maven.clean.fastMode", defaultValue = FAST_MODE_BACKGROUND)
    private String fastMode;

    /**
     * The current session. May be null during some tests.
     */
    @Inject
    private Session session;

    /**
     * The current project instance.
     */
    @Inject
    private Project project;

    /**
     * The service to use for creating include and exclude filters.
     */
    @Inject
    private PathMatcherFactory matcherFactory;

    /**
     * Deletes build directories and file-sets.
     * Directories are deleted in the following order:
     *
     * <ol>
     *   <li>Build directory ({@code ${project.build.directory}})</li>
     *   <li>Main classes directory ({@code ${project.build.outputDirectory}})</li>
     *   <li>Test classes directory ({@code ${project.build.testOutputDirectory}})</li>
     *   <li>Directories specified in the {@code <targetPath>} child of {@code <source>} elements</li>
     *   <li>Additional file-sets</li>
     * </ol>
     *
     * Redundant directories are omitted. For example, the main classes directory will be skipped
     * in the usual case where it is a sub-directory of the build directory.
     *
     * @throws MojoException if a directory cannot be deleted
     */
    @Override
    public void execute() {
        if (skip) {
            logger.info("Clean is skipped.");
            return;
        }

        String multiModuleProjectDirectory =
                session != null ? session.getSystemProperties().get("maven.multiModuleProjectDirectory") : null;

        @SuppressWarnings("LocalVariableHidesMemberVariable")
        final Path fastDir;
        if (fast && this.fastDir != null) {
            fastDir = this.fastDir;
        } else if (fast && multiModuleProjectDirectory != null) {
            fastDir = Path.of(multiModuleProjectDirectory, "target", ".clean");
        } else {
            fastDir = null;
            if (fast) {
                logger.warn("Fast clean requires maven 3.3.1 or newer, "
                        + "or an explicit directory to be specified with the 'fastDir' configuration of "
                        + "this plugin, or the 'maven.clean.fastDir' user property to be set.");
            }
        }
        if (fast
                && !FAST_MODE_BACKGROUND.equals(fastMode)
                && !FAST_MODE_AT_END.equals(fastMode)
                && !FAST_MODE_DEFER.equals(fastMode)) {
            throw new IllegalArgumentException("Illegal value '" + fastMode + "' for fastMode. Allowed values are '"
                    + FAST_MODE_BACKGROUND + "', '" + FAST_MODE_AT_END + "' and '" + FAST_MODE_DEFER + "'.");
        }
        final var cleaner = new Cleaner(
                session,
                matcherFactory,
                logger,
                isVerbose(),
                fastDir,
                fastMode,
                followSymLinks,
                force,
                failOnError,
                retryOnError);
        try {
            for (Path directoryItem : getDirectories()) {
                cleaner.delete(directoryItem);
            }
            if (filesets != null) {
                for (Fileset fileset : filesets) {
                    if (fileset.getDirectory() == null) {
                        throw new MojoException("Missing base directory for " + fileset);
                    }
                    cleaner.delete(fileset);
                }
            }
        } catch (IOException e) {
            throw new MojoException("Failed to clean project: " + e.getMessage(), e);
        }
    }

    /**
     * Indicates whether verbose output is enabled.
     *
     * @return {@code true} if verbose output is enabled, {@code false} otherwise.
     */
    private boolean isVerbose() {
        return (verbose != null) ? verbose : logger.isDebugEnabled();
    }

    /**
     * {@return the default directories to clean, or an empty list if none}
     * The list includes the directories specified in both the Maven 3 and Maven 4 ways.
     * Null items and redundant directories (children of other items) are omitted.
     */
    private List<Path> getDirectories() {
        if (excludeDefaultDirectories) {
            return List.of();
        }

        // Directories declared in the Maven 3 way.
        var directories = new ArrayList<Path>(Arrays.asList(directory, outputDirectory, testOutputDirectory));

        // Directories declared in the Maven 4 way, with <source> elements.
        if (project != null && session != null) {
            ProjectManager projectManager = session.getService(ProjectManager.class);
            if (projectManager != null) {
                final Build build = project.getBuild();
                projectManager.getSourceRoots(project).forEach((source) -> {
                    source.targetPath().ifPresent((targetPath) -> {
                        // TODO: we can replace by `Project.getOutputDirectory(scope)` if MVN-11322 is merged.
                        String base;
                        ProjectScope scope = source.scope();
                        if (scope == ProjectScope.MAIN) {
                            base = build.getOutputDirectory();
                        } else if (scope == ProjectScope.TEST) {
                            base = build.getTestOutputDirectory();
                        } else {
                            base = build.getDirectory();
                        }
                        Path dir = project.getBasedir().resolve(base);
                        directories.add(dir.resolve(targetPath));
                    });
                });
            }
        }
        /*
         * Remove children of included parents. In the common case where the first element in the list is
         * the parent of all other directories, these loops will do only one iteration over all elements.
         */
        directories.removeIf(Objects::isNull);
        for (int i = 0; i < directories.size(); i++) {
            final Path prefix = directories.get(i);
            for (int j = directories.size(); --j >= 0; ) {
                if (j != i && directories.get(j).startsWith(prefix)) {
                    directories.remove(j); // Keep only the base directories.
                }
            }
        }
        return directories;
    }
}
