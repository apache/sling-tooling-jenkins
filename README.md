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

The dedicated job's stages reflect the local agent workflow:

1. **Checkout and Load Configuration** — check out the project and resolve module settings and library arguments.
2. **Configure Job** — apply scheduling, retention, and concurrency settings, then read and validate the task input.
3. **Build Pi Image** — build or reuse the cached Docker image, without binding credentials.
4. **Prepare Pi Task** — write the prompts, model settings, runtime script, and bundled extensions outside the checkout.
5. **Run Pi Task** — run the agent, including skill loading, LLMAO requests, project edits, verification, and diff output. An unsuccessful task fails this stage.
6. **Publish Token Usage** — stop any remaining container and summarize and archive observed usage, also after build or task failures.
7. **Clean Up** — remove generated runtime inputs and temporary reports, leaving project edits untouched.

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
    llmaoInput: 'Update this project to the most recent released parent POM version and respond only with SUCCESS or FAILURE'
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

A bundled output logger is explicitly loaded. It writes Pi's assistant text, model-response stop reasons/errors, agent/request progress, tool starts and completion status, and tool-result tails to stderr in the Jenkins console. Tool-call summaries include shell commands, paths, and search patterns (up to 2,000 characters), but not file contents supplied to edit/write tools. Assistant text and tool results are limited to their last 6,000 characters; successful file/skill-read contents, prompts, images, and reasoning are not logged. The logger redacts the `LLMAO_PAT` value before truncation and strips ANSI color codes. It does not alter assistant messages, tool results, or the final SUCCESS/FAILURE response on stdout.

Pi retries transient provider/network errors, including HTTP 429 rate limits, up to 10 times per failed model turn at one-minute intervals. Retries stay in the same Pi session, preserving tool history and edits; provider-level retries remain disabled. This polls for quota recovery rather than parsing LLMAO's custom reset timestamp, and the existing job/process timeouts still apply.

