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
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.maven.api.Event;
import org.apache.maven.api.EventType;
import org.apache.maven.api.Listener;
import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.PathMatcherFactory;

/**
 * Cleans directories.
 *
 * @author Benjamin Bentmann
 * @author Martin Desruisseaux
 */
final class Cleaner implements FileVisitor<Path> {
    /**
     * Whether the host operating system is from the Windows family.
     */
    private static final boolean ON_WINDOWS = (File.separatorChar == '\\');

    private static final SessionData.Key<Path> LAST_DIRECTORY_TO_DELETE =
            SessionData.key(Path.class, Cleaner.class.getName() + ".lastDirectoryToDelete");

    /**
     * The maven session. This is typically non-null in a real run, but it can be during unit tests.
     */
    @Nonnull
    private final Session session;

    /**
     * The logger where to send information or warning messages.
     */
    @Nonnull
    private final Log logger;

    /**
     * Whether to send to the logger some information that would normally be at the "debug" level.
     * Those information include the files or directories that are deleted.
     */
    private final boolean verbose;

    /**
     * Whether to list the files or directories that are deleted. This is a combination of {@link #verbose}
     * with {@link #logger} configuration, and is stored because frequently requested.
     */
    private final boolean listDeletedFiles;

    /**
     * Whether the last call to {@code tryDelete(Path)} really deleted the file.
     * If this flag is {@code false} after {@link #tryDelete(Path)} returned {@code true},
     * then the file has been deleted by some concurrent process before this cleaner plugin
     * tried to delete the file. This flag is used only for reporting this fact in the logs.
     *
     * @see #logDelete(Path, BasicFileAttributes)
     */
    private boolean reallyDeletedLastFile;

    @Nonnull
    private final Path fastDir;

    @Nonnull
    private final String fastMode;

    /**
     * The service to use for creating include and exclude filters.
     * Used for setting a value to {@link #fileMatcher} and {@link #directoryMatcher}.
     */
    @Nonnull
    private final PathMatcherFactory matcherFactory;

    /**
     * Combination of includes and excludes path matchers applied on files.
     */
    @Nonnull
    private PathMatcher fileMatcher;

    /**
     * Combination of includes and excludes path matchers applied on directories.
     */
    @Nonnull
    private PathMatcher directoryMatcher;

    /**
     * Whether the base directory is excluded from the set of directories to delete.
     * This is usually {@code false}, unless explicitly excluded by specifying an
     * empty string in the excludes.
     */
    private boolean isBaseDirectoryExcluded;

    /**
     * Whether to follow symbolic links while deleting files from the directories.
     * This value is specified by the <abbr>MOJO</abbr> plugin configuration,
     * but can be overridden by {@link Fileset}.
     *
     * @see CleanMojo#followSymLinks
     * @see Fileset#followSymlinks
     */
    private boolean followSymlinks;

    /**
     * Whether to force the deletion of read-only files. Note that on Linux,
     * {@link Files#delete(Path)} and {@link Files#deleteIfExists(Path)} delete read-only files
     * but throw {@link AccessDeniedException} if the directory containing the file is read-only.
     */
    private final boolean force;

    /**
     * Whether the build stops if there are I/O errors.
     */
    private final boolean failOnError;

    /**
     * Whether the plugin should undertake additional attempts (after a short delay) to delete a file
     * if the first attempt failed. This is meant to help deleting files that are temporarily locked
     * by third-party tools like virus scanners or search indexing.
     */
    private final boolean retryOnError;

    /**
     * The delays (in milliseconds) if {@link #retryOnError} is {@code true}.
     * The length of this array is the maximal number of new attempts.
     */
    private static final int[] RETRY_DELAYS = new int[] {50, 250, 750};

    /**
     * Number of files that we failed to delete.
     * This is incremented only if {@link #failOnError} is {@code false}, otherwise exceptions are thrown.
     */
    private int failureCount;

    /**
     * Whether each directory level contains at least one excluded file.
     * This is used for determining whether the directory can be deleted.
     * A bit is used for each directory level, with {@link #currentDepth}
     * telling which bit is for the current directory.
     */
    private final BitSet nonEmptyDirectoryLevels;

    /**
     * 0 for the base directory, and incremented for each subdirectory.
     */
    private int currentDepth;

