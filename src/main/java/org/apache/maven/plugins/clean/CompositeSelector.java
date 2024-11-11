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
import java.util.Objects;

import static java.util.Arrays.stream;

/**
 * Composed multiple selectors in a logical AND fashion.
 * Only if all the composed selectors say "yes", then the result is "yes".
 * If any of the composed selectors says "no", then the result is "no".
 *
 * @since 2.5
 */
class CompositeSelector implements Selector {

    private final Selector[] delegates;

    private CompositeSelector(Selector... delegates) {
        this.delegates = delegates;
    }

    @Override
    public boolean isSelected(Path file, String pathname) throws IOException {
        for (Selector delegate : delegates) {
            if (!delegate.isSelected(file, pathname)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean couldHoldSelected(Path dir, String pathname) {
        for (Selector delegate : delegates) {
            if (!delegate.couldHoldSelected(dir, pathname)) {
                return false;
            }
        }
        return true;
    }

    static Selector compose(Selector... delegates) {
        Selector[] nonNullDelegates = stream(delegates).filter(Objects::nonNull).toArray(Selector[]::new);
        if (nonNullDelegates.length == 0) {
            return null; // Shortcut: no delegate = the null selector.
        } else if (nonNullDelegates.length == 1) {
            return nonNullDelegates[0]; // Shortcut: one delegate = the delegate itself.
        }
        return new CompositeSelector(nonNullDelegates);
    }
}
