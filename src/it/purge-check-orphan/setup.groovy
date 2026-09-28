// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

// Setup: create the orphaned sub-project structure that simulates a module removed
// from the reactor after a git operation.  The directory has only a target/ subdirectory
// (no pom.xml, no src/) — exactly what is left when a sub-project is deleted from git.

def orphan = new File(basedir, 'sub-orphan')
def orphanTarget = new File(orphan, 'target/classes')
orphanTarget.mkdirs()
new File(orphanTarget, 'Foo.class').bytes = new byte[0]

// Also create a target/ inside sub-existing to make sure it is NOT removed.
def existingTarget = new File(basedir, 'sub-existing/target/classes')
existingTarget.mkdirs()
new File(existingTarget, 'Bar.class').bytes = new byte[0]

return true
