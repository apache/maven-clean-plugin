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

// Verify purge-check behaviour:
//   - sub-orphan/ must have been removed entirely (only non-hidden child was target/)
//   - sub-existing/ must remain (pom.xml + src/ are siblings of target/)

def log = new File(basedir, 'build.log').text
assert log.contains('BUILD SUCCESS') : 'expected BUILD SUCCESS'

// Orphaned directory must be gone entirely — not just its target/ subdirectory
def orphanDir = new File(basedir, 'sub-orphan')
assert !orphanDir.exists() : "sub-orphan/ should have been removed entirely by purge-check, but still exists"

// Active sub-project's directory must be untouched
// (purge-check runs on initialize; clean:clean runs later and removes it — so we only
//  check the log to confirm purge-check did NOT report it as orphaned)
assert !log.find('Removing orphaned.*sub-existing') \
    : "sub-existing/ must not be treated as orphaned"

assert log.contains('Removing orphaned directory') : 'expected purge-check to report removal'
