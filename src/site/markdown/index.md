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

The [usage page](./usage.html) has general instructions for the Clean Plugin.

If you have more questions about the plugin, see the [FAQ](./faq.html). You can also contact the [user mailing list](./mailing-lists.html). The project archives the posts to the mailing list, and an older thread can contain the answer to your question. You can also search the [mail archive](./mailing-lists.html).

If you think that the plugin misses a feature or has a defect, submit a feature request or a bug report in our [issue tracker](./issue-management.html). If you open a new issue, give a clear description of the problem. The developers must reproduce the problem to correct a bug. Thus, attach the full debug log and the POM to the issue. A small demo project is best.

Patches are also welcome. Contributors can get the project from the [source repository](./scm.html). The [guide to helping with Maven](https://maven.apache.org/guides/development/guide-helping.html) has more information.

## Examples

For more uses of the Clean Plugin, see these examples:

- [How to delete additional files outside of the default directories?](./examples/delete_additional_files.html)
- [How to ignore clean errors?](./examples/ignoring-errors.html)
- [How to skip clean execution?](./examples/skipping-clean.html)
