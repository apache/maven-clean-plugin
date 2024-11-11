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
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.file.Files.createFile;
import static java.nio.file.Files.getLastModifiedTime;
import static java.nio.file.Files.setLastModifiedTime;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgeSelectorTest {

    @Test
    void selectionBasedOnModificationTimeBefore(@TempDir Path tempDir) throws Exception {
        final Path presentFile = createFile(tempDir.resolve("present"));
        final Path pastFile = createFile(tempDir.resolve("past"));
        final Path futureFile = createFile(tempDir.resolve("future"));
        final Instant present = getLastModifiedTime(presentFile).toInstant();
        final Instant past = present.minusSeconds(1L);
        setLastModifiedTime(pastFile, FileTime.from(past));
        final Instant future = present.plusSeconds(1L);
        setLastModifiedTime(futureFile, FileTime.from(future));

        final AgeSelector ageSelector = new AgeSelector(present);

        assertTrue(ageSelector.couldHoldSelected(tempDir, ""));
        assertTrue(ageSelector.isSelected(pastFile, ""));
        assertFalse(ageSelector.isSelected(presentFile, ""));
        assertFalse(ageSelector.isSelected(futureFile, ""));
    }
}
