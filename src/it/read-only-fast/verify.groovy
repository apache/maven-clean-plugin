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

if (new File(basedir, "target").exists()) {
    System.out.println("FAILURE: 'target' has not been deleted.");
    return false;
}
if (new File(basedir, ".fastdir").exists()) {
    System.out.println("FAILURE: '.fastdir' has not been deleted (read-only files left behind).");
    return false;
}

File buildLog = new File(basedir, 'build.log')
// With failOnError=true (the default), deletion runs synchronously — the
// background cleaner thread name won't appear.  Verify that the fast-delete
// path was used by checking for the staging-directory debug message.
return buildLog.text.contains('Deleting') && buildLog.text.contains('in background')
