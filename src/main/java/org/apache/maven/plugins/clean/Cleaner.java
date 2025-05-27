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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.Os;
import org.eclipse.aether.SessionData;

import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.newDirectoryStream;
import static org.apache.maven.plugins.clean.CleanMojo.FAST_MODE_BACKGROUND;
import static org.apache.maven.plugins.clean.CleanMojo.FAST_MODE_DEFER;

/**
 * Cleans directories.
 *
 * @author Benjamin Bentmann
 */
class Cleaner {

    private static final boolean ON_WINDOWS = Os.isFamily(Os.FAMILY_WINDOWS);

    private static final String LAST_DIRECTORY_TO_DELETE = Cleaner.class.getName() + ".lastDirectoryToDelete";

    /**
     * The maven session.  This is typically non-null in a real run, but it can be during unit tests.
     */
    private final MavenSession session;

    private final Path fastDir;

    private final String fastMode;

    private final boolean verbose;

    private Log log;

    /**
     * Whether to force the deletion of read-only files. Note that on Linux,
     * {@link Files#delete(Path)} and {@link Files#deleteIfExists(Path)} delete read-only files
     * but throw {@link AccessDeniedException} if the directory containing the file is read-only.
     */
    private final boolean force;

    /**
     * Creates a new cleaner.
     *
     * @param session  The Maven session to be used.
     * @param log      The logger to use.
     * @param verbose  Whether to perform verbose logging.
     * @param fastDir  The explicit configured directory or to be deleted in fast mode.
     * @param fastMode The fast deletion mode.
     * @param force    whether to force the deletion of read-only files
     */
    Cleaner(MavenSession session, final Log log, boolean verbose, Path fastDir, String fastMode, boolean force) {
        this.session = session;
        // This can't be null as the Cleaner gets it from the CleanMojo which gets it from AbstractMojo class, where it
        // is never null.
        this.log = log;
        this.fastDir = fastDir;
        this.fastMode = fastMode;
        this.verbose = verbose;
        this.force = force;
    }

