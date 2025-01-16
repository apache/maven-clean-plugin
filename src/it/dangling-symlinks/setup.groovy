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
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import org.apache.maven.plugins.clean.*;

try
{
    File targetDir = new File( basedir, "target" );
    File link = new File( targetDir, "link" );
    File target = new File( targetDir, "link-target.txt" );

    System.out.println( "Creating symlink " + link + " -> " + target );
    Files.createSymbolicLink( link.toPath(), target.toPath() );
    if ( !link.exists() )
    {
        System.out.println( "Platform does not support symlinks, skipping test." );
    }

    System.out.println( "Deleting symlink target " + target );
    target.delete();
}
catch( Exception ex )
{
    ex.printStackTrace();
    return false;
}

return true;
