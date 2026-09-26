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

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
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
import org.apache.maven.api.SessionData;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.plugin.Log;

/**
 * A session-scoped service that moves directories to a staging area and deletes them in a background thread.
 * A single instance is shared across all subprojects in a reactor build via {@link SessionData},
 * ensuring only one background thread and one session listener regardless of the number of subprojects.
 *
 * <p>This class does <em>not</em> extend {@link Cleaner}. Each subproject creates its own {@link Cleaner}
 * with per-subproject configuration ({@code force}, {@code retryOnError}, etc.) and attaches this shared
 * service via {@link Cleaner#setBackgroundCleaner(BackgroundCleaner)}. The per-subproject values are
 * passed to {@link #fastDelete(Path, boolean, boolean)} and captured alongside each directory so
 * that background deletion respects the configuration of the subproject that requested the deletion,
 * even in multi-subproject builds where subprojects configure the clean plugin differently.</p>
 *
 * <h4>Session-scoped (first-wins) vs per-subproject configuration</h4>
 * <ul>
 *   <li><b>Per-subproject</b> (captured per-call): {@code force}, {@code retryOnError}.</li>
 *   <li><b>Session-scoped, first-wins</b>: {@code fastDir}, {@code fastMode} — set by the first
 *       subproject that activates fast clean; subsequent subprojects with different values produce
 *       a warning and are ignored.</li>
 *   <li><b>Session-wide side effect</b>: {@code disabled} — if any subproject encounters a fast-delete
 *       error, fast clean is disabled for all remaining subprojects in the session.</li>
 * </ul>
 *
 * <h4>Background deletion strategy</h4>
 * Instead of the per-file retry used by the foreground {@link Cleaner} (which calls {@code System.gc()}
 * on Windows, causing JVM-wide stop-the-world pauses — see MCLEAN-102), this class uses a batch retry
 * strategy: walk the entire tree attempting each deletion once, then sleep once and retry all failures
 * together. This eliminates the stop-the-world pauses that caused a 50% performance regression on
 * multi-core Windows machines.
 *
 * <h4>Leftover cleanup</h4>
 * On first creation, this class scans the staging directory for leftovers from previous (possibly
 * killed) builds and queues them for background deletion.
 *
 * <h4>Limitations</h4>
 * This class can be used for deleting {@link Path} only, not {@link Fileset}, because this class cannot handle
 * the case where only a subset of the files should be deleted. It cannot handle following symbolic links neither.
 *
 * @author Benjamin Bentmann
 * @author Martin Desruisseaux
 * @author Guillaume Nodet
 */
final class BackgroundCleaner implements Listener, Runnable {

    /**
     * Whether the host operating system is from the Windows family.
     */
    private static final boolean ON_WINDOWS = (File.separatorChar == '\\');

    /**
     * Key for storing the shared {@code BackgroundCleaner} instance in {@link SessionData}.
     * Using {@link SessionData#computeIfAbsent} ensures that only one instance, one background
     * thread, and one session listener are created per Maven session, regardless of the number
     * of subprojects in the reactor.
     */
    private static final SessionData.Key<BackgroundCleaner> KEY = SessionData.key(BackgroundCleaner.class);

    /**
     * Delay in milliseconds before retrying failed deletions in batch.
     * A single sleep after the entire tree walk replaces the per-file
     * {@code System.gc()} + sleep that caused MCLEAN-102.
     */
    private static final int BATCH_RETRY_DELAY_MS = 250;

    /**
     * A directory queued for deferred deletion (when {@link FastMode#AT_END} or {@link FastMode#DEFER}).
     * Captures the per-subproject {@code force} and {@code retryOnError} values at submission time
     * so that background deletion respects the originating subproject's configuration.
     */
    private record DeferredDeletion(Path dir, boolean force, boolean retryOnError) {}

    /**
     * The maven session.
     */
    @Nonnull
    private final Session session;

