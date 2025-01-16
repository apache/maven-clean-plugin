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

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

String[] expected = {
    "ext",
    "ext/file.txt",
    "ext/dir/file.txt",
};

for ( String path : expected )
{
    File file = new File( basedir, path );
    System.out.println( "Checking for existence of " + file );
    if ( !file.exists() )
    {
        System.out.println( "FAILURE!" );
        return false;
    }
}

String[] unexpected = {
    "target/link.txt",
    "target/link",
    "target",
    "target2/link.txt",
    "target2/link",
    "target2",
};

for ( String path : unexpected )
{
    File file = new File( basedir, path );
    System.out.println( "Checking for absence of " + file );
    if ( file.exists() )
    {
        System.out.println( "FAILURE!" );
        return false;
    }
}
