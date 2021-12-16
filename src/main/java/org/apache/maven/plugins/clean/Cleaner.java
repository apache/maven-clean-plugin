package org.apache.maven.plugins.clean;

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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.utils.Os;
import org.apache.maven.shared.utils.io.FileUtils;

/**
 * Cleans directories.
 * 
 * @author Benjamin Bentmann
 */
class Cleaner
{

    private static final boolean ON_WINDOWS = Os.isFamily( Os.FAMILY_WINDOWS );

    private static final String LAST_DIRECTORY_TO_DELETE = Cleaner.class.getName() + ".lastDirectoryToDelete";

    private final MavenSession session;

    private final Logger logDebug;

    private final Logger logInfo;

    private final Logger logVerbose;

    private final Logger logWarn;

    private final File fastDir;

    /**
     * Creates a new cleaner.
     * 
     * @param log The logger to use, may be <code>null</code> to disable logging.
     * @param verbose Whether to perform verbose logging.
     */
    Cleaner( MavenSession session, final Log log, boolean verbose, File fastDir )
    {
        this.session = session;
        logDebug = ( log == null || !log.isDebugEnabled() ) ? null : new Logger()
        {
            public void log( CharSequence message )
            {
                log.debug( message );
            }
        };

        logInfo = ( log == null || !log.isInfoEnabled() ) ? null : new Logger()
        {
            public void log( CharSequence message )
            {
                log.info( message );
            }
        };

        logWarn = ( log == null || !log.isWarnEnabled() ) ? null : new Logger()
        {
            public void log( CharSequence message )
            {
                log.warn( message );
            }
        };

        logVerbose = verbose ? logInfo : logDebug;

        this.fastDir = fastDir;
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
    public void delete( File basedir, Selector selector, boolean followSymlinks, boolean failOnError,
                        boolean retryOnError )
        throws IOException
    {
        if ( !basedir.isDirectory() )
        {
            if ( !basedir.exists() )
            {
                if ( logDebug != null )
                {
                    logDebug.log( "Skipping non-existing directory " + basedir );
                }
                return;
            }
            throw new IOException( "Invalid base directory " + basedir );
        }

        if ( logInfo != null )
        {
            logInfo.log( "Deleting " + basedir + ( selector != null ? " (" + selector + ")" : "" ) );
        }

        File file = followSymlinks ? basedir : basedir.getCanonicalFile();

        if ( selector == null && !followSymlinks && fastDir != null )
        {
            // If anything wrong happens, we'll just use the usual deletion mechanism
            if ( fastDelete( file ) )
            {
                return;
            }
        }

        delete( file, "", selector, followSymlinks, failOnError, retryOnError );
    }

    private boolean fastDelete( File baseDir )
    {
        // Handle the case where we use ${maven.multiModuleProjectDirectory}/target/.clean for example
        if ( fastDir.getAbsolutePath().startsWith( baseDir.getAbsolutePath() ) )
        {
            try
            {
                File tmpDir = createTempDir( baseDir.getParentFile(), baseDir.getName() + "." );
                try
                {
                    Files.move( baseDir.toPath(), tmpDir.toPath(), StandardCopyOption.ATOMIC_MOVE );
                    session.getRequest().getData().put( LAST_DIRECTORY_TO_DELETE, baseDir );
                    baseDir = tmpDir;
                }
                catch ( IOException e )
                {
                    tmpDir.delete();
                    if ( logDebug != null )
                    {
                        logDebug.log( "Unable to fast delete directory: " + e );
                    }
                    return false;
                }
            }
            catch ( IOException e )
            {
                if ( logDebug != null )
                {
                    logDebug.log( "Unable to fast delete directory: " + e );
                }
                return false;
            }
        }
        // Create fastDir and the needed parents if needed
        if ( fastDir.mkdirs() || fastDir.isDirectory() )
        {
            try
            {
                File tmpDir = createTempDir( fastDir, "" );
                File dstDir = new File( tmpDir, baseDir.getName() );
                // Note that by specifying the ATOMIC_MOVE, we expect an exception to be thrown
                // if the path leads to a directory on another mountpoint.  If this is the case
                // or any other exception occurs, an exception will be thrown in which case
                // the method will return false and the usual deletion will be performed.
                Files.move( baseDir.toPath(), dstDir.toPath(), StandardCopyOption.ATOMIC_MOVE );
                BackgroundCleaner.delete( this, tmpDir );
                return true;
            }
            catch ( IOException e )
            {
                if ( logDebug != null )
                {
                    logDebug.log( "Unable to fast delete directory: " + e );
                }
            }
        }
        else
        {
            if ( logDebug != null )
            {
                logDebug.log( "Unable to fast delete directory as the path "
                        + fastDir + " does not point to a directory or can not be created" );
            }
        }
        return false;
    }

    private File createTempDir( File baseDir, String prefix ) throws IOException
    {
        for ( int i = 0; i < 10; i++ )
        {
            int rnd = ThreadLocalRandom.current().nextInt();
            File tmpDir = new File( baseDir, prefix + Integer.toHexString( rnd ) );
            if ( tmpDir.mkdir() )
            {
                return tmpDir;
            }
        }
        throw new IOException( "Can not create temp directory" );
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
    private Result delete( File file, String pathname, Selector selector, boolean followSymlinks, boolean failOnError,
                           boolean retryOnError )
        throws IOException
    {
        Result result = new Result();

        boolean isDirectory = file.isDirectory();

        if ( isDirectory )
        {
            if ( selector == null || selector.couldHoldSelected( pathname ) )
            {
                final boolean isSymlink = FileUtils.isSymbolicLink( file );
                File canonical = followSymlinks ? file : file.getCanonicalFile();
                if ( followSymlinks || !isSymlink )
                {
                    String[] filenames = canonical.list();
                    if ( filenames != null )
                    {
                        String prefix = pathname.length() > 0 ? pathname + File.separatorChar : "";
                        for ( int i = filenames.length - 1; i >= 0; i-- )
                        {
                            String filename = filenames[i];
                            File child = new File( canonical, filename );
                            result.update( delete( child, prefix + filename, selector, followSymlinks, failOnError,
                                                   retryOnError ) );
                        }
                    }
                }
                else if ( logDebug != null )
                {
                    logDebug.log( "Not recursing into symlink " + file );
                }
            }
            else if ( logDebug != null )
            {
                logDebug.log( "Not recursing into directory without included files " + file );
            }
        }

        if ( !result.excluded && ( selector == null || selector.isSelected( pathname ) ) )
        {
            if ( logVerbose != null )
            {
                if ( isDirectory )
                {
                    logVerbose.log( "Deleting directory " + file );
                }
                else if ( file.exists() )
                {
                    logVerbose.log( "Deleting file " + file );
                }
                else
                {
                    logVerbose.log( "Deleting dangling symlink " + file );
                }
            }
            result.failures += delete( file, failOnError, retryOnError );
        }
        else
        {
            result.excluded = true;
        }

        return result;
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
    private int delete( File file, boolean failOnError, boolean retryOnError )
        throws IOException
    {
        if ( !file.delete() )
        {
            boolean deleted = false;

            if ( retryOnError )
            {
                if ( ON_WINDOWS )
                {
                    // try to release any locks held by non-closed files
                    System.gc();
                }

                final int[] delays = { 50, 250, 750 };
                for ( int i = 0; !deleted && i < delays.length; i++ )
                {
                    try
                    {
                        Thread.sleep( delays[i] );
                    }
                    catch ( InterruptedException e )
                    {
                        // ignore
                    }
                    deleted = file.delete() || !file.exists();
                }
            }
            else
            {
                deleted = !file.exists();
            }

            if ( !deleted )
            {
                if ( failOnError )
                {
                    throw new IOException( "Failed to delete " + file );
                }
                else
                {
                    if ( logWarn != null )
                    {
                        logWarn.log( "Failed to delete " + file );
                    }
                    return 1;
                }
            }
        }

        return 0;
    }

    private static class Result
    {

        private int failures;

        private boolean excluded;

        public void update( Result result )
        {
            failures += result.failures;
            excluded |= result.excluded;
        }

    }

    private interface Logger
    {

        void log( CharSequence message );

    }

    static class BackgroundCleaner extends Thread
    {

        private static BackgroundCleaner instance;

        private final Deque<File> filesToDelete = new ArrayDeque<>();

        private final Cleaner cleaner;

        private static final int NEW = 0;
        private static final int RUNNING = 1;
        private static final int STOPPED = 2;

        private int status = NEW;

        public static void delete( Cleaner cleaner, File dir )
        {
            synchronized ( BackgroundCleaner.class )
            {
                if ( instance == null || !instance.doDelete( dir ) )
                {
                    instance = new BackgroundCleaner( cleaner, dir );
                }
            }
        }

        static void sessionEnd()
        {
            synchronized ( BackgroundCleaner.class )
            {
                if ( instance != null )
                {
                    instance.doSessionEnd();
                }
            }
        }

        private BackgroundCleaner( Cleaner cleaner, File dir )
        {
            this.cleaner = cleaner;
            init( cleaner.fastDir, dir );
        }

        public void run()
        {
            while ( true )
            {
                File basedir = pollNext();
                if ( basedir == null )
                {
                    break;
                }
                try
                {
                    cleaner.delete( basedir, "", null, false, false, true );
                }
                catch ( IOException e )
                {
                    // do not display errors
                }
            }
        }

        synchronized void init( File fastDir, File dir )
        {
            if ( fastDir.isDirectory() )
            {
                File[] children = fastDir.listFiles();
                if ( children != null && children.length > 0 )
                {
                    for ( File child : children )
                    {
                        doDelete( child );
                    }
                }
            }
            doDelete( dir );
        }

        synchronized File pollNext()
        {
            File basedir = filesToDelete.poll();
            if ( basedir == null )
            {
                File lastFolder = ( File ) cleaner.session.getRequest().getData().get( LAST_DIRECTORY_TO_DELETE );
                if ( lastFolder != null )
                {
                    cleaner.session.getRequest().getData().remove( LAST_DIRECTORY_TO_DELETE );
                    return lastFolder;
                }
                else
                {
                    status = STOPPED;
                    notifyAll();
                }
            }
            return basedir;
        }

        synchronized boolean doDelete( File dir )
        {
            if ( status == STOPPED )
            {
                return false;
            }
            filesToDelete.add( dir );
            if ( status == NEW )
            {
                status = RUNNING;
                notifyAll();
                List<EventSpy> spies = cleaner.session.getRequest().getEventSpyDispatcher().getEventSpies();
                boolean hasSessionListener = false;
                for ( EventSpy spy : spies )
                {
                    if ( spy instanceof SessionListener )
                    {
                        hasSessionListener = true;
                        break;
                    }
                }
                if ( !hasSessionListener )
                {
                    spies.add( new SessionListener() );
                }
                start();
            }
            return true;
        }

        synchronized void doSessionEnd()
        {
            if ( status != STOPPED )
            {
                try
                {
                    cleaner.logInfo.log( "Waiting for background file deletion" );
                    while ( status != STOPPED )
                    {
                        wait();
                    }
                }
                catch ( InterruptedException e )
                {
                    // ignore
                }
            }
        }

    }

    static class SessionListener extends AbstractEventSpy
    {
        @Override
        public void onEvent( Object event )
        {
            if ( event instanceof ExecutionEvent )
            {
                ExecutionEvent ee = ( ExecutionEvent ) event;
                if ( ee.getType() == ExecutionEvent.Type.SessionEnded )
                {

                    BackgroundCleaner.sessionEnd();
                }
            }
        }
    }

}
