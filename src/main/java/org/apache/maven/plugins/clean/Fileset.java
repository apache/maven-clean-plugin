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

import java.nio.file.Path;

/**
 * Customizes the string representation of
 * {@code org.apache.maven.shared.model.fileset.FileSet} to return the
 * included and excluded files from the file-set's directory. Specifically,
 * <code>"file-set: <I>[directory]</I> (included: <I>[included files]</I>,
 * excluded: <I>[excluded files]</I>)"</code>
 *
 * @since 2.1
 */
public class Fileset {

    private Path directory;

    private String[] includes;

    private String[] excludes;

    private boolean followSymlinks;

    private boolean useDefaultExcludes;

    /**
     * Creates an initially empty file set.
     */
    public Fileset() {}

    /**
     * {@return the base directory}.
     */
    public Path getDirectory() {
        return directory;
    }

    /**
     * {@return the patterns of the file to include, or an empty array if unspecified}.
     */
    public String[] getIncludes() {
        return (includes != null) ? includes : new String[0];
    }

    /**
     * {@return the patterns of the file to exclude, or an empty array if unspecified}.
     */
    public String[] getExcludes() {
        return (excludes != null) ? excludes : new String[0];
    }

    /**
     * {@return whether the base directory is excluded from the fileset}.
     * This is {@code false} by default. The base directory can be excluded
     * explicitly if the exclude patterns contains an empty string.
     */
    public boolean isBaseDirectoryExcluded() {
        if (excludes != null) {
            for (String pattern : excludes) {
                if (pattern == null || pattern.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return whether to follow symbolic links}.
     */
    public boolean isFollowSymlinks() {
        return followSymlinks;
    }

    /**
     * {@return whether to use a default set of excludes}.
     */
    public boolean isUseDefaultExcludes() {
        return useDefaultExcludes;
    }

    /**
     * Appends the elements of the given array in the given buffer.
     * This is a helper method for {@link #toString()} implementations.
     *
     * @param buffer the buffer to add the elements to
     * @param label label identifying the array of elements to add
     * @param patterns the elements to append, or {@code null} if none
     */
    static void append(StringBuilder buffer, String label, String[] patterns) {
        buffer.append(label).append(": [");
        if (patterns != null) {
            for (int i = 0; i < patterns.length; i++) {
                if (i != 0) {
                    buffer.append(", ");
                }
                buffer.append(patterns[i]);
            }
        }
        buffer.append(']');
    }

    /**
     * {@return a string representation of the included and excluded files from this file-set's directory}.
     * Specifically, <code>"file-set: <I>[directory]</I> (included:
     * <I>[included files]</I>, excluded: <I>[excluded files]</I>)"</code>
     */
    @Override
    public String toString() {
        var buffer = new StringBuilder("file set: ").append(getDirectory());
        append(buffer.append(" ("), "included", getIncludes());
        append(buffer.append(", "), "excluded", getExcludes());
        return buffer.append(')').toString();
    }
}
