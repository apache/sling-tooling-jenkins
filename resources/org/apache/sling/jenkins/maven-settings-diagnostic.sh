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
python3 - <<'PY'
import os
from pathlib import Path
import xml.etree.ElementTree as ET

path = Path(os.environ.get("MVN_SETTINGS") or
            Path.home() / ".m2/settings.xml")
print(f"Maven settings source: {path}")

try:
    if not path.is_file():
        print("User settings file does not exist")
        raise SystemExit(0)
    root = ET.parse(path).getroot()
except Exception as error:
    # Exception messages or tracebacks could contain settings contents.
    print(f"Unable to inspect Maven settings ({type(error).__name__})")
    raise SystemExit(1)

servers = [
    server for server in root.findall("./{*}servers/{*}server")
    if server.findtext("{*}id", "").strip() == "docker.io"
]
print(f"docker.io server entries: {len(servers)}")

for number, server in enumerate(servers, 1):
    for field, variable in [
        ("username", "DOCKERHUB_USER"),
        ("password", "DOCKERHUB_TOKEN"),
    ]:
        value = server.findtext("{*}" + field, "").strip()
        expected = "${env." + variable + "}"
        if value == expected:
            kind = "expected environment reference"
        elif "${" in value:
            kind = "different Maven expression"
        elif value:
            kind = "literal value (not shown)"
        else:
            kind = "empty"
        print(f"docker.io entry {number} {field}: {kind}")
PY
