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
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.maven.api.Event;
import org.apache.maven.api.EventType;
import org.apache.maven.api.Listener;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.PathMatcherFactory;

/**
 * A cleaner potentially executed by background threads.
 *
 * <h4>Limitations</h4>
 * This class can be used for deleting {@link Path} only, not {@link Fileset}, because this class cannot handle
 * the case where only a subset of the files should be deleted. It cannot handle following symbolic links neither.
 *
 * @author Benjamin Bentmann
 * @author Martin Desruisseaux
 */
final class BackgroundCleaner extends Cleaner implements Listener, Runnable {
    /**
     * The maven session.
     */
    @Nonnull
    private final Session session;

    /**
     * The directory where to temporarily move the files to delete.
     */
    @Nonnull
    private final Path fastDir;

    /**
     * Mode to use when using fast clean. Values are:
     * {@code background} to start deletion immediately and waiting for all files to be deleted when the session ends,
     * {@code at-end} to indicate that the actual deletion should be performed synchronously when the session ends, or
     * {@code defer} to specify that the actual file deletion should be started in the background when the session ends.
     */
    @Nonnull
    private final FastMode fastMode;

    /**
     * The executor to use for deleting files in background threads.
     */
    @Nonnull
    private final ExecutorService executor;

    /**
     * Files to delete at the end of the session instead of in background thread.
     * This is unused ({@code null}) for {@link FastMode#BACKGROUND}.
     */
    private final List<Path> filesToDeleteAtEnd;

    /**
     * Directories to delete last, after the executor has been shutdown, and only if they are empty.
     * This is the directory that contains the temporary directories where the files to delete have been moved.
     * We can obviously not delete that directory before all deletion tasks in the background thread finished.
     * The directory is deleted only if empty because other plugins (e.g. compiler plugin) may have wrote new
     * files after the clean.
     */
    @Nonnull
    private final Set<Path> directoriesToDeleteIfEmpty;

    /**
     * Errors that occurred during the deletion.
     */
    private IOException errors;

    /**
     * Whether at least one deletion task has been queued.
     */
    private boolean started;

    /**
     * Whether to disable the deletion of files in background threads.
     * This is used for avoiding to repeat the same warning many times
     * when the {@link #fastDir} directory does not exist.
     */
    private boolean disabled;

    /**
     * Creates a new cleaner to be executed in a background thread.
     *
     * @param session         the Maven session to be used
     * @param matcherFactory  the service to use for creating include and exclude filters.
     * @param logger          the logger to use
     * @param verbose         whether to perform verbose logging
     * @param fastDir         the explicit configured directory or to be deleted in fast mode
     * @param fastMode        the fast deletion mode
     * @param followSymlinks  whether to follow symlinks
     * @param force           whether to force the deletion of read-only files
     * @param failOnError     whether to abort with an exception in case a selected file/directory could not be deleted
     * @param retryOnError    whether to undertake additional delete attempts in case the first attempt failed
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    BackgroundCleaner(
            @Nonnull Session session,
            @Nonnull PathMatcherFactory matcherFactory,
            @Nonnull Log logger,
            boolean verbose,
            @Nonnull Path fastDir,
            @Nonnull FastMode fastMode,
            boolean followSymlinks,
            boolean force,
            boolean failOnError,
            boolean retryOnError) {
        super(matcherFactory, logger, verbose, followSymlinks, force, failOnError, retryOnError);
        this.session = session;
        this.fastDir = fastDir;
        this.fastMode = fastMode;
        filesToDeleteAtEnd = (fastMode != FastMode.BACKGROUND) ? new ArrayList<>() : null;
        directoriesToDeleteIfEmpty = new LinkedHashSet<>(); // Will need to delete in order.
        executor = Executors.newSingleThreadExecutor((task) -> new Thread(task, "mvn-background-cleaner"));
    }

    /**
     * Returns an error message to show to user if the fast delete failed.
     */
    @Override
    String fastDeleteError(IOException e) {
        disabled = true;
        var message = new StringBuilder("Unable to fast delete directory");
        if (!Files.isDirectory(fastDir)) {
            message.append(" as the path ")
                    .append(fastDir)
                    .append(" does not point to a directory or cannot be created");
        }
        return message.append(". Fallback to immediate mode.").toString();
    }

