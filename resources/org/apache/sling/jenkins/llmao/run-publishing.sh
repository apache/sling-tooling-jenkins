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

container="${LLMAO_CONTAINER}-publish"
trap 'docker rm --force "$container" >/dev/null 2>&1 || true' EXIT HUP INT TERM
options=()
case "$LLMAO_PUBLISH_ACTION" in
    prepare) workspace_mount="type=bind,source=$LLMAO_WORKSPACE,target=/workspace" ;;
    publish)
        workspace_mount="type=bind,source=$LLMAO_WORKSPACE,target=/workspace,readonly"
        options=(--env LLMAO_GITHUB_PAT)
        mkdir -p publication
        ;;
    *) echo 'Unknown publishing action.' >&2; exit 1 ;;
esac

run() {
    docker run --rm --init \
        --name "$container" \
        --user "$(id -u):$(id -g)" \
        --read-only --tmpfs /tmp:rw,nosuid,nodev,mode=1777 \
        "${options[@]}" \
        --mount "type=bind,source=$(pwd)/inputs,target=/inputs,readonly" \
        --mount "$workspace_mount" \
        --workdir /tmp \
        "$LLMAO_IMAGE" python3 /inputs/publish-pr.py "$LLMAO_PUBLISH_ACTION"
}

if [ "$LLMAO_PUBLISH_ACTION" = publish ]; then
    run > publication/pull-request.json
else
    run
fi
