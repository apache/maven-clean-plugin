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

// Verify that clean succeeds in a multi-module project where one module depends on
// another without attempting dependency resolution (MCLEAN-104).
// This regression was introduced in Maven 4.0.0-beta-1 and fixed in apache/maven#2124.
// The clean lifecycle should not resolve dependencies, so module1 need not be installed.

def log = new File( basedir, 'build.log' ).text

assert log.contains( 'BUILD SUCCESS' ) : 'expected BUILD SUCCESS'
assert !log.contains( 'Could not resolve dependencies' ) : 'clean should not attempt dependency resolution'
assert !log.contains( 'DependencyResolutionException' ) : 'clean should not attempt dependency resolution'
