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

if [ "$LLMAO_CHECK" = prepare-parent ]; then
    checkout_status=$(git -C "$LLMAO_WORKSPACE" status --porcelain)
    if [ -n "$checkout_status" ]; then
        echo 'The project checkout must be clean before selecting the parent target.' >&2
        exit 1
    fi
fi

case "$LLMAO_CHECK" in
    prepare-parent|check-parent) command=(python3 /inputs/parent-pom.py "$LLMAO_CHECK") ;;
    verify-build) command=(sh /inputs/verify-build.sh) ;;
    *) echo 'Unknown verification action.' >&2; exit 1 ;;
esac

# Use the same pinned image, but a new process/home with no agent or PAT.
container="${LLMAO_CONTAINER}-verify"
trap 'docker rm --force "$container" >/dev/null 2>&1 || true' EXIT HUP INT TERM
docker run --rm --init \
    --name "$container" \
    --user "$(id -u):$(id -g)" \
    --read-only --tmpfs /tmp:rw,nosuid,nodev,mode=1777 \
    --mount "type=bind,source=$(pwd)/inputs,target=/inputs,readonly" \
    --mount "type=bind,source=$LLMAO_WORKSPACE,target=/workspace" \
    --mount "type=bind,source=$LLMAO_WORKSPACE/.git,target=/workspace/.git,readonly" \
    --workdir /workspace \
    "$LLMAO_IMAGE" "${command[@]}"