    /**
     * Creates a new cleaner.
     * By default, the cleaner has no include or exclude filters,
     * does not exclude the base directory and does not follow symbolic links.
     * These properties can be modified by {@link #delete(Fileset)}.
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
    Cleaner(
            @Nullable Session session,
            @Nonnull PathMatcherFactory matcherFactory,
            @Nonnull Log logger,
            boolean verbose,
            @Nullable Path fastDir,
            @Nonnull String fastMode,
            boolean followSymlinks,
            boolean force,
            boolean failOnError,
            boolean retryOnError) {
        this.session = session;
        this.matcherFactory = matcherFactory;
        this.logger = logger;
        this.verbose = verbose;
        this.fastDir = fastDir;
        this.fastMode = fastMode;
        this.followSymlinks = followSymlinks;
        this.force = force;
        this.failOnError = failOnError;
        this.retryOnError = retryOnError;
        listDeletedFiles = verbose ? logger.isInfoEnabled() : logger.isDebugEnabled();
        nonEmptyDirectoryLevels = new BitSet();
        fileMatcher = matcherFactory.includesAll();
        directoryMatcher = fileMatcher;
    }

    /**
     * Deletes the specified fileset.
     * This method modifies the include and exclude filters,
     * whether to exclude the base directory and whether to follow symbolic links.
     *
     * @param fileset the fileset to delete
     * @throws IOException if a file/directory could not be deleted and {@link #failOnError} is {@code true}
     */
    public void delete(@Nonnull Fileset fileset) throws IOException {
        fileMatcher = matcherFactory.createPathMatcher(
                fileset.getDirectory(), fileset.getIncludes(), fileset.getExcludes(), fileset.useDefaultExcludes());
        directoryMatcher = matcherFactory.deriveDirectoryMatcher(fileMatcher);
        isBaseDirectoryExcluded = fileset.isBaseDirectoryExcluded();
        followSymlinks = fileset.followSymlinks();
        delete(fileset.getDirectory());
    }

