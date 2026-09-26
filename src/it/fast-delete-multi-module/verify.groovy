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

// Verify multi-module fast clean with session-scoped BackgroundCleaner:
// - Both modules' target directories should be cleaned
// - The shared staging directory (.fastdir) should be cleaned up
// - The background cleaner thread should have run (session-scoped sharing)

def log = new File( basedir, 'build.log' ).text

// Both modules must have their target directories cleaned
assert !new File( basedir, 'module-force/target' ).exists() : 'module-force/target should have been deleted'
assert !new File( basedir, 'module-noforce/target' ).exists() : 'module-noforce/target should have been deleted'

// The shared staging directory should be cleaned after the session ends
assert !new File( basedir, '.fastdir' ).exists() : '.fastdir staging directory should have been deleted'

// The background cleaner thread must have been used (proves session-scoped sharing)
assert log.contains( 'mvn-background-cleaner' ) : 'expected background cleaner thread name in log'

// Build must succeed
assert log.contains( 'BUILD SUCCESS' ) : 'expected BUILD SUCCESS'
