#!/bin/bash
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
export GIT_OPTIONAL_LOCKS=0

# Docker containers outlive their client process unless explicitly stopped.
trap 'docker rm --force "$LLMAO_CONTAINER" >/dev/null 2>&1 || true' EXIT HUP INT TERM

checkout_status=$(git -C "$LLMAO_WORKSPACE" status --porcelain)
if [ -n "$checkout_status" ]; then
    echo 'The project checkout must be clean before the Pi update.' >&2
    echo FAILURE
    exit 1
fi

status=0
mkdir -p "$LLMAO_REPORT_DIR"
result=$(docker run --rm --init \
    --name "$LLMAO_CONTAINER" \
    --user "$(id -u):$(id -g)" \
    --read-only --tmpfs /tmp:rw,nosuid,nodev,mode=1777 \
    --env LLMAO_PAT --env LLMAO_MODEL \
    --mount "type=bind,source=$(pwd)/inputs,target=/inputs,readonly" \
    --mount "type=bind,source=$LLMAO_REPORT_DIR,target=/reports" \
    --mount "type=bind,source=$LLMAO_WORKSPACE,target=/workspace" \
    --mount "type=bind,source=$LLMAO_WORKSPACE/.git,target=/workspace/.git,readonly" \
    --workdir /workspace \
    "$LLMAO_IMAGE" sh /inputs/run-pi.sh) || status=$?

result=$(printf '%s' "$result" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
if [ "$status" -eq 0 ] && [ "$result" = SUCCESS ]; then
    echo SUCCESS
else
    echo FAILURE
    if [ "$status" -eq 0 ]; then
        if [ "$result" != FAILURE ]; then
            echo 'Pi did not return exactly SUCCESS or FAILURE.' >&2
            printf 'Unexpected Pi response: <%s>\n' "$result" >&2
        fi
        status=1
    fi
fi

# Report partial edits on failure as well. Include new files without staging
# them, and disable external diff drivers/text converters from the checkout.
printf 'Pi project diff (exit code %s):\n' "$status"
git --no-pager -C "$LLMAO_WORKSPACE" diff --no-ext-diff --no-textconv --binary HEAD --
while IFS= read -r -d '' file; do
    git --no-pager -C "$LLMAO_WORKSPACE" diff --no-ext-diff --no-textconv \
        --no-index --binary -- /dev/null "$file" || test "$?" -eq 1
done < <(git -C "$LLMAO_WORKSPACE" ls-files --others --exclude-standard -z)
if [ -z "$(git -C "$LLMAO_WORKSPACE" status --porcelain)" ]; then
    echo 'No project changes.'
fi

exit "$status"
