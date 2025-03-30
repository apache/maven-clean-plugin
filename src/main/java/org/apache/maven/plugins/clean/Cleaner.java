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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.maven.api.Event;
import org.apache.maven.api.EventType;
import org.apache.maven.api.Listener;
import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.plugin.Log;
import org.codehaus.plexus.util.Os;

import static org.apache.maven.plugins.clean.CleanMojo.FAST_MODE_BACKGROUND;
import static org.apache.maven.plugins.clean.CleanMojo.FAST_MODE_DEFER;

/**
 * Cleans directories.
 *
 * @author Benjamin Bentmann
 */
class Cleaner {

    private static final boolean ON_WINDOWS = Os.isFamily(Os.FAMILY_WINDOWS);

    private static final SessionData.Key<Path> LAST_DIRECTORY_TO_DELETE =
            SessionData.key(Path.class, Cleaner.class.getName() + ".lastDirectoryToDelete");

    /**
     * The maven session.  This is typically non-null in a real run, but it can be during unit tests.
     */
    private final Session session;

    private final Logger logDebug;

    private final Logger logInfo;

    private final Logger logVerbose;

    private final Logger logWarn;

    private final Path fastDir;

    private final String fastMode;

    /**
     * Creates a new cleaner.
     *
     * @param session  the Maven session to be used
     * @param log      the logger to use, may be <code>null</code> to disable logging
     * @param verbose  whether to perform verbose logging
     * @param fastDir  the explicit configured directory or to be deleted in fast mode
     * @param fastMode the fast deletion mode
     */
    Cleaner(Session session, Log log, boolean verbose, Path fastDir, String fastMode) {
        logDebug = (log == null || !log.isDebugEnabled()) ? null : logger(log::debug, log::debug);

        logInfo = (log == null || !log.isInfoEnabled()) ? null : logger(log::info, log::info);

        logWarn = (log == null || !log.isWarnEnabled()) ? null : logger(log::warn, log::warn);

        logVerbose = verbose ? logInfo : logDebug;

        this.session = session;
        this.fastDir = fastDir;
        this.fastMode = fastMode;
    }

    private Logger logger(Consumer<CharSequence> l1, BiConsumer<CharSequence, Throwable> l2) {
        return new Logger() {
            @Override
            public void log(CharSequence message) {
                l1.accept(message);
            }

            @Override
            public void log(CharSequence message, Throwable t) {
                l2.accept(message, t);
            }
        };
    }

    /**
     * Deletes the specified directories and its contents.
     *
     * @param basedir the directory to delete, must not be <code>null</code>. Non-existing directories will be silently
     *            ignored
     * @param selector the selector used to determine what contents to delete, may be <code>null</code> to delete
     *            everything
     * @param followSymlinks whether to follow symlinks
     * @param failOnError whether to abort with an exception in case a selected file/directory could not be deleted
     * @param retryOnError whether to undertake additional delete attempts in case the first attempt failed
     * @throws IOException if a file/directory could not be deleted and <code>failOnError</code> is <code>true</code>
     */
    public void delete(
            Path basedir, Selector selector, boolean followSymlinks, boolean failOnError, boolean retryOnError)
            throws IOException {
        if (!Files.isDirectory(basedir)) {
            if (!Files.exists(basedir)) {
                if (logDebug != null) {
                    logDebug.log("Skipping non-existing directory " + basedir);
                }
                return;
            }
            throw new IOException("Invalid base directory " + basedir);
        }

        if (logInfo != null) {
            logInfo.log("Deleting " + basedir + (selector != null ? " (" + selector + ")" : ""));
        }

        Path file = followSymlinks ? basedir : getCanonicalPath(basedir);

        if (selector == null && !followSymlinks && fastDir != null && session != null) {
            // If anything wrong happens, we'll just use the usual deletion mechanism
            if (fastDelete(file)) {
                return;
            }
        }

        delete(file, "", selector, followSymlinks, failOnError, retryOnError);
    }

    private boolean fastDelete(Path baseDir) {
        Path fastDir = this.fastDir;
        // Handle the case where we use ${maven.multiModuleProjectDirectory}/target/.clean for example
        if (fastDir.toAbsolutePath().startsWith(baseDir.toAbsolutePath())) {
            try {
                String prefix = baseDir.getFileName().toString() + ".";
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
                if (logDebug != null) {
                    logDebug.log("Unable to fast delete directory", e);
                }
                return false;
            }
        }
        // Create fastDir and the needed parents if needed
        try {
            if (!Files.isDirectory(fastDir)) {
                Files.createDirectories(fastDir);
            }
        } catch (IOException e) {
            if (logDebug != null) {
                logDebug.log(
                        "Unable to fast delete directory as the path " + fastDir
                                + " does not point to a directory or cannot be created",
                        e);
            }
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
            if (logDebug != null) {
                logDebug.log("Unable to fast delete directory", e);
            }
            return false;
        }
    }