    /**
     * The logger where to send information about what the plugin is doing.
     */
    @Nonnull
    private final Log logger;

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
     * Directories to delete at the end of the session instead of in background thread.
     * This is unused ({@code null}) for {@link FastMode#BACKGROUND}.
     */
    private final List<DeferredDeletion> filesToDeleteAtEnd;

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
     * Whether to disable the deletion of files in background threads.
     * This is used for avoiding to repeat the same warning many times
     * when the {@link #fastDir} directory does not exist.
     *
     * <p>This field is written by {@link #fastDeleteError(IOException)} without holding any lock
     * (to avoid a lock-ordering risk), and read by the synchronized {@link #fastDelete} method.
     * Declaring it {@code volatile} ensures that the write is immediately visible to all threads
     * without requiring the reader to hold the same monitor as the writer.</p>
     */
    private volatile boolean disabled;

    /**
     * Creates a new background cleaner service.
     * Use {@link #getOrCreate} to obtain a session-scoped instance.
     *
     * @param session   the Maven session to be used
     * @param logger    the logger to use
     * @param fastDir   the directory where to temporarily move the files to delete
     * @param fastMode  the fast deletion mode
     */
    private BackgroundCleaner(
            @Nonnull Session session, @Nonnull Log logger, @Nonnull Path fastDir, @Nonnull FastMode fastMode) {
        this.session = session;
        this.logger = logger;
        this.fastDir = fastDir;
        this.fastMode = fastMode;
        filesToDeleteAtEnd = (fastMode != FastMode.BACKGROUND) ? new ArrayList<>() : null;
        directoriesToDeleteIfEmpty = new LinkedHashSet<>(); // Will need to delete in order.
        executor = Executors.newSingleThreadExecutor((task) -> new Thread(task, "mvn-background-cleaner"));
        // Note: registerListener() and scanForLeftovers() are called by getOrCreate() AFTER
        // computeIfAbsent() returns, to avoid re-entrant ConcurrentHashMap access and to
        // prevent `this` from escaping the constructor.
    }

    /**
     * Initializes the background cleaner by registering the session listener and scanning
     * for leftover directories. This method must be called exactly once, immediately after
     * the instance is created by {@link #getOrCreate}, but outside the
     * {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent} mapping function
     * to avoid re-entrant deadlock.
     */
    private void init() {
        session.registerListener(this);
        scanForLeftovers();
    }

    /**
     * Returns the session-scoped {@code BackgroundCleaner}, creating it on first access.
     * The instance is stored in {@link SessionData} so that all subprojects in a reactor
     * share the same background thread and session listener.
     *
     * @param session   the Maven session to be used
     * @param logger    the logger to use
     * @param fastDir   the directory where to temporarily move the files to delete
     * @param fastMode  the fast deletion mode
     * @return the shared background cleaner instance for the session
     */
    static BackgroundCleaner getOrCreate(
            @Nonnull Session session, @Nonnull Log logger, @Nonnull Path fastDir, @Nonnull FastMode fastMode) {
        boolean[] created = {false};
        BackgroundCleaner bc = session.getData().computeIfAbsent(KEY, () -> {
            created[0] = true;
            return new BackgroundCleaner(session, logger, fastDir, fastMode);
        });
        if (created[0]) {
            // Initialization is deferred to here (outside computeIfAbsent) to avoid
            // re-entrant ConcurrentHashMap access and `this` escaping the constructor.
            bc.init();
        }
        if (!bc.fastDir.equals(fastDir) || bc.fastMode != fastMode) {
            logger.warn("BackgroundCleaner already initialized with fastDir=" + bc.fastDir
                    + ", fastMode=" + bc.fastMode + "; ignoring fastDir=" + fastDir
                    + ", fastMode=" + fastMode + " from this subproject.");
        }
        return bc;
    }

    /**
     * Scans the fast directory for leftover directories from previous (possibly killed) builds
     * and queues them for background deletion. This restores the cleanup behavior that was
     * present in the singleton pattern of version 3.5.0 but was lost when switching to
     * per-subproject instances.
     *
     * <p><b>Limitation:</b> leftovers are always deleted with {@code force=false}.
     * Because the previous build's configuration is not persisted, we cannot know
     * whether it used {@code force=true}. As a consequence, read-only files that
     * survived a killed build will not be force-deleted here; they will remain until
     * the user runs a new clean with {@code force=true}.</p>
     */
    private void scanForLeftovers() {
        if (Files.isDirectory(fastDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(fastDir)) {
                for (Path child : stream) {
                    if (Files.isDirectory(child)) {
                        logger.debug("Cleaning leftover directory from previous build: " + child);
                        executor.submit(() -> deleteInBackground(child, false, true));
                    }
                }
            } catch (IOException e) {
                logger.debug("Failed to scan for leftover directories in " + fastDir + ": " + e);
            }
        }
    }

    /**
     * Returns an error message to show to user if the fast delete failed.
     */
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
     * This method is synchronized to support concurrent calls from parallel subproject builds.
     *
     * @param baseDir       the directory to delete, must not be {@code null}
     * @param force         whether to force the deletion of read-only files
     * @param retryOnError  whether to undertake a batch retry of failed deletions
     * @return whether this method was able to register the background task
     * @throws IOException if an error occurred while preparing the task before execution in a background thread
     */
    synchronized boolean fastDelete(Path baseDir, boolean force, boolean retryOnError) throws IOException {
        if (disabled) {
            return false;
        }
        final Path parent = baseDir.getParent();
        if (parent == null) {
            return false;
        }
        /*
         * The default directory is `${maven.multiModuleProjectDirectory}/target/.clean`.
         * This is fine when cleaning a multi-project, in which case this directory will
         * be shared by all subprojects and should not interfere with any subproject.
         * However, when cleaning a single project, that default directory may be inside
         * the `target` directory to delete. In such case, we need a 3 steps process:
         *
         *  1) The `target` directory is renamed to temporary name inside the same parent directory.
         *  2) A new `target` directory is created with a `.clean` sub-folder (after this `if` block).
         *  3) The directory at 1 is moved to 2 as if it was the target directory of a subproject.
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
         * The prefix is the name of parent directory, which is usually the subproject name.
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
                filesToDeleteAtEnd.add(new DeferredDeletion(dir, force, retryOnError));
            } else {
                executor.submit(() -> deleteInBackground(dir, force, retryOnError));
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
     * Deletes the given directory in a background thread using batch retry.
     * Unlike the foreground {@link Cleaner}, this method does not call {@code System.gc()}
     * or sleep per file, avoiding the stop-the-world JVM pauses that caused the performance
     * regression described in MCLEAN-102.
     *
     * <p>The deletion proceeds in two passes:</p>
     * <ol>
     *   <li><b>Walk:</b> traverse the file tree and attempt to delete each file/directory once.
     *       Failures are silently collected without retrying.</li>
     *   <li><b>Batch retry:</b> if {@code retryOnError} is enabled and there were failures,
     *       sleep once ({@value #BATCH_RETRY_DELAY_MS}ms) to let external processes release
     *       file locks, then retry all failures together.</li>
     * </ol>
     *
     * <p>Any files that still cannot be deleted after the batch retry will be cleaned up
     * by the {@linkplain #scanForLeftovers() leftover scan} on the next build.</p>
     *
     * <h4>Thread safety</h4>
     * This method is designed to run in the background executor thread. It does not share
     * any mutable state with the main thread except through {@link #errorOccurred(IOException)},
     * which is synchronized.
     *
     * @param dir          the directory to delete
     * @param force        whether to force the deletion of read-only files
     * @param retryOnError whether to undertake a batch retry of failed deletions
     */
    private void deleteInBackground(Path dir, boolean force, boolean retryOnError) {
        logger.debug("Deleting " + dir + " in background.");
        List<Path> failures = new ArrayList<>();
        try {
            Files.walkFileTree(dir, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                /**
                 * Current depth relative to the root directory, used by {@link Cleaner#setWritable}
                 * to walk up to the parent when the file itself is already writable.
                 */
                int depth;

                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (ON_WINDOWS && attrs.isOther()) {
                        // MCLEAN-93: NTFS junctions have isDirectory() and isOther() attributes set.
                        // Delete the junction itself and skip its contents to avoid deleting the
                        // contents of the junction target, which may be outside the project.
                        if (!tryDeleteOnce(d, force, depth)) {
                            failures.add(d);
                        }
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    depth++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!tryDeleteOnce(file, force, depth)) {
                        failures.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    depth--;
                    if (!tryDeleteOnce(d, force, depth)) {
                        failures.add(d);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    failures.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errorOccurred(e);
            return;
        }
        if (!failures.isEmpty() && retryOnError) {
            try {
                Thread.sleep(BATCH_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Report collected failures before returning.
                errorOccurred(buildFailureException(failures));
                return;
            }
            List<Path> remaining = new ArrayList<>();
            for (Path path : failures) {
                if (!tryDeleteOnce(path, force, 0)) {
                    remaining.add(path);
                }
            }
            if (!remaining.isEmpty()) {
                errorOccurred(buildFailureException(remaining));
            }
        } else if (!failures.isEmpty()) {
            errorOccurred(buildFailureException(failures));
        }
    }

    /**
     * Builds an {@link IOException} that reports the failing paths (capped at 10) and total count.
     *
     * @param failures the list of paths that could not be deleted
     * @return an exception describing the failures
     */
    private static IOException buildFailureException(List<Path> failures) {
        StringBuilder sb = new StringBuilder("Failed to delete ")
                .append(failures.size())
                .append(" path(s) during background clean");
        int limit = Math.min(failures.size(), 10);
        for (int i = 0; i < limit; i++) {
            sb.append("\n  ").append(failures.get(i));
        }
        if (failures.size() > limit) {
            sb.append("\n  ... and ").append(failures.size() - limit).append(" more");
        }
        return new IOException(sb.toString());
    }

    /**
     * Tries to delete a single file or directory once, without retry delays or {@code System.gc()}.
     * If {@code force} is enabled and deletion fails with {@link AccessDeniedException},
     * the file (or its parent directory) is made writable and deletion is retried immediately (once).
     *
     * @param file  the file or directory to delete
     * @param force whether to make read-only files writable before retrying
     * @param currentDepth the depth of the file relative to the staged root, used by
     *                     {@link Cleaner#setWritable} to walk up to the parent directory
     * @return {@code true} if the file was deleted or did not exist
     */
    private static boolean tryDeleteOnce(Path file, boolean force, int currentDepth) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (AccessDeniedException e) {
            if (force) {
                try {
                    Cleaner.setWritable(file, currentDepth);
                    Files.deleteIfExists(file);
                    return true;
                } catch (IOException retry) {
                    return false;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
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
            filesToDeleteAtEnd.forEach(
                    (d) -> executor.submit(() -> deleteInBackground(d.dir(), d.force(), d.retryOnError())));
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