Each run fetches `skills/` from the `master` branch of [Apache Sling Whiteboard](https://github.com/apache/sling-whiteboard/tree/master/skills), including references and scripts. All its skills are explicitly available to Pi, including `update-sling-parent-pom`. The fetched Git revision is logged. Skills live outside the project checkout and do not appear in the diff. The parent-POM skill currently targets the latest supported release, not simply the current version plus one.

Pi is instructed to respond only with `SUCCESS` after completing and verifying its update, or `FAILURE` otherwise, without staging, committing, pushing, switching branches, or altering Git metadata. The wrapper maps an exact `SUCCESS` and a successful Pi process to exit code `0`; `FAILURE`, empty/malformed answers, or execution errors return a nonzero exit code and fail the Jenkins job. The final response is kept separate from diff output, so strings in the diff cannot be mistaken for an agent result.

The resulting Git diff is printed to stdout in the Jenkins build log, including partial changes on failure and new, non-ignored files without staging them. Project edits remain in the job workspace for inspection; no commit, push, or automatic recovery is performed. Generated input/configuration files are cleaned up, and sessions are not archived.

### Run the LLMAO Pi setup locally

The commands below run the same Pi version as the Jenkins image, with the same tool allowlist and an explicitly supplied Sling skills directory. They run Pi **inside Docker**, rather than using a host-installed `pi`, so only the selected project checkout is mounted from the host. Docker and a local Docker daemon are required.

Build the image from this repository root:

```sh
docker build --tag sling-llmao-pi:1.1.0-jdk21-v3 resources/org/apache/sling/jenkins/llmao
```

Set these paths and model values in Bash, replacing the examples. `SKILLS_DIR` may be the `skills/` root or one skill directory containing `SKILL.md`. `PI_CONFIG_DIR` must be a **minimal** Pi configuration directory containing `models.json` and `settings.json`, and optionally `auth.json`; use the provider/model configuration and credential for the LLM you want to test. Prefer a short-lived credential supplied through an environment variable over storing a key in a file. Do not mount the whole `~/.pi/agent` directory: it can expose unrelated credentials, sessions, extensions, and skills. For LLMAO, set `PI_PROVIDER=llmao`, `PI_MODEL` to a model available to your key, and export `LLMAO_PAT` in the host shell.

```sh
export PROJECT_DIR="$(realpath /path/to/project-checkout)"
export SKILLS_DIR="$(realpath /path/to/sling-whiteboard/skills)"
export PI_CONFIG_DIR="$(realpath /path/to/minimal-pi-config)"
export PI_PROVIDER=llmao
export PI_MODEL=gemma4-26b
# Export LLMAO_PAT using your local secret manager or shell; do not put its value in this file.

# Like the Jenkins job, start from a clean, ordinary Git checkout.
check_checkout() {
  if [ ! -d "$PROJECT_DIR/.git" ]; then
    echo 'Use a normal clone, not a linked worktree.' >&2
    return 1
  fi
  if [ -n "$(git -C "$PROJECT_DIR" status --porcelain)" ]; then
    echo 'Project checkout must be clean.' >&2
    return 1
  fi
}
check_checkout
```

Define a small wrapper that gives the container only the project, its read-only Git metadata, the supplied skills, and the minimal Pi configuration. It starts an interactive TTY when run from a terminal and also works for print mode or piped input.

```bash
run_pi() {
  local tty_args=()
  if [[ -t 0 && -t 1 ]]; then tty_args=(-it); else tty_args=(-i); fi

  docker run --rm --init "${tty_args[@]}" \
    --user "$(id -u):$(id -g)" \
    --read-only --tmpfs /tmp:rw,nosuid,nodev,mode=1777 \
    --cap-drop=ALL --security-opt=no-new-privileges \
    --env HOME=/tmp/pi-home \
    --env PI_CODING_AGENT_DIR=/tmp/pi-agent \
    --env PI_OFFLINE=1 --env PI_SKIP_VERSION_CHECK=1 --env PI_TELEMETRY=0 \
    --env GIT_OPTIONAL_LOCKS=0 \
    --env LLMAO_PAT \
    --mount "type=bind,source=$PROJECT_DIR,target=/workspace" \
    --mount "type=bind,source=$PROJECT_DIR/.git,target=/workspace/.git,readonly" \
    --mount "type=bind,source=$SKILLS_DIR,target=/skills,readonly" \
    --mount "type=bind,source=$PI_CONFIG_DIR,target=/config,readonly" \
    --workdir /workspace \
    sling-llmao-pi:1.1.0-jdk21-v3 \
    sh -c '
      set -eu
      mkdir -p "$HOME" "$PI_CODING_AGENT_DIR"
      cp /config/models.json /config/settings.json "$PI_CODING_AGENT_DIR/"
      if [ -f /config/auth.json ]; then cp /config/auth.json "$PI_CODING_AGENT_DIR/"; fi
      git config --global --add safe.directory /workspace
      export MAVEN_CONFIG="$HOME/.m2"
      export MAVEN_OPTS="-Duser.home=$HOME"
      export MAVEN_ARGS="-B -ntp"
      exec pi "$@"
    ' pi "$@"
}
```

The wrapper forwards `LLMAO_PAT` for the LLMAO provider. For another provider, add only its required environment variable(s) as `--env NAME` options, or put only that provider's credential in the mounted `auth.json`. The container's Pi configuration and Maven home are temporary and are discarded when it exits.

#### Exact non-interactive reproducer

This follows the Jenkins prompt and flags: it selects the LLMAO model, uses the same read/search/edit/write/shell tools, disables automatic extensions/MCP/skills/prompts/context files, and explicitly loads the supplied skills. Change the task text if reproducing a different update.

```sh
run_pi \
  --provider "$PI_PROVIDER" --model "$PI_MODEL" \
  --print --tools read,bash,edit,write,grep,find,ls --no-session \
  --no-extensions --no-mcp --no-skills --no-prompt-templates \
  --skill /skills --no-context-files --no-approve \
  --system-prompt 'You are a coding assistant working on the checked-out project. Follow the relevant supplied Apache Sling skills; use update-sling-parent-pom for upgrades to the most recent released parent POM version. Complete the requested update and its required verification. Java 21 is the only available JDK; mvn and mvn21 both run Maven with Java 21. Do not stage, unstage, commit, push, switch branches, or change Git metadata. Respond with exactly SUCCESS if the update and verification succeeded, or FAILURE otherwise. Do not include punctuation or explanation.' \
  'Update this project to the most recent released parent POM version and respond only with SUCCESS or FAILURE'
```

Pi prints its final response to stdout; inspect the resulting project changes on the host with `git -C "$PROJECT_DIR" diff` and `git -C "$PROJECT_DIR" status --short`. Git metadata is mounted read-only, so Pi cannot stage or commit changes.

#### Interactive Pi use

Run the same wrapper without `--print`, retaining the same skill and tool flags:

```sh
run_pi \
  --provider "$PI_PROVIDER" --model "$PI_MODEL" \
  --tools read,bash,edit,write,grep,find,ls --no-session \
  --no-extensions --no-mcp --no-skills --no-prompt-templates \
  --skill /skills --no-context-files --no-approve
```

In the Pi TUI, invoke the relevant skill explicitly when desired, for example `/skill:update-sling-parent-pom`, then describe the task. Both modes mount only `$PROJECT_DIR` from the host as a writable checkout; its `.git` directory is read-only. Other host checkouts, including sibling Sling projects, are not mounted. Files within the selected project directory remain accessible, as do the explicitly mounted skills/configuration. Network access is still available for LLMAO and dependency downloads; this filesystem isolation does not prevent fetching remote repositories. For a linked Git worktree, use a separate ordinary clone: its `.git` file points at shared metadata outside the checkout, which is intentionally not mounted.

### Per-build token usage

LLMAO builds that reach the image-build stage publish a console summary, add the observed total to their build description (for example, `LLMAO: SUCCESS | 18420 tokens`), and archive `llmao-usage.json`. Normal non-LLMAO builds are unaffected. No model prices or monetary cost estimates are configured.

A dedicated Pi extension collects gateway-reported usage across all model responses, including tool-calling turns and any usage reported on failed or retried turns. The report contains:

- `provider`, `model`, and Jenkins `build` metadata (`job`, `number`, and `url`).
- `result` (`SUCCESS`, `FAILURE`, or `INCOMPLETE` when no process exit code was returned), `exitCode`, Pi `state`, and `aborted`.
- `requests` (provider request attempts), `responsesWithUsage`, and `responsesWithoutUsage`. Counters are `null` and Pi state is `unknown` if the reporter could not produce a file.
- `tokens`: `input` (uncached input), `output`, `cacheRead`, `cacheWrite`, and `total`. Cached input is counted separately, not added to input twice; reasoning tokens are already included in output.
- `usageStatus`: `complete` if Pi settled without interruption and every response reported usage; `partial` if only some usage is available or the run was interrupted; `unavailable` if no valid usage was reported. `tokens` is `null` for unavailable usage, rather than a misleading zero. A gateway-reported zero remains zero.

Usage is checkpointed atomically in a unique output directory outside the project checkout after each response. Jenkins stops the container before publishing and archiving the report, then cleans up the temporary report files. Reports are published on success and failure, and on cancellation where the Jenkins agent remains available. They contain counts and metadata only, not prompts, source content, reasoning, credentials, or session history.

These are **observed token totals**, not billing records: interrupted requests and gateway errors may not return usage, so partial totals can undercount actual consumption. Failed requests without usage are not guessed to be free. Artifacts follow the job's existing ten-build retention policy; download the JSON reports before expiry if you need longer-term cost tracking.

### Docker runtime

The `ubuntu` agent needs Bash, Git, and access to a **local Docker daemon**. The daemon must see the project and temporary workspaces at the same absolute paths for bind mounts; containerized executors must share those paths with the Docker host. The job also requires the Jenkins Pipeline Utility Steps plugin (`writeJSON`/`readJSON`). No host Node.js, Java, Maven, Pi installation, or Jenkins Docker Pipeline plugin is required.

The library builds `sling-llmao-pi:1.1.0-jdk21-v3` from its [Dockerfile](resources/org/apache/sling/jenkins/llmao/Dockerfile), pinning the official Node 24 and Maven/Temurin 21 images by digest and Pi at version `1.1.0`. The image includes only JDK 21, plus Maven 3.9.16, Git, curl, Python, ripgrep, and `fd` (Ubuntu's `fd-find` exposed under the name Pi expects). Release credentials are not included. Docker's layer cache avoids reinstalling tools on subsequent runs. An uncached build needs Docker Hub, npm, and Ubuntu package repositories; the running container needs LLMAO, GitHub, Maven repositories, and any documentation needed by the skill.

Java 21 is the only available JDK. Both `mvn` and the `mvn21` wrapper use it, and Pi is told to use these commands. No `mvn8`, `mvn11`, or `mvn17` wrappers are installed. `MAVEN_ARGS='-B -ntp'` applies batch mode and suppresses download-progress output for both Maven commands, even when a skill invokes `mvn clean verify` without those flags.

Image building happens before credential binding. The container receives `LLMAO_PAT` from the Jenkins environment, not as a token value in its command line or configuration files. It runs as the Jenkins user's UID/GID with writable project and usage-report mounts and read-only `.git` and input mounts. Its private configuration, skills checkout, and Maven home/cache live in container-local `/tmp`; host credentials and the Docker socket are not mounted. The Pi process is bounded to 25 minutes within a 30-minute job timeout. Container removal is attempted on shell exit and again in the Pipeline's `finally` block, along with removal of the temporary input/configuration files.

### Testing

Run offline checks from the repository root:

```sh
groovy tests/llmao.groovy
node tests/tool-output-logger.mjs
node tests/usage-reporter.mjs
python3 tests/pi-docker.py
```

These stub Jenkins steps and Docker/Pi output; they require no credentials or LLMAO access. To exercise the real Docker image and Pi CLI against a local mock gateway and a fixture skills repository:

```sh
docker build --tag sling-llmao-pi:1.1.0-jdk21-v3 resources/org/apache/sling/jenkins/llmao
python3 tests/pi-docker.py --integration
```

The integration test exercises skill discovery/loading, real read/edit/write/find/shell tool calls, assistant/progress/tool console output with private read contents, Java 21 with both Maven commands and the absence of older JDKs/wrappers, Maven batch-mode settings, environment-token authentication, model selection, tracked/new-file diffs, SUCCESS/FAILURE mapping, malformed/empty answers, API errors, multi-turn token/cache totals, retries, missing/zero usage, cancellation, and container removal. It uses only disposable fixture projects and does not call LLMAO or update the real project. A real Jenkins run is still required to validate gateway access, usage artifact publishing, and the actual parent-POM migration.
