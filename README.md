[![Apache Sling](https://sling.apache.org/res/logos/sling.png)](https://sling.apache.org)

&#32;[![Sonarcloud Status](https://sonarcloud.io/api/project_badges/measure?project=apache_sling-tooling-jenkins&metric=alert_status)](https://sonarcloud.io/dashboard?id=apache_sling-tooling-jenkins) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

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

## Calling Apache LLMAO from Jenkins

`slingOsgiBundleBuild(llmaoOnly: true)` runs a separate Pi automation job, not the normal Maven matrix, SonarCloud analysis, deployment, or normal build notifications. Pi can run Maven as part of verifying its requested changes. Projects using `slingOsgiBundleBuild()` without this argument continue to build as before; they do not call LLMAO.

### A separate scheduled job

1. Create a **dedicated Pipeline job** for the project, for example `<project>-llmao`. This library does not create that job automatically.
2. Give it access to a Jenkins **Secret text** credential with ID `llmao-pat` containing an access token from [Apache LLMAO](https://llm.apache.org/keys). For an automated job, LLMAO's [FAQ](https://llm.apache.org/help/faq) describes requesting a script-specific key.
3. Configure **Pipeline script from SCM** pointing to the project's repository and intended branch. Use a separate script path such as `Jenkinsfile.llmao`, leaving the normal `Jenkinsfile` unchanged. A library-invocation example is available in [`examples/llmao/Jenkinsfile`](examples/llmao/Jenkinsfile).
4. Run **Build Now** once. That first run installs the cron trigger; later runs happen on the schedule or whenever you click **Build Now**.

The separate job has its own schedule, build history, result, and concurrency limit. It uses Jenkins' standard cron trigger, so no Parameterized Scheduler plugin is required. LLMAO cron is installed on standalone Pipeline jobs (where `BRANCH_NAME` is unset) or the `master` branch of a multibranch job; feature branches can be run manually. Schedule the intended branch only, and do not expose the credential to untrusted branches or pull requests.

For initial testing, use this as `Jenkinsfile.llmao`:

```groovy
@Library('sling@mybranch') _
slingOsgiBundleBuild(
    llmaoOnly: true,
    llmaoRebuildFrequency: '', // Manual only; switch to '@weekly' for scheduling.
    llmaoModel: 'gemma4-26b',
    llmaoInput: 'Update this project to the next parent pom verion and respond only with SUCCESS or FAILURE'
)
```

### LLMAO configuration

These settings can be provided as library arguments or under `jenkins` in `.sling-module.json`; arguments take precedence. Only `llmaoOnly: true` in the library invocation selects this mode. It cannot be enabled by the module descriptor.

| Setting | Default | Meaning |
| --- | --- | --- |
| `llmaoRebuildFrequency` | `@weekly` | Cron for the dedicated job. Use `''` for manual runs only. |
| `llmaoModel` | `gemma4-26b` | Model ID available to the PAT on the LLMAO [Models page](https://llm.apache.org/models). |
| `llmaoMaxTokens` | `4096` | Maximum output tokens per model response; must be a positive integer. |
| `llmaoInput` | Empty | Task prompt to send to the Pi agent. |
| `llmaoInputFile` | Empty | Workspace-relative UTF-8 prompt file to read instead of `llmaoInput`. |

Provide either an input string or an input file; the library has no default task. The library allocates its own node and checks out the project; `llmaoInputFile` must exist in that checked-out workspace. Input files are not deleted. The checkout must be clean before Pi starts, so existing changes are never silently discarded. Avoid exposing secrets to the agent, and check the Models page for who can see prompts and source files.

`llmaoMaxTokens` replaces Pi's default 16,384-token output allowance for this custom model. A smaller allowance may help a request fit within the remaining PAT quota, but does not reset or increase that quota or cap the total tokens used by a multi-step job.

The library runs [Pi](https://pi.dev) in Docker, configured for LLMAO's OpenAI-compatible endpoint at `https://llm.apache.org/v1`, with the selected model and temperature `0.0`. Pi can read, search, edit, and write project files and run shell commands. Automatic extensions, MCP, automatic project instructions, stored sessions, and compaction remain disabled.

A bundled tool-output logger is explicitly loaded. It writes shell commands (up to 2,000 characters), shell-result tails (up to 6,000 characters), and errors from other tools to stderr in the Jenkins console. It redacts the `LLMAO_PAT` value before truncation and does not log successful file reads, prompts, or reasoning. It does not alter tool results or the final SUCCESS/FAILURE response on stdout.

Pi retries transient provider/network errors, including HTTP 429 rate limits, up to 10 times per failed model turn at one-minute intervals. Retries stay in the same Pi session, preserving tool history and edits; provider-level retries remain disabled. This polls for quota recovery rather than parsing LLMAO's custom reset timestamp, and the existing job/process timeouts still apply.

Each run fetches `skills/` from the `master` branch of [Apache Sling Whiteboard](https://github.com/apache/sling-whiteboard/tree/master/skills), including references and scripts. All its skills are explicitly available to Pi, including `update-sling-parent-pom`. The fetched Git revision is logged. Skills live outside the project checkout and do not appear in the diff. The parent-POM skill currently targets the latest supported release, not simply the current version plus one.

Pi is instructed to respond only with `SUCCESS` after completing and verifying its update, or `FAILURE` otherwise, without staging, committing, pushing, switching branches, or altering Git metadata. The wrapper maps an exact `SUCCESS` and a successful Pi process to exit code `0`; `FAILURE`, empty/malformed answers, or execution errors return a nonzero exit code and fail the Jenkins job. The final response is kept separate from diff output, so strings in the diff cannot be mistaken for an agent result.

The resulting Git diff is printed to stdout in the Jenkins build log, including partial changes on failure and new, non-ignored files without staging them. Project edits remain in the job workspace for inspection; no commit, push, or automatic recovery is performed. Generated input/configuration files are cleaned up, and sessions are not archived.

### Docker runtime

The `ubuntu` agent needs Bash, Git, and access to a **local Docker daemon**. The daemon must see the project and temporary workspaces at the same absolute paths for bind mounts; containerized executors must share those paths with the Docker host. The job also requires the Jenkins Pipeline Utility Steps plugin (`writeJSON`/`readJSON`). No host Node.js, Java, Maven, Pi installation, or Jenkins Docker Pipeline plugin is required.

The library builds `sling-llmao-pi:1.1.0-maven21-99e61abc` from its [Dockerfile](resources/org/apache/sling/jenkins/llmao/Dockerfile), pinning the official Node 24 and Maven/Temurin 21 images by digest and Pi at version `1.1.0`. The image includes Java 21, Maven 3.9.16, an `mvn21` wrapper, Git, curl, Python, and ripgrep. Other JDK versions and release credentials are not included. Docker's layer cache avoids reinstalling tools on subsequent runs. An uncached build needs Docker Hub, npm, and Ubuntu package repositories; the running container needs LLMAO, GitHub, Maven repositories, and any documentation needed by the skill.

Image building happens before credential binding. The container receives `LLMAO_PAT` from the Jenkins environment, not as a token value in its command line or configuration files. It runs as the Jenkins user's UID/GID with a writable project mount and read-only `.git` and input mounts. Its private configuration, skills checkout, and Maven home/cache live in container-local `/tmp`; host credentials and the Docker socket are not mounted. The Pi process is bounded to 25 minutes within a 30-minute job timeout. Container removal is attempted on shell exit and again in the Pipeline's `finally` block, along with removal of the temporary input/configuration files.

### Testing

Run offline checks from the repository root:

```sh
groovy tests/llmao.groovy
python3 tests/pi-docker.py
```

These stub Jenkins steps and Docker/Pi output; they require no credentials or LLMAO access. To exercise the real Docker image and Pi CLI against a local mock gateway and a fixture skills repository:

```sh
docker build --tag sling-llmao-pi:1.1.0-maven21-99e61abc resources/org/apache/sling/jenkins/llmao
python3 tests/pi-docker.py --integration
```

The integration test exercises skill discovery/loading, real read/edit/write/shell tool calls, Java/Maven availability, environment-token authentication, model selection, tracked/new-file diffs, SUCCESS/FAILURE mapping, malformed/empty answers, API errors, cancellation, and container removal. It uses only disposable fixture projects and does not call LLMAO or update the real project. A real Jenkins run is still required to validate gateway access and the actual parent-POM migration.