    /**
     * Deletes the specified file or directory.
     *
     * @param file the file/directory to delete, must not be <code>null</code>. If <code>followSymlinks</code> is
     *            <code>false</code>, it is assumed that the parent file is canonical
     * @param pathname the relative pathname of the file, using {@link File#separatorChar}, must not be
     *            <code>null</code>
     * @param selector the selector used to determine what contents to delete, may be <code>null</code> to delete
     *            everything
     * @param followSymlinks whether to follow symlinks
     * @param failOnError whether to abort with an exception in case a selected file/directory could not be deleted
     * @param retryOnError whether to undertake additional delete attempts in case the first attempt failed
     * @return The result of the cleaning, never <code>null</code>
     * @throws IOException if a file/directory could not be deleted and <code>failOnError</code> is <code>true</code>
     */
    private Result delete(
            Path file,
            String pathname,
            Selector selector,
            boolean followSymlinks,
            boolean failOnError,
            boolean retryOnError)
            throws IOException {
        Result result = new Result();

        boolean isDirectory = Files.isDirectory(file);

        if (isDirectory) {
            if (selector == null || selector.couldHoldSelected(pathname)) {
                final boolean isSymlink = isSymbolicLink(file);
                Path canonical = followSymlinks ? file : getCanonicalPath(file);
                if (followSymlinks || !isSymlink) {
                    String prefix = !pathname.isEmpty() ? pathname + File.separatorChar : "";
                    try (Stream<Path> children = Files.list(canonical)) {
                        for (Path child : children.toList()) {
                            result.update(delete(
                                    child,
                                    prefix + child.getFileName(),
                                    selector,
                                    followSymlinks,
                                    failOnError,
                                    retryOnError));
                        }
                    }
                } else if (logDebug != null) {
                    logDebug.log("Not recursing into symlink " + file);
                }
            } else if (logDebug != null) {
                logDebug.log("Not recursing into directory without included files " + file);
            }
        }

        if (!result.excluded && (selector == null || selector.isSelected(pathname))) {
            if (logVerbose != null) {
                if (isDirectory) {
                    logVerbose.log("Deleting directory " + file);
                } else if (Files.exists(file)) {
                    logVerbose.log("Deleting file " + file);
                } else {
                    logVerbose.log("Deleting dangling symlink " + file);
                }
            }
            result.failures += delete(file, failOnError, retryOnError);
        } else {
            result.excluded = true;
        }

        return result;
    }

    private static Path getCanonicalPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return getCanonicalPath(path.getParent()).resolve(path.getFileName());
        }
    }

    private boolean isSymbolicLink(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.isSymbolicLink()
                // MCLEAN-93: NTFS junctions have isDirectory() and isOther() attributes set
                || (attrs.isDirectory() && attrs.isOther());
    }

    /**
     * Deletes the specified file or directory. If the path denotes a symlink, only the link is removed. Its target is
     * left untouched.
     *
     * @param file         the file/directory to delete, must not be <code>null</code>
     * @param failOnError  whether to abort with an exception if the file/directory could not be deleted
     * @param retryOnError whether to undertake additional delete attempts if the first attempt failed
     * @return <code>0</code> if the file was deleted, <code>1</code> otherwise
     * @throws IOException if a file/directory could not be deleted and <code>failOnError</code> is <code>true</code>
     */
    private int delete(Path file, boolean failOnError, boolean retryOnError) throws IOException {
        IOException failure = delete(file);
        if (failure != null) {

            if (retryOnError) {
                if (ON_WINDOWS) {
                    // try to release any locks held by non-closed files
                    System.gc();
                }

                final int[] delays = {50, 250, 750};
                for (int delay : delays) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    failure = delete(file);
                    if (failure == null) {
                        break;
                    }
                }
            }

            if (Files.exists(file)) {
                if (failOnError) {
                    throw new IOException("Failed to delete " + file, failure);
                } else {
                    if (logWarn != null) {
                        logWarn.log("Failed to delete " + file, failure);
                    }
                    return 1;
                }
            }
        }

        return 0;
    }

    private static IOException delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            return e;
        }
        return null;
    }

    private static class Result {

        private int failures;

        private boolean excluded;

        public void update(Result result) {
            failures += result.failures;
            excluded |= result.excluded;
        }
    }

    private interface Logger {

        void log(CharSequence message);

        void log(CharSequence message, Throwable t);
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
            while (true) {
                Path basedir = pollNext();
                if (basedir == null) {
                    break;
                }
                try {
                    cleaner.delete(basedir, "", null, false, false, true);
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
            if (status == NEW && FAST_MODE_BACKGROUND.equals(fastMode)) {
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
                if (!FAST_MODE_DEFER.equals(fastMode)) {
                    try {
                        if (cleaner.logInfo != null) {
                            cleaner.logInfo.log("Waiting for background file deletion");
                        }
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
