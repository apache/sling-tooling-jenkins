#!/bin/sh
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set +x
set -eu

# No host configuration, stored credentials, sessions, or automatic updates.
export HOME=/tmp/pi-home
export PI_CODING_AGENT_DIR=/tmp/pi-agent
export PI_OFFLINE=1 PI_SKIP_VERSION_CHECK=1 PI_TELEMETRY=0
export LLMAO_USAGE_FILE=/reports/llmao-usage.json
export GIT_OPTIONAL_LOCKS=0
export MAVEN_CONFIG="$HOME/.m2"
export MAVEN_OPTS="-Duser.home=$HOME"
export MAVEN_ARGS='-B -ntp'
mkdir -p "$HOME" "$PI_CODING_AGENT_DIR"
cp /inputs/models.json /inputs/settings.json "$PI_CODING_AGENT_DIR/"
git config --global --add safe.directory /workspace

# Load all whiteboard skills, including their references and scripts. Keep
# them outside the project so the resulting diff contains only project edits.
timeout --kill-after=5s 120s git clone --depth 1 --filter=blob:none --sparse \
    --branch master https://github.com/apache/sling-whiteboard.git "$HOME/sling-whiteboard" >&2
git -C "$HOME/sling-whiteboard" sparse-checkout set skills >&2
printf 'Sling skills revision: ' >&2
git -C "$HOME/sling-whiteboard" rev-parse HEAD >&2

# Print mode keeps the final assistant text on stdout for the result check.
# The output logger sends assistant/progress/tool diagnostics to stderr.
exec timeout --kill-after=5s 1500s pi \
    --provider llmao --model "$LLMAO_MODEL" \
    --print --tools read,bash,edit,write,grep,find,ls --no-session \
    --no-extensions --no-mcp --no-skills --no-prompt-templates \
    --extension /inputs/tool-output-logger.mjs \
    --extension /inputs/usage-reporter.mjs \
    --skill "$HOME/sling-whiteboard/skills" \
    --no-context-files --no-approve \
    --system-prompt /inputs/system-prompt.txt \
    < /inputs/prompt_input.txt
