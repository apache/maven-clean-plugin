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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;

import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.utils.Os;
import org.eclipse.aether.SessionData;

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

    private final Logger logDebug;

    private final Logger logInfo;

    private final Logger logVerbose;

    private final Logger logWarn;

    private final File fastDir;

    private final String fastMode;

    /**
     * Creates a new cleaner.
     * @param log The logger to use, may be <code>null</code> to disable logging.
     * @param verbose Whether to perform verbose logging.
     * @param fastMode The fast deletion mode
     */
    Cleaner(MavenSession session, final Log log, boolean verbose, File fastDir, String fastMode) {
        logDebug = (log == null || !log.isDebugEnabled()) ? null : log::debug;

        logInfo = (log == null || !log.isInfoEnabled()) ? null : log::info;

        logWarn = (log == null || !log.isWarnEnabled()) ? null : log::warn;

        logVerbose = verbose ? logInfo : logDebug;

        this.session = session;
        this.fastDir = fastDir;
        this.fastMode = fastMode;
    }

    /**
     * Deletes the specified directories and its contents.
     *
     * @param basedir The directory to delete, must not be <code>null</code>. Non-existing directories will be silently
     *            ignored.
     * @param selector The selector used to determine what contents to delete, may be <code>null</code> to delete
     *            everything.
     * @param followSymlinks Whether to follow symlinks.
     * @param failOnError Whether to abort with an exception in case a selected file/directory could not be deleted.
     * @param retryOnError Whether to undertake additional delete attempts in case the first attempt failed.
     * @throws IOException If a file/directory could not be deleted and <code>failOnError</code> is <code>true</code>.
     */
    public void delete(
            File basedir, Selector selector, boolean followSymlinks, boolean failOnError, boolean retryOnError)
            throws IOException {
        if (!basedir.isDirectory()) {
            if (!basedir.exists()) {
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

        File file = followSymlinks ? basedir : basedir.getCanonicalFile();

        if (selector == null && !followSymlinks && fastDir != null && session != null) {
            // If anything wrong happens, we'll just use the usual deletion mechanism
            if (fastDelete(file)) {
                return;
            }
        }

        delete(file, "", selector, followSymlinks, failOnError, retryOnError);
    }

    private boolean fastDelete(File baseDirFile) {
        Path baseDir = baseDirFile.toPath();
        Path fastDir = this.fastDir.toPath();
        // Handle the case where we use ${maven.multiModuleProjectDirectory}/target/.clean for example
        if (fastDir.toAbsolutePath().startsWith(baseDir.toAbsolutePath())) {
            try {
                String prefix = baseDir.getFileName().toString() + ".";
                Path tmpDir = Files.createTempDirectory(baseDir.getParent(), prefix);
                try {
                    Files.move(baseDir, tmpDir, StandardCopyOption.REPLACE_EXISTING);
                    if (session != null) {
                        session.getRepositorySession().getData().set(LAST_DIRECTORY_TO_DELETE, baseDir.toFile());
                    }
                    baseDir = tmpDir;
                } catch (IOException e) {
                    Files.delete(tmpDir);
                    throw e;
                }
            } catch (IOException e) {
                if (logDebug != null) {
                    // TODO: this Logger interface cannot log exceptions and needs refactoring
                    logDebug.log("Unable to fast delete directory: " + e);
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
                // TODO: this Logger interface cannot log exceptions and needs refactoring
                logDebug.log("Unable to fast delete directory as the path " + fastDir
                        + " does not point to a directory or cannot be created: " + e);
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
            BackgroundCleaner.delete(this, tmpDir.toFile(), fastMode);
            return true;
        } catch (IOException e) {
            if (logDebug != null) {
                // TODO: this Logger interface cannot log exceptions and needs refactoring
                logDebug.log("Unable to fast delete directory: " + e);
            }
            return false;
        }
    }

    /**
     * Deletes the specified file or directory.
     *
     * @param file The file/directory to delete, must not be <code>null</code>. If <code>followSymlinks</code> is
     *            <code>false</code>, it is assumed that the parent file is canonical.
     * @param pathname The relative pathname of the file, using {@link File#separatorChar}, must not be
     *            <code>null</code>.
     * @param selector The selector used to determine what contents to delete, may be <code>null</code> to delete
     *            everything.
     * @param followSymlinks Whether to follow symlinks.
     * @param failOnError Whether to abort with an exception in case a selected file/directory could not be deleted.
     * @param retryOnError Whether to undertake additional delete attempts in case the first attempt failed.
     * @return The result of the cleaning, never <code>null</code>.
     * @throws IOException If a file/directory could not be deleted and <code>failOnError</code> is <code>true</code>.
     */
    private Result delete(
            File file,
            String pathname,
            Selector selector,
            boolean followSymlinks,
            boolean failOnError,
            boolean retryOnError)
            throws IOException {
        Result result = new Result();

        boolean isDirectory = file.isDirectory();

        if (isDirectory) {
            if (selector == null || selector.couldHoldSelected(pathname)) {
                final boolean isSymlink = isSymbolicLink(file.toPath());
                File canonical = followSymlinks ? file : file.getCanonicalFile();
                if (followSymlinks || !isSymlink) {
                    String[] filenames = canonical.list();
                    if (filenames != null) {
                        String prefix = pathname.length() > 0 ? pathname + File.separatorChar : "";
                        for (int i = filenames.length - 1; i >= 0; i--) {
                            String filename = filenames[i];
                            File child = new File(canonical, filename);
                            result.update(delete(
                                    child, prefix + filename, selector, followSymlinks, failOnError, retryOnError));
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
                } else if (file.exists()) {
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

    private boolean isSymbolicLink(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.isSymbolicLink() || (attrs.isDirectory() && attrs.isOther());
    }

    /**
     * Deletes the specified file, directory. If the path denotes a symlink, only the link is removed, its target is
     * left untouched.
     *
     * @param file The file/directory to delete, must not be <code>null</code>.
     * @param failOnError Whether to abort with an exception in case the file/directory could not be deleted.
     * @param retryOnError Whether to undertake additional delete attempts in case the first attempt failed.
     * @return <code>0</code> if the file was deleted, <code>1</code> otherwise.
     * @throws IOException If a file/directory could not be deleted and <code>failOnError</code> is <code>true</code>.
     */
    private int delete(File file, boolean failOnError, boolean retryOnError) throws IOException {
        if (!file.delete()) {
            boolean deleted = false;

            if (retryOnError) {
                if (ON_WINDOWS) {
                    // try to release any locks held by non-closed files
                    System.gc();
                }

                final int[] delays = {50, 250, 750};
                for (int i = 0; !deleted && i < delays.length; i++) {
                    try {
                        Thread.sleep(delays[i]);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    deleted = file.delete() || !file.exists();
                }
            } else {
                deleted = !file.exists();
            }

            if (!deleted) {
                if (failOnError) {
                    throw new IOException("Failed to delete " + file);
                } else {
                    if (logWarn != null) {
                        logWarn.log("Failed to delete " + file);
                    }
                    return 1;
                }
            }
        }

        return 0;
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
    }

    private static class BackgroundCleaner extends Thread {

        private static BackgroundCleaner instance;

        private final Deque<File> filesToDelete = new ArrayDeque<>();

        private final Cleaner cleaner;

        private final String fastMode;

        private static final int NEW = 0;
        private static final int RUNNING = 1;
        private static final int STOPPED = 2;

        private int status = NEW;

        public static void delete(Cleaner cleaner, File dir, String fastMode) {
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

        private BackgroundCleaner(Cleaner cleaner, File dir, String fastMode) {
            super("mvn-background-cleaner");
            this.cleaner = cleaner;
            this.fastMode = fastMode;
            init(cleaner.fastDir, dir);
        }

        public void run() {
            while (true) {
                File basedir = pollNext();
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

        synchronized void init(File fastDir, File dir) {
            if (fastDir.isDirectory()) {
                File[] children = fastDir.listFiles();
                if (children != null && children.length > 0) {
                    for (File child : children) {
                        doDelete(child);
                    }
                }
            }
            doDelete(dir);
        }

        synchronized File pollNext() {
            File basedir = filesToDelete.poll();
            if (basedir == null) {
                if (cleaner.session != null) {
                    SessionData data = cleaner.session.getRepositorySession().getData();
                    File lastDir = (File) data.get(LAST_DIRECTORY_TO_DELETE);
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

        synchronized boolean doDelete(File dir) {
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
