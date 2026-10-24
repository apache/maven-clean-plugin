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

1. [I already ran *mvn clean* but the directory (*put dir name here*) is still there. What can I do?](#I_already_ran_mvn_clean_but_the_directory_.28put_dir_name_here.29_is_still_there._What_should_I_do.3F)
2. [On Windows, I got *&quot;Unable to delete directory&quot;*. What is wrong?](#On_Windows.2C_I_got_Unable_to_delete_directory._What_s_wrong.3F)

<a id="I_already_ran_mvn_clean_but_the_directory_.28put_dir_name_here.29_is_still_there._What_should_I_do.3F"></a>

### I already ran *mvn clean* but the directory (*put dir name here*) is still there. What can I do?

Some plugins generate their files outside the default directories that the Clean Plugin deletes. Add the location of these files to the configuration of the Clean Plugin. You can also change the configuration of those plugins. Then those plugins put their files in *project.build.directory*, which is the *target* directory by default.

<a id="On_Windows.2C_I_got_Unable_to_delete_directory._What_s_wrong.3F"></a>

### On Windows, I got *&quot;Unable to delete directory&quot;*. What is wrong?

For example, *clean* can fail if you opened a command line with *target* as the current directory. Windows locks some resources. You must close the handles on these resources. To skip these errors, call *clean* with the command-line parameter *-Dmaven.clean.failOnError=false*. For more information, see the [Ignore Errors](./examples/ignoring-errors.html) page.

[Sysinternals](http://www.microsoft.com/technet/sysinternals/default.mspx) has several utilities, for example [Process Explorer](http://www.microsoft.com/technet/sysinternals/Utilities/ProcessExplorer.mspx) and [Handle](http://www.microsoft.com/technet/sysinternals/utilities/handle.mspx), that can help you to manage Windows handles.
