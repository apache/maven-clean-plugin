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
import java.util.ArrayList;
import java.util.List;

/**
 * Customizes the string representation of
 * {@code org.apache.maven.api.model.FileSet} to return the
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
     * {@return the patterns of the file to include, or an empty list if unspecified}.
     */
    public List<String> getIncludes() {
        return listWithoutNull(includes);
    }

    /**
     * {@return the patterns of the file to exclude, or an empty list if unspecified}.
     */
    public List<String> getExcludes() {
        return listWithoutNull(excludes);
    }

    /**
     * {@return the content of the given array without null elements}.
     * The existence of null elements has been observed in practice,
     * not sure where they come from.
     *
     * @param patterns the {@link #includes} or {@link #excludes} array, or {@code null} if none
     */
    private static List<String> listWithoutNull(String[] patterns) {
        if (patterns == null) {
            return List.of();
        }
        var list = new ArrayList<String>(patterns.length);
        for (String pattern : patterns) {
            if (pattern != null) {
                list.add(pattern);
            }
        }
        return list;
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
    public boolean followSymlinks() {
        return followSymlinks;
    }

    /**
     * {@return whether to use a default set of excludes}.
     */
    public boolean useDefaultExcludes() {
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
    private static void append(StringBuilder buffer, String label, List<String> patterns) {
        buffer.append(label).append(": [");
        String separator = "";
        for (String pattern : patterns) {
            buffer.append(separator).append(pattern);
            separator = ", ";
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
