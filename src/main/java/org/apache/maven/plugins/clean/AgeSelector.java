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
import java.nio.file.Path;
import java.time.Instant;

import static java.nio.file.Files.getLastModifiedTime;

/**
 * Selects paths based on age, whether they have a modification time before a given time.
 * This can be used to select files which were the result of any <i>previous</i> build versus files which are the result
 * of the <i>current</i> build.
 *
 * @since 2.5
 */
class AgeSelector implements Selector {

    private final Instant before;

    AgeSelector(Instant before) {
        this.before = before;
    }

    @Override
    public boolean isSelected(Path file, String pathname) throws IOException {
        return getLastModifiedTime(file).toInstant().isBefore(before);
    }

    @Override
    public boolean couldHoldSelected(Path dir, String pathname) {
        return true;
    }
}
