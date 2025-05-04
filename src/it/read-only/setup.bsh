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

if (!new File(basedir, "target/read-only-dir/read-only.properties").setWritable(false)) {
    System.out.println("Cannot change file permission.");
    return false;
}
if (File.separatorChar == '/') {
    // Directory permission can be changed only on Unix, not on Windows.
    if (!new File(basedir, "target/read-only-dir").setWritable(false)) {
        System.out.println("Cannot change directory permission.");
        return false;
    }
}
if (!new File(basedir, "target/writable-dir/writable.properties").canWrite()) {
    System.out.println("Expected a writable file.");
    return false;
}
return true;
