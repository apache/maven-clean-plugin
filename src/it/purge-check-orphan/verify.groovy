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
//   - sub-orphan/target/ must have been removed (only non-hidden child was target/)
//   - sub-existing/target/ must remain (pom.xml + src/ are siblings of target/)

def log = new File(basedir, 'build.log').text
assert log.contains('BUILD SUCCESS') : 'expected BUILD SUCCESS'

// Orphaned build directory must be gone
def orphanTarget = new File(basedir, 'sub-orphan/target')
assert !orphanTarget.exists() : "sub-orphan/target/ should have been removed by purge-check, but still exists"

// Active sub-project's build directory must be untouched
// (purge-check runs on initialize; clean:clean runs later and removes it — so we only
//  check the log to confirm purge-check did NOT report it as orphaned)
assert !log.contains('sub-existing') || log.contains('Removing orphaned') == false \
    || !log.find('Removing orphaned.*sub-existing') \
    : "sub-existing/target/ must not be treated as orphaned"

assert log.contains('Removing orphaned build directory') : 'expected purge-check to report removal'
