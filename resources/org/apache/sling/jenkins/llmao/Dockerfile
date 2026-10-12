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

FROM eclipse-temurin:8-jdk-noble@sha256:55cdca1740b7bcfc1dc207744bd280e1ff2b7829475185745e1b5d19c3e42f97 AS jdk8
FROM eclipse-temurin:11-jdk-noble@sha256:82b78cb38df84009971601d16667cd1af118c281a79ae5064bf42ab8279d9f77 AS jdk11
FROM eclipse-temurin:17-jdk-noble@sha256:fb79620c3f6ee8f1c7530a8dc4fd49e3b410bcef037694ad4858a1553bac4170 AS jdk17

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320

COPY --from=node /usr/local/ /usr/local/
COPY --from=jdk8 /opt/java/openjdk/ /opt/java/jdk8/
COPY --from=jdk11 /opt/java/openjdk/ /opt/java/jdk11/
COPY --from=jdk17 /opt/java/openjdk/ /opt/java/jdk17/

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash ca-certificates curl fd-find git python3 ripgrep \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/bin/fdfind /usr/local/bin/fd

RUN npm install --global --ignore-scripts --no-audit --no-fund \
    @earendil-works/pi-coding-agent@1.1.0

RUN for version in 8 11 17 21; do \
      home="/opt/java/jdk$version"; \
      if [ "$version" = 21 ]; then home=/opt/java/openjdk; fi; \
      printf '#!/bin/sh\nexport JAVA_HOME=%s\nexport PATH="$JAVA_HOME/bin:$PATH"\nexec /usr/share/maven/bin/mvn "$@"\n' "$home" \
        > "/usr/local/bin/mvn$version"; \
      chmod +x "/usr/local/bin/mvn$version"; \
    done

# The Maven entrypoint writes to a root-owned home; initialize a private home
# in run-pi.sh instead so arbitrary Jenkins executor UIDs can run the image.
ENTRYPOINT []