    /**
     * Deletes the specified directory and its contents in a background thread.
     *
     * @param basedir the directory to delete, must not be {@code null}
     * @return whether this method was able to register the background task
     * @throws IOException if an error occurred while preparing the task before execution in a background thread
     */
    @Override
    boolean fastDelete(Path baseDir) throws IOException {
        if (disabled) {
            return false;
        }
        final Path parent = baseDir.getParent();
        if (parent == null) {
            return false;
        }
        if (!started) {
            started = true;
            session.registerListener(this);
        }
        /*
         * The default directory is `${maven.multiModuleProjectDirectory}/target/.clean`.
         * This is fine when cleaning a multi-project, in which case this directory will
         * be shared by all sub-projects and should not interfere with any sub-project.
         * However, when cleaning a single project, that default directory may be inside
         * the `target` directory to delete. In such case, we need a 3 steps process:
         *
         *  1) The `target` directory is renamed to temporary name inside the same parent directory.
         *  2) A new `target` directory is created with a `.clean` sub-folder (after this `if` block).
         *  3) The directory at 1 is moved to 2 as if it was the target directory of a sub-project.
         *
         * Note that we have to use `toAbsolutePath()` instead of `toRealPath()`
         * because `fastDir` may not exist yet.
         */
        directoriesToDeleteIfEmpty.add(fastDir); // Should be before `baseDir`.
        if (fastDir.toAbsolutePath().startsWith(baseDir.toAbsolutePath())) {
            String prefix = baseDir.getFileName().toString() + '-';
            Path tmpDir = Files.createTempDirectory(parent, prefix);

            // After `baseDir` has been moved, it will be implicitly recreated by `createDirectories(fastDir)` below.
            // Register for another deletion, but after `fastDir` for giving a chance to `baseDir` to become empty.
            directoriesToDeleteIfEmpty.add(baseDir);
            try {
                baseDir = Files.move(baseDir, tmpDir, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                try {
                    Files.delete(tmpDir);
                } catch (IOException s) {
                    e.addSuppressed(s);
                }
                throw e;
            }
        }
        /*
         * Create a temporary directory inside `fastDir` and all parent directories if needed.
         * The prefix is the name of parent directory, which is usually the sub-project name.
         * It allows to recognize the target directory when all of them are moved to the same
         * `${maven.multiModuleProjectDirectory}/target/.clean` directory.
         */
        String prefix = parent.getFileName().toString() + '-';
        Path tmpDir = Files.createTempDirectory(Files.createDirectories(fastDir), prefix);
        /*
         * Note: a previous version used `ATOMIC_MOVE` standard option instead of `REPLACE_EXISTING` in order
         * to increse the chances to have an exception if the path leads to a directory on another mountpoint.
         * However, `ATOMIC_MOVE` causes the `REPLACE_EXISTING` option to be ignored and it is implementation
         * specific if the existing `tmpDir` is replaced or if the method fails by throwing an `IOException`.
         * For avoiding this risk, it should be okay to not use the atomic move given that the `Files.move(…)`
         * Javadoc specifies:
         *
         *   > When invoked to move a directory that is not empty then the directory is moved if it does not
         *   > require moving the entries in the directory. For example, renaming a directory on the same
         *   > `FileStore` will usually not require moving the entries in the directory. When moving a directory
         *   > requires that its entries be moved then this method fails (by throwing an `IOException`).
         *
         * If an exception occurs, the usual deletion will be performed.
         */
        try {
            final Path dir = Files.move(baseDir, tmpDir, StandardCopyOption.REPLACE_EXISTING);
            if (filesToDeleteAtEnd != null) {
                filesToDeleteAtEnd.add(dir);
            } else {
                executor.submit(() -> deleteSilently(dir));
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.delete(tmpDir);
            } catch (IOException s) {
                e.addSuppressed(s);
            }
            throw e;
        }
        return true;
    }

    /**
     * Deletes the given directory without logging messages and without throwing {@link IOException}.
     * The exceptions are stored for reporting after the end of the session.
     *
     * <h4>Thread safety</h4>
     * Contrarily to most other methods in {@code BackgroundCleaner}, this method is
     * thread-safe because it uses a copy of this cleaner for walking in the file tree.
     */
    private void deleteSilently(final Path dir) {
        try {
            Files.walkFileTree(dir, Set.of(), Integer.MAX_VALUE, new Cleaner(this));
        } catch (IOException e) {
            errorOccurred(e);
        }
    }

    /**
     * Stores the given error for later reporting. This method can be invoked from any thread.
     */
    private synchronized void errorOccurred(IOException e) {
        if (errors == null) {
            errors = e;
        } else {
            errors.addSuppressed(e);
        }
    }

    /**
     * Invoked at the end of the session for waiting the completion of background tasks.
     * There's no clean API to do that properly as this is a very unusual use case for a
     * plugin to outlive its main execution.
     */
    @Override
    public void onEvent(Event event) {
        if (event.getType() != EventType.SESSION_ENDED) {
            return;
        }
        session.unregisterListener(this);
        if (filesToDeleteAtEnd != null) {
            filesToDeleteAtEnd.forEach((dir) -> executor.submit(() -> deleteSilently(dir)));
        }
        if (fastMode == FastMode.DEFER) {
            executor.submit(this);
            executor.shutdown();
            return;
        }
        executor.shutdown();
        try {
            // Wait for a short time for logging only if it takes longer.
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                logger.info("Waiting for background file deletion.");
                if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                    logger.warn("Timeout while waiting for background file deletion."
                            + " Some directories may not have been deleted.");
                }
            }
        } catch (InterruptedException e) {
            // Someone decided that we waited long enough.
            logger.warn(e);
        }
        run();
    }

    /**
     * Invoked after most other executor tasks are finished. It should be the very last task,
     * but it may not be really last if a timeout occurred while waiting for the completion of
     * other tasks, or if the wait has been interrupted, or if using a multi-threaded executor
     * with {@link FastMode#DEFER}.
     */
    @Override
    public synchronized void run() {
        for (Path dir : directoriesToDeleteIfEmpty) {
            try {
                Files.deleteIfExists(dir);
            } catch (DirectoryNotEmptyException e) {
                // Ignore as per method contract. Maybe another plugin started to write its output.
            } catch (IOException e) {
                errorOccurred(e);
            }
        }
        if (errors != null) {
            logger.warn("Errors during background file deletion.", errors);
            errors = null;
        }
    }
}
