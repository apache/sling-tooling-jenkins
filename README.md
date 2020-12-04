[![Apache Sling](https://sling.apache.org/res/logos/sling.png)](https://sling.apache.org)

 [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Sling Jenkins Shared Library


The master branch of this module is pulled directly from the Jenkins
builds and used as [Jenkins Shared Library](https://www.jenkins.io/doc/book/pipeline/shared-libraries/) with name `sling`, no deployment step is required to activate changes as the `master` branch is loaded implicitly.

See <https://cwiki.apache.org/confluence/display/SLING/Sling+Jenkins+Setup>
for more info.

## Test Changes

As changes to master are active immediately on all jobs, it is recommended to test any changes in the Jenkins Shared Library by pushing them to a dedicated branch (in this example named `mybranch`) and then reference that branch from within a branch of any other project, by using a `Jenkinsfile` explicitly via `@Library` as follows

```
/**
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

@Library('sling@mybranch') _
slingOsgiBundleBuild()

```

When the branch job related to the modified `Jenkinsfile` is built, it will use the library from `mybranch` instead of `master`. Other jobs are not affected.