    /**
     * Deletes the specified directories and its contents.
     *
     * @param basedir        The directory to delete, must not be <code>null</code>. Non-existing directories will be silently
     *                       ignored.
     * @param selector       The selector used to determine what contents to delete, may be <code>null</code> to delete
     *                       everything.
     * @param followSymlinks Whether to follow symlinks.
     * @param failOnError    Whether to abort with an exception in case a selected file/directory could not be deleted.
     * @param retryOnError   Whether to undertake additional delete attempts in case the first attempt failed.
     * @throws IOException If a file/directory could not be deleted and <code>failOnError</code> is <code>true</code>.
     */
    public void delete(
            Path basedir, Selector selector, boolean followSymlinks, boolean failOnError, boolean retryOnError)
            throws IOException {
        if (!isDirectory(basedir)) {
            if (!exists(basedir)) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping non-existing directory " + basedir);
                }
                return;
            }
            throw new IOException("Invalid base directory " + basedir);
        }

        if (log.isInfoEnabled()) {
            log.info("Deleting " + basedir + (selector != null ? " (" + selector + ")" : ""));
        }

        Path file = followSymlinks ? basedir : basedir.toRealPath();

        if (selector == null && !followSymlinks && fastDir != null && session != null) {
            // If anything wrong happens, we'll just use the usual deletion mechanism
            if (fastDelete(file)) {
                return;
            }
        }

        delete(file, "", selector, followSymlinks, failOnError, retryOnError);
    }

    private boolean fastDelete(Path baseDir) {
        // Handle the case where we use ${maven.multiModuleProjectDirectory}/target/.clean for example
        if (fastDir.toAbsolutePath().startsWith(baseDir.toAbsolutePath())) {
            try {
                String prefix = baseDir.getFileName().toString() + ".";
                Path tmpDir = Files.createTempDirectory(baseDir.getParent(), prefix);
                try {
                    Files.move(baseDir, tmpDir, StandardCopyOption.REPLACE_EXISTING);
                    if (session != null) {
                        session.getRepositorySession().getData().set(LAST_DIRECTORY_TO_DELETE, baseDir);
                    }
                    baseDir = tmpDir;
                } catch (IOException e) {
                    Files.delete(tmpDir);
                    throw e;
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Unable to fast delete directory: ", e);
                }
                return false;
            }
        }
        // Create fastDir and the needed parents if needed
        try {
            if (!isDirectory(fastDir)) {
                Files.createDirectories(fastDir);
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "Unable to fast delete directory as the path " + fastDir
                                + " does not point to a directory or cannot be created: ",
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
            if (log.isDebugEnabled()) {
                log.debug("Unable to fast delete directory: ", e);
            }
            return false;
        }
    }

    /**
     * Deletes the specified file or directory.
     *
     * @param file           The file/directory to delete, must not be <code>null</code>. If <code>followSymlinks</code> is
     *                       <code>false</code>, it is assumed that the parent file is canonical.
     * @param pathname       The relative pathname of the file, using {@link File#separatorChar}, must not be
     *                       <code>null</code>.
     * @param selector       The selector used to determine what contents to delete, may be <code>null</code> to delete
     *                       everything.
     * @param followSymlinks Whether to follow symlinks.
     * @param failOnError    Whether to abort with an exception in case a selected file/directory could not be deleted.
     * @param retryOnError   Whether to undertake additional delete attempts in case the first attempt failed.
     * @return The result of the cleaning, never <code>null</code>.
     * @throws IOException If a file/directory could not be deleted and <code>failOnError</code> is <code>true</code>.
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

        boolean isDirectory = isDirectory(file);

        if (isDirectory) {
            if (selector == null || selector.couldHoldSelected(pathname)) {
                if (followSymlinks || !isSymbolicLink(file)) {
                    Path canonical = followSymlinks ? file : file.toRealPath();
                    String prefix = pathname.length() > 0 ? pathname + File.separatorChar : "";
                    try (DirectoryStream<Path> children = newDirectoryStream(canonical)) {
                        for (Path child : children) {
                            String filename = child.getFileName().toString();
                            result.update(delete(
                                    child, prefix + filename, selector, followSymlinks, failOnError, retryOnError));
                        }
                    }
                } else if (log.isDebugEnabled()) {
                    log.debug("Not recursing into symlink " + file);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("Not recursing into directory without included files " + file);
            }
        }

        if (!result.excluded && (selector == null || selector.isSelected(pathname))) {
            String logmessage;
            if (isDirectory) {
                logmessage = "Deleting directory " + file;
            } else if (exists(file)) {
                logmessage = "Deleting file " + file;
            } else {
                logmessage = "Deleting dangling symlink " + file;
            }

            if (verbose && log.isInfoEnabled()) {
                log.info(logmessage);
            } else if (log.isDebugEnabled()) {
                log.debug(logmessage);
            }

            result.failures += delete(file, failOnError, retryOnError);
        } else {
            result.excluded = true;
        }

        return result;
    }

    private boolean isSymbolicLink(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.isSymbolicLink()
                // MCLEAN-93: NTFS junctions have isDirectory() and isOther() attributes set
                || (attrs.isDirectory() && attrs.isOther());
    }

    /**
     * Makes the given file or directory writable.
     * If the file is already writable, then this method tries to make the parent directory writable.
     *
     * @param file the path to the file or directory to make writable, or {@code null} if none
     * @return the root path which has been made writable, or {@code null} if none
     */
    private static Path setWritable(Path file) throws IOException {
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
            file = file.getParent();
        }
        return null;
    }

    /**
     * Deletes the specified file, directory. If the path denotes a symlink, only the link is removed, its target is
     * left untouched.
     *
     * @param file         The file/directory to delete, must not be <code>null</code>.
     * @param failOnError  Whether to abort with an exception in case the file/directory could not be deleted.
     * @param retryOnError Whether to undertake additional delete attempts in case the first attempt failed.
     * @return <code>0</code> if the file was deleted, <code>1</code> otherwise.
     * @throws IOException If a file/directory could not be deleted and <code>failOnError</code> is <code>true</code>.
     */
    private int delete(Path file, boolean failOnError, boolean retryOnError) throws IOException {
        IOException failure = delete(file);
        if (failure != null) {
            boolean deleted = false;
            boolean tryWritable = force && failure instanceof AccessDeniedException;
            if (tryWritable || retryOnError) {
                final Set<Path> madeWritable; // Safety against never-ending loops.
                if (force) {
                    madeWritable = new HashSet<>();
                    madeWritable.add(null); // For having `add(null)` to return `false`.
                } else {
                    madeWritable = null;
                }
                final int[] delays = {50, 250, 750};
                int delayIndex = 0;
                while (!deleted && delayIndex < delays.length) {
                    if (tryWritable) {
                        tryWritable = madeWritable.add(setWritable(file));
                        // `true` if we successfully changed permission, in which case we will skip the delay.
                    }
                    if (!tryWritable) {
                        if (ON_WINDOWS) {
                            // Try to release any locks held by non-closed files.
                            System.gc();
                        }
                        try {
                            Thread.sleep(delays[delayIndex++]);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    }
                    deleted = delete(file) == null || !exists(file);
                    tryWritable = !deleted && force && failure instanceof AccessDeniedException;
                }
            } else {
                deleted = !exists(file);
            }

            if (!deleted) {
                if (failOnError) {
                    throw new IOException("Failed to delete " + file, failure);
                } else {
                    if (log.isWarnEnabled()) {
                        log.warn("Failed to delete " + file, failure);
                    }
                    return 1;
                }
            }
        }

        return 0;
    }

    private static IOException delete(Path file) {
        try {
            Files.delete(file);
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

    private static class BackgroundCleaner extends Thread {

        private static final int NEW = 0;
        private static final int RUNNING = 1;
        private static final int STOPPED = 2;
        private static BackgroundCleaner instance;
        private final Deque<Path> filesToDelete = new ArrayDeque<>();
        private final Cleaner cleaner;
        private final String fastMode;
        private int status = NEW;

        private BackgroundCleaner(Cleaner cleaner, Path dir, String fastMode) throws IOException {
            super("mvn-background-cleaner");
            this.cleaner = cleaner;
            this.fastMode = fastMode;
            init(cleaner.fastDir, dir);
        }

        public static void delete(Cleaner cleaner, Path dir, String fastMode) throws IOException {
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

        synchronized void init(Path fastDir, Path dir) throws IOException {
            if (isDirectory(fastDir)) {
                try (DirectoryStream<Path> children = newDirectoryStream(fastDir)) {
                    for (Path child : children) {
                        doDelete(child);
                    }
                }
            }
            doDelete(dir);
        }

        synchronized Path pollNext() {
            Path basedir = filesToDelete.poll();
            if (basedir == null) {
                if (cleaner.session != null) {
                    SessionData data = cleaner.session.getRepositorySession().getData();
                    Path lastDir = (Path) data.get(LAST_DIRECTORY_TO_DELETE);
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
            ExecutionListener executionListener = cleaner.session.getRequest().getExecutionListener();
            if (executionListener == null
                    || !Proxy.isProxyClass(executionListener.getClass())
                    || !(Proxy.getInvocationHandler(executionListener) instanceof SpyInvocationHandler)) {
                ExecutionListener listener = (ExecutionListener) Proxy.newProxyInstance(
                        ExecutionListener.class.getClassLoader(),
                        new Class[] {ExecutionListener.class},
                        new SpyInvocationHandler(executionListener));
                cleaner.session.getRequest().setExecutionListener(listener);
            }
        }

        synchronized void doSessionEnd() {
            if (status != STOPPED) {
                if (status == NEW) {
                    start();
                }
                if (!FAST_MODE_DEFER.equals(fastMode)) {
                    try {
                        if (cleaner.log.isInfoEnabled()) {
                            cleaner.log.info("Waiting for background file deletion");
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

    static class SpyInvocationHandler implements InvocationHandler {
        private final ExecutionListener delegate;

        SpyInvocationHandler(ExecutionListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("sessionEnded".equals(method.getName())) {
                BackgroundCleaner.sessionEnd();
            }
            if (delegate != null) {
                return method.invoke(delegate, args);
            }
            return null;
        }
    }
}
