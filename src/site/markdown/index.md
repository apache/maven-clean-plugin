---
title: Introduction
author: 
  - Edwin Punzalan
date: 2013-07-22
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

# Apache Maven Clean Plugin

The Clean Plugin removes the files that Maven generates in the directory of a project during a build.

## Goals Overview

The Clean Plugin has one goal.

- [clean:clean](./clean-mojo.html) removes the files that Maven generates in the working directory of a project. By default, it finds and deletes the directories in `project.build.directory`, `project.build.outputDirectory`, `project.build.testOutputDirectory`, and `project.reporting.outputDirectory`.

## Usage

General instructions for using the Clean Plugin can be found on the [usage page](./usage.html). Some more specific use cases are described in the examples given below.

If you have more questions about the plugin, see the [FAQ](./faq.html) and feel free to contact the [user mailing list](./mailing-lists.html). The posts to the mailing list are archived and could already contain the answer to your question as part of an older thread. Hence, it is also worth browsing/searching the [mail archive](./mailing-lists.html).

If you think the plugin is missing a feature or has a defect, submit a feature request or bug report in our [issue tracker](./issue-management.html). When creating a new issue, give a clear description of your concern. Especially for fixing bugs, the developers must reproduce your problem. For this reason, entire debug logs, POMs, or preferably small demo projects attached to the issue are very much appreciated. Patches are also welcome. Contributors can check out the project from our [source repository](./scm.html) and find more information in the [guide to helping with Maven](https://maven.apache.org/guides/development/guide-helping.html).

## Examples

To better understand some uses of the Clean Plugin, see the following examples:

- [How to delete additional files outside of the default directories?](./examples/delete_additional_files.html)
- [How to ignore clean errors?](./examples/ignoring-errors.html)
- [How to skip clean execution?](./examples/skipping-clean.html)
