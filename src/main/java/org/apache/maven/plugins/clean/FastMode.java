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

import java.util.Locale;

/**
 * Specifies when to delete files when fast clean is used.
 * This enumeration defines the value values for {@code maven.clean.fastMode},
 * in upper case and with {@code '-'} replaced by {@code '_'}.
 */
enum FastMode {
    /**
     * Start deletion immediately and wait for all files to be deleted when the session ends.
     * This is the default mode when the deletion of files in a background thread is enabled.
     */
    BACKGROUND,

    /**
     * Actual deletion should be performed synchronously when the session ends.
     * The plugin waits for all files to be deleted when the session end.
     */
    AT_END,

    /**
     * Actual file deletion should be started in the background when the session ends.
     * The plugin does not wait for files to be deleted.
     */
    DEFER;

    /**
     * {@return the enumeration value for the given configuration option}
     *
     * @param  option  the configuration option, case insensitive
     * @throws IllegalArgumentException if the given option is invalid
     */
    public static FastMode caseInsensitiveValueOf(String option) {
        try {
            return valueOf(option.trim().toUpperCase(Locale.US).replace('-', '_'));
        } catch (NullPointerException | IllegalArgumentException e) {
            StringBuilder sb =
                    new StringBuilder("Illegal value '").append(option).append("' for fastMode. Allowed values are '");
            FastMode[] values = values();
            int last = values.length;
            for (int i = 0; i <= last; i++) {
                sb.append(values[i]).append(i < last ? "', '" : "' and '");
            }
            throw new IllegalArgumentException(sb.append("'.").toString(), e);
        }
    }

    /**
     * {@return the lower-case variant of this enumeration value}
     */
    @Override
    public String toString() {
        return name().replace('_', '-').toLowerCase(Locale.US);
    }
}
