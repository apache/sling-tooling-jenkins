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

`slingOsgiBundleBuild(llmaoOnly: true)` runs only an LLMAO request, not the Maven matrix, SonarCloud analysis, deployment, or normal build notifications. Projects using `slingOsgiBundleBuild()` without this argument continue to build as before; they do not call LLMAO.

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
    llmaoInput: "The build failed with error: 'npm ERR! code ERESOLVE'."
)
```

### LLMAO configuration

These settings can be provided as library arguments or under `jenkins` in `.sling-module.json`; arguments take precedence. Only `llmaoOnly: true` in the library invocation selects this mode. It cannot be enabled by the module descriptor.

| Setting | Default | Meaning |
| --- | --- | --- |
| `llmaoRebuildFrequency` | `@weekly` | Cron for the dedicated job. Use `''` for manual runs only. |
| `llmaoModel` | `gemma4-26b` | Model ID available to the PAT on the LLMAO [Models page](https://llm.apache.org/models). |
| `llmaoInput` | Empty | Error summary to send as the user message. |
| `llmaoInputFile` | Empty | Workspace-relative UTF-8 file to read instead of `llmaoInput`. |

Provide either an input string or an input file; the library has no sample input by default and does not fetch logs from another Jenkins job. To supply live data, gather a small, sanitized summary in the dedicated job and pass it as `llmaoInput`. The library allocates its own node and checks out the project; `llmaoInputFile` must exist in that checked-out workspace. Input files are not deleted. Avoid sending secrets or large logs, and check the Models page for who can see your prompts.

The library runs [Pi](https://pi.dev) in Docker as a non-interactive LLM client. Pi is configured for LLMAO's OpenAI-compatible endpoint at `https://llm.apache.org/v1`, with the selected model and temperature `0.0`. The existing system prompt and input are passed to Pi, with tools, extensions, MCP, project instructions, stored sessions, automatic retries, and compaction disabled. Pi adds its own working-directory metadata to the system prompt; it cannot inspect the project checkout. Only its final text on stdout is captured as the analysis result.

An exact `RETRY` is reported without retrying the normal project build; `FIX` or an ambiguous answer marks only the LLMAO job unstable. Empty answers, Pi errors, HTTP/network errors, and timeouts fail the LLMAO job. Generated input/configuration files are cleaned up, and raw prompts/responses are not archived or printed.

### Docker runtime

The `ubuntu` agent needs a Unix shell and access to a **local Docker daemon**. The daemon must see the executor's temporary workspace at the same absolute path for the input bind mount; containerized executors must share that path with the Docker host. The job also requires the Jenkins Pipeline Utility Steps plugin (`writeJSON`/`readJSON`). No host Node.js, Pi installation, or Jenkins Docker Pipeline plugin is required.

The library builds `sling-llmao-pi:1.1.0-node24-d6aa754f` from its [Dockerfile](resources/org/apache/sling/jenkins/llmao/Dockerfile), pinning the official `node:24-bookworm-slim` image by digest and the `@earendil-works/pi-coding-agent` package at version `1.1.0`. Docker's layer cache avoids reinstalling Pi on subsequent runs on that executor. The first uncached build needs access to Docker Hub and the npm registry; the running container needs access to `llm.apache.org`.

Image building happens before credential binding and outside stdout capture. The container receives `LLMAO_PAT` from the Jenkins environment, not as a token value in its command line or configuration files. It runs as the Jenkins user's UID/GID, mounts only temporary input files read-only, and keeps its writable configuration in container-local `/tmp`. The Pi process is bounded to 120 seconds, with a five-second forced-termination grace period. Container removal is attempted on shell exit and again in the Pipeline's `finally` block, along with removal of the temporary files. No credentials or sessions are persisted in the image.

### Testing

Run offline checks from the repository root:

```sh
groovy tests/llmao.groovy
python3 tests/pi-docker.py
```

These stub Jenkins steps and Docker/Pi output; they require no credentials or LLMAO access. To exercise the real Docker image and Pi CLI against a local mock OpenAI-compatible gateway:

```sh
docker build --tag sling-llmao-pi:1.1.0-node24-d6aa754f resources/org/apache/sling/jenkins/llmao
python3 tests/pi-docker.py --integration
```

The integration test verifies environment-token authentication, model selection, prompt/input preservation, temperature, stdout, empty answers, HTTP errors, cancellation, and container removal. It does not call LLMAO. A real Jenkins run is still required to validate the Pipeline environment and gateway access.
