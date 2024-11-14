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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.file.Files.createFile;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeSelectorTest {

    @Test
    void compositionOfNone() {
        assertNull(CompositeSelector.compose());
    }

    @Test
    void compositionOfOneIgnoringNulls() {
        Selector composite = mock();
        assertSame(composite, CompositeSelector.compose(composite, null));
    }

    @Test
    void compositionOfTwoLogicalAnd(@TempDir Path tempDir) throws Exception {
        final Path file = createFile(tempDir.resolve("file"));
        final Selector delegate1 = mock();
        when(delegate1.couldHoldSelected(eq(tempDir), contains("1"))).thenReturn(true);
        when(delegate1.isSelected(eq(file), contains("1"))).thenReturn(true);
        final Selector delegate2 = mock();
        when(delegate2.couldHoldSelected(eq(tempDir), contains("2"))).thenReturn(true);
        when(delegate2.isSelected(eq(file), contains("2"))).thenReturn(true);

        final CompositeSelector selector =
                assertInstanceOf(CompositeSelector.class, CompositeSelector.compose(delegate1, delegate2));

        assertTrue(selector.couldHoldSelected(tempDir, "1+2"));
        assertFalse(selector.couldHoldSelected(tempDir, "1"));
        assertFalse(selector.couldHoldSelected(tempDir, "2"));
        assertTrue(selector.isSelected(file, "1+2"));
        assertFalse(selector.isSelected(file, "1"));
        assertFalse(selector.isSelected(file, "2"));
    }
}
