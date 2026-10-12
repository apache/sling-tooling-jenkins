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
mkdir -p "$HOME" "$PI_CODING_AGENT_DIR"
cp /inputs/models.json /inputs/settings.json "$PI_CODING_AGENT_DIR/"

# Print mode emits only the final assistant text to stdout; errors use stderr.
exec timeout --kill-after=5s 120s pi \
    --provider llmao --model "$LLMAO_MODEL" \
    --print --no-tools --no-session \
    --no-extensions --no-mcp --no-skills --no-prompt-templates \
    --no-context-files --no-approve \
    --system-prompt /inputs/system-prompt.txt \
    < /inputs/prompt_input.txt
