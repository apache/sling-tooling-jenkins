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

FROM node:24-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20 AS node

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320

COPY --from=node /usr/local/ /usr/local/

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash ca-certificates curl fd-find git python3 ripgrep \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/bin/fdfind /usr/local/bin/fd

RUN npm install --global --ignore-scripts --no-audit --no-fund \
    @earendil-works/pi-coding-agent@1.1.0

RUN printf '#!/bin/sh\nexport JAVA_HOME=/opt/java/openjdk\nexport PATH="$JAVA_HOME/bin:$PATH"\nexec /usr/share/maven/bin/mvn "$@"\n' \
      > /usr/local/bin/mvn21 \
    && chmod +x /usr/local/bin/mvn21

# The Maven entrypoint writes to a root-owned home; initialize a private home
# in run-pi.sh instead so arbitrary Jenkins executor UIDs can run the image.
ENTRYPOINT []