    /**
     * Deletes the specified directory and its contents using the current configuration.
     * Non-existing directories will be silently ignored.
     *
     * <h4>Configuration</h4>
     * The behavior of this method depends on the {@code Cleaner} configuration.
     * Some configuration can be modified by calls to {@link #delete(Fileset)}.
     * Therefore, for deleting files with the default configuration (no include
     * or exclude filters, not following symbolic links), this method should be
     * invoked first.
     *
     * @param basedir the directory to delete, must not be {@code null}
     * @throws IOException if a file/directory could not be deleted and {@code failOnError} is {@code true}
     */
    public void delete(@Nonnull Path basedir) throws IOException {
        if (!Files.isDirectory(basedir)) {
            if (Files.notExists(basedir)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Skipping non-existing directory " + basedir);
                }
                return;
            }
            throw new NotDirectoryException("Invalid base directory " + basedir);
        }
        if (logger.isInfoEnabled()) {
            logger.info("Deleting " + basedir + (isClearAll() ? "" : " (" + fileMatcher + ')'));
        }
        var options = EnumSet.noneOf(FileVisitOption.class);
        if (followSymlinks) {
            options.add(FileVisitOption.FOLLOW_LINKS);
            basedir = getCanonicalPath(basedir, null);
        }
        if (isClearAll() && !followSymlinks && fastDir != null && session != null) {
            // If anything wrong happens, we'll just use the usual deletion mechanism
            if (fastDelete(basedir)) {
                return;
            }
        }
        Files.walkFileTree(basedir, options, Integer.MAX_VALUE, this);
    }

    /**
     * {@return whether {@link #fileMatcher} matches all files}.
     * This is a required condition for allowing the use of {@link #fastDelete(Path)}.
     */
    private boolean isClearAll() {
        return fileMatcher == matcherFactory.includesAll();
    }

    private boolean fastDelete(Path baseDir) {
        // Handle the case where we use ${maven.multiModuleProjectDirectory}/target/.clean for example
        if (fastDir.toAbsolutePath().startsWith(baseDir.toAbsolutePath())) {
            try {
                String prefix = baseDir.getFileName().toString() + '.';
                Path tmpDir = Files.createTempDirectory(baseDir.getParent(), prefix);
                try {
                    Files.move(baseDir, tmpDir, StandardCopyOption.REPLACE_EXISTING);
                    if (session != null) {
                        session.getData().set(LAST_DIRECTORY_TO_DELETE, baseDir);
                    }
                    baseDir = tmpDir;
                } catch (IOException e) {
                    Files.delete(tmpDir);
                    throw e;
                }
            } catch (IOException e) {
                logger.debug("Unable to fast delete directory", e);
                return false;
            }
        }
        // Create fastDir and the needed parents if needed
        try {
            if (!Files.isDirectory(fastDir)) {
                Files.createDirectories(fastDir);
            }
        } catch (IOException e) {
            logger.debug(
                    "Unable to fast delete directory as the path " + fastDir
                            + " does not point to a directory or cannot be created",
                    e);
            return false;
        }
        try {
            Path tmpDir = Files.createTempDirectory(fastDir, "");
            Path dstDir = tmpDir.resolve(baseDir.getFileName());
            // Note that by specifying the ATOMIC_MOVE, we expect an exception to be thrown
            // if the path leads to a directory on another mountpoint.  If this is the case
            // or any other exception occurs, an exception will be thrown in which case
            // the method will return false and the usual deletion will be performed.
            Files.move(baseDir, dstDir, StandardCopyOption.ATOMIC_MOVE);
            BackgroundCleaner.delete(this, tmpDir, fastMode);
            return true;
        } catch (IOException e) {
            logger.debug("Unable to fast delete directory", e);
            return false;
        }
    }

    /**
     * Invoked for a directory before entries in the directory are visited.
     * Determines if the given directory should be scanned for files to delete.
     */
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        if (ON_WINDOWS && !followSymlinks && attrs.isOther()) {
            /*
             * MCLEAN-93: NTFS junctions have isDirectory() and isOther() attributes set.
             * If not following symbolic links, then it should be handled as a file.
             */
            visitFile(dir, attrs);
            return FileVisitResult.SKIP_SUBTREE;
        }
        if (directoryMatcher.matches(dir)) {
            nonEmptyDirectoryLevels.clear(++currentDepth);
            return FileVisitResult.CONTINUE;
        } else {
            nonEmptyDirectoryLevels.set(currentDepth); // Remember that this directory is not empty.
            logger.debug("Not recursing into directory without included files " + dir);
            return FileVisitResult.SKIP_SUBTREE;
        }
    }

    /**
     * Invoked for a file in a directory.
     * Deletes that file, unless the file is excluded by the selector.
     */
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (fileMatcher.matches(file) && tryDelete(file)) {
            if (listDeletedFiles) {
                logDelete(file, attrs);
            }
        } else {
            nonEmptyDirectoryLevels.set(currentDepth); // Remember that the directory will not be empty.
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * Invoked for a file that could not be visited.
     */
    @Override
    public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
        if (logger.isWarnEnabled()) {
            logger.warn("Failed to visit " + file, failure);
        }
        failureCount++;
        nonEmptyDirectoryLevels.set(currentDepth); // Remember that the directory will not be empty.
        if (failOnError) {
            throw failure;
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * Invoked for a directory after all files and sub-trees have been visited.
     * If the directory is not empty, then this method deletes it.
     */
    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
        boolean canDelete = !nonEmptyDirectoryLevels.get(currentDepth--); // False if the directory is not empty.
        if (failure != null) {
            if (logger.isWarnEnabled()) {
                logger.warn("Error in directory " + dir, failure);
            }
            failureCount++;
            canDelete = false;
        } else {
            canDelete &= (currentDepth != 0 || !isBaseDirectoryExcluded);
            if (canDelete) {
                canDelete = fileMatcher.matches(dir);
            }
        }
        if (canDelete && tryDelete(dir)) {
            if (listDeletedFiles) {
                logDelete(dir, null);
            }
        } else {
            nonEmptyDirectoryLevels.set(currentDepth); // Remember that the parent of `dir` is not empty.
        }
        if (failure != null && failOnError) {
            throw failure;
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * Returns the real path of the given file. If the real path cannot be obtained,
     * this method tries to get the real path of the parent and to append the rest of
     * the filename.
     *
     * @param  path       the path to get as a canonical path
     * @param  mainError  should be {@code null} (reserved to recursive calls of this method)
     * @return the real path of the given path
     * @throws IOException if the canonical path cannot be obtained
     */
    private static Path getCanonicalPath(final Path path, IOException mainError) throws IOException {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            if (mainError == null) {
                mainError = e;
            } else {
                mainError.addSuppressed(e);
            }
            final Path parent = path.getParent();
            if (parent != null) {
                return getCanonicalPath(parent, mainError).resolve(path.getFileName());
            }
            throw e;
        }
    }

    /**
     * Makes the given file or directory writable.
     * If the file is already writable, then this method tries to make the parent directory writable.
     *
     * @param file the path to the file or directory to make writable, or {@code null} if none
     * @param currentDepth 0 for the base directory, and decremented for each parent directory
     * @return the root path which has been made writable, or {@code null} if none
     */
    private static Path setWritable(Path file, int currentDepth) throws IOException {
        while (file != null) {
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) {
                EnumSet<PosixFilePermission> permissions =
                        EnumSet.copyOf(posix.readAttributes().permissions());
                if (permissions.add(PosixFilePermission.OWNER_WRITE)) {
                    posix.setPermissions(permissions);
                    return file;
                }
            } else {
                DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
                if (dos == null) {
                    return null; // Unknown type of file attributes.
                }
                // No need to update the parent directory because DOS read-only attribute does not apply to folders.
                dos.setReadOnly(false);
                return file;
            }
            // File was already writable. Maybe it is the parent directory which was not writable.
            if (--currentDepth < 0) {
                break;
            }
            file = file.getParent();
        }
        return null;
    }

    /**
     * Deletes the specified file or directory.
     * If the path denotes a symlink, only the link is removed. Its target is left untouched.
     * This method returns {@code true} if the file has been deleted, or {@code false} if the
     * file does not exist or if an {@link IOException} occurred but {@link #failOnError} is
     * {@code false}.
     *
     * <h4>Auxiliary information as side-effect</h4>
     * This method sets the {@link #reallyDeletedLastFile} flag to whether this method really deleted the file.
     * If that flag is {@code false} after this method returned {@code true}, then the file has been deleted by
     * some concurrent process before this method tried to deleted the file.
     * That flag is used for logging purpose only.
     *
     * @param  file the file/directory to delete, must not be {@code null}
     * @return whether the file has been deleted or did not existed anymore by the time this method is invoked
     * @throws IOException if a file/directory could not be deleted and {@code failOnError} is {@code true}
     */
    @SuppressWarnings("SleepWhileInLoop")
    private boolean tryDelete(final Path file) throws IOException {
        try {
            reallyDeletedLastFile = Files.deleteIfExists(file);
            return true;
        } catch (IOException failure) {
            boolean tryWritable = force && failure instanceof AccessDeniedException;
            if (tryWritable || retryOnError) {
                final Set<Path> madeWritable; // Safety against never-ending loops.
                if (force) {
                    madeWritable = new HashSet<>();
                    madeWritable.add(null); // For having `add(null)` to return `false`.
                } else {
                    madeWritable = null;
                }
                final var alreadyReported = new HashMap<Class<?>, Set<String>>(); // For avoiding repetition.
                isNewError(alreadyReported, failure);
                int delayIndex = 0;
                while (delayIndex < RETRY_DELAYS.length) {
                    if (tryWritable) {
                        tryWritable = madeWritable.add(setWritable(file, currentDepth));
                        // `true` if we successfully changed permission, in which case we will skip the delay.
                    }
                    if (!tryWritable) {
                        if (ON_WINDOWS) {
                            // Try to release any locks held by non-closed files.
                            System.gc();
                        }
                        try {
                            Thread.sleep(RETRY_DELAYS[delayIndex++]);
                        } catch (InterruptedException e) {
                            failure.addSuppressed(e);
                            throw failure;
                        }
                    }
                    try {
                        reallyDeletedLastFile = Files.deleteIfExists(file);
                        return true;
                    } catch (IOException again) {
                        tryWritable = force && failure instanceof AccessDeniedException;
                        if (isNewError(alreadyReported, again)) {
                            failure.addSuppressed(again);
                        }
                    }
                }
            }
            reallyDeletedLastFile = false; // As a matter of principle, but not really needed.
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to delete " + file, failure);
            }
            failureCount++;
            if (failOnError) {
                throw failure;
            }
            return false;
        }
    }

    /**
     * Returns {@code true} if the given exception has not been reported before.
     */
    private static boolean isNewError(Map<Class<?>, Set<String>> reported, Exception e) {
        return reported.computeIfAbsent(e.getClass(), (key) -> new HashSet<>()).add(e.getMessage());
    }

    /**
     * Reports that a file, directory or symbolic link has been deleted. This method should be invoked only
     * when {@link #tryDelete(Path)} returned {@code true} and {@link #listDeletedFiles} is {@code true}.
     *
     * <p>If {@code attrs} is {@code null}, then the file is assumed a directory. This arbitrary rule
     * is an implementation convenience specific to the context in which we invoke this method.</p>
     */
    private void logDelete(final Path file, final BasicFileAttributes attrs) {
        String message;
        if (attrs == null || attrs.isDirectory()) {
            message = "Deleted directory ";
        } else if (attrs.isRegularFile()) {
            message = "Deleted file ";
        } else if (attrs.isSymbolicLink()) {
            message = "Deleted dangling symlink ";
        } else {
            message = "Deleted ";
        }
        boolean info = verbose;
        if (!reallyDeletedLastFile) {
            info = true;
            message = "Another process deleted concurrently" + message.substring(message.indexOf(' '));
        }
        message += file;
        if (info) {
            logger.info(message);
        } else {
            logger.debug(message);
        }
    }

    private static class BackgroundCleaner extends Thread {

        private static BackgroundCleaner instance;

        private final Deque<Path> filesToDelete = new ArrayDeque<>();

        private final Cleaner cleaner;

        private final String fastMode;

        private static final int NEW = 0;
        private static final int RUNNING = 1;
        private static final int STOPPED = 2;

        private int status = NEW;

        public static void delete(Cleaner cleaner, Path dir, String fastMode) {
            synchronized (BackgroundCleaner.class) {
                if (instance == null || !instance.doDelete(dir)) {
                    instance = new BackgroundCleaner(cleaner, dir, fastMode);
                }
            }
        }

        static void sessionEnd() {
            synchronized (BackgroundCleaner.class) {
                if (instance != null) {
                    instance.doSessionEnd();
                }
            }
        }

        private BackgroundCleaner(Cleaner cleaner, Path dir, String fastMode) {
            super("mvn-background-cleaner");
            this.cleaner = cleaner;
            this.fastMode = fastMode;
            init(cleaner.fastDir, dir);
        }

        @Override
        public void run() {
            var options = EnumSet.noneOf(FileVisitOption.class);
            if (cleaner.followSymlinks) {
                options.add(FileVisitOption.FOLLOW_LINKS);
            }
            Path basedir;
            while ((basedir = pollNext()) != null) {
                try {
                    Files.walkFileTree(basedir, options, Integer.MAX_VALUE, cleaner);
                } catch (IOException e) {
                    // do not display errors
                }
            }
        }

        synchronized void init(Path fastDir, Path dir) {
            if (Files.isDirectory(fastDir)) {
                try {
                    try (Stream<Path> children = Files.list(fastDir)) {
                        children.forEach(this::doDelete);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            doDelete(dir);
        }

        synchronized Path pollNext() {
            Path basedir = filesToDelete.poll();
            if (basedir == null) {
                if (cleaner.session != null) {
                    SessionData data = cleaner.session.getData();
                    Path lastDir = data.get(LAST_DIRECTORY_TO_DELETE);
                    if (lastDir != null) {
                        data.set(LAST_DIRECTORY_TO_DELETE, null);
                        return lastDir;
                    }
                }
                status = STOPPED;
                notifyAll();
            }
            return basedir;
        }

        synchronized boolean doDelete(Path dir) {
            if (status == STOPPED) {
                return false;
            }
            filesToDelete.add(dir);
            if (status == NEW && CleanMojo.FAST_MODE_BACKGROUND.equals(fastMode)) {
                status = RUNNING;
                notifyAll();
                start();
            }
            wrapExecutionListener();
            return true;
        }

        /**
         * If this has not been done already, we wrap the ExecutionListener inside a proxy
         * which simply delegates call to the previous listener.  When the session ends, it will
         * also call {@link BackgroundCleaner#sessionEnd()}.
         * There's no clean API to do that properly as this is a very unusual use case for a plugin
         * to outlive its main execution.
         */
        private void wrapExecutionListener() {
            synchronized (CleanerListener.class) {
                if (cleaner.session.getListeners().stream().noneMatch(l -> l instanceof CleanerListener)) {
                    cleaner.session.registerListener(new CleanerListener());
                }
            }
        }

        synchronized void doSessionEnd() {
            if (status != STOPPED) {
                if (status == NEW) {
                    start();
                }
                if (!CleanMojo.FAST_MODE_DEFER.equals(fastMode)) {
                    try {
                        cleaner.logger.info("Waiting for background file deletion");
                        while (status != STOPPED) {
                            wait();
                        }
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
        }
    }

    static class CleanerListener implements Listener {
        @Override
        public void onEvent(Event event) {
            if (event.getType() == EventType.SESSION_ENDED) {
                BackgroundCleaner.sessionEnd();
            }
        }
    }
}
