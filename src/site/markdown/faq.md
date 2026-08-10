---
title: Frequently Asked Questions
---

<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

<a id="top"></a>

# Frequently Asked Questions

1. [I already ran *mvn clean* but the directory (*put dir name here*) is still there. What should I do?](#I_already_ran_mvn_clean_but_the_directory_.28put_dir_name_here.29_is_still_there._What_should_I_do.3F)
2. [On Windows, I got *&quot;Unable to delete directory&quot;*. What's wrong?](#On_Windows.2C_I_got_Unable_to_delete_directory._What_s_wrong.3F)

### I already ran *mvn clean* but the directory (*put dir name here*) is still there. What should I do?

Some files-generating plugins can generate their files outside of the
default directories being deleted by the clean plugin. You should add
the location of such files in the clean plugin configuration or change
the configuration of those plugins to put their files inside the
*project.build.directory* which is by default, the *target*
directory.

<a id="On_Windows.2C_I_got_Unable_to_delete_directory._What_s_wrong.3F"></a>

### On Windows, I got *&quot;Unable to delete directory&quot;*. What's wrong?

For instance, *clean* could fail if you already have opened a command
line with target as the current dir. Windows locks some ressources and you need
to close the handles on these ressources.
To skip these errors, you could call *clean* with the command line parameter *-Dmaven.clean.failOnError=false*.
For more information, refer to [Ignoring Errors](./examples/ignoring-errors.html) page.

[Sysinternals](http://www.microsoft.com/technet/sysinternals/default.mspx) produced
a number of utilities, like [Process Explorer](http://www.microsoft.com/technet/sysinternals/Utilities/ProcessExplorer.mspx)
or [Handle](http://www.microsoft.com/technet/sysinternals/utilities/handle.mspx)
that help you to deal with Windows handles.
