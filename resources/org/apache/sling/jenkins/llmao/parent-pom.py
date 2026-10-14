#!/usr/bin/env python3
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

"""Select and check a released Sling parent without executing project code."""

import json
from pathlib import Path
import re
import sys
from urllib.request import urlopen
import xml.etree.ElementTree as ET


def read_parent(path):
    root = ET.parse(path).getroot()
    if root.tag == "{http://maven.apache.org/POM/4.0.0}project":
        namespace = "{http://maven.apache.org/POM/4.0.0}"
    elif root.tag == "project":
        namespace = ""
    else:
        raise ValueError("Expected a Maven project document")
    parent = root.find(f"{namespace}parent")
    if parent is None:
        raise ValueError("pom.xml has no direct parent")
    result = {key: (parent.findtext(f"{namespace}{key}") or "").strip()
              for key in ("groupId", "artifactId", "version")}
    if result["groupId"] != "org.apache.sling" or result["artifactId"] not in (
            "sling-parent", "sling-bundle-parent"):
        raise ValueError("Expected org.apache.sling:sling-parent or sling-bundle-parent")
    if not re.fullmatch(r"[0-9]+", result["version"]):
        raise ValueError("The Sling parent must have an explicit numeric release version")
    return result


def select_target(parent, metadata):
    # Sling parent release versions are integers. Ignore snapshots and other
    # qualifiers rather than relying on lexical order or metadata's <latest>.
    versions = [int(version.text.strip())
                for version in ET.fromstring(metadata).findall("./versioning/versions/version")
                if version.text and re.fullmatch(r"[0-9]+", version.text.strip())]
    if not versions:
        raise ValueError("Maven Central metadata contains no released Sling parent versions")
    target = str(max(versions))
    if int(target) < int(parent["version"]):
        raise ValueError("Maven Central's latest release is older than the current parent")
    return dict(parent, targetVersion=target, updateRequired=target != parent["version"])


def check_parent(parent, expected):
    for key in ("groupId", "artifactId"):
        if parent[key] != expected[key]:
            raise ValueError(f"Parent {key} changed: expected {expected[key]}, found {parent[key]}")
    if parent["version"] != expected["targetVersion"]:
        raise ValueError(f"Expected parent version {expected['targetVersion']}, found {parent['version']} "
                         f"(original version {expected['version']})")
    if expected["updateRequired"] and parent["version"] == expected["version"]:
        raise ValueError("The parent version was not updated")


def main():
    action = sys.argv[1]
    parent = read_parent("/workspace/pom.xml")
    if action == "prepare-parent":
        url = ("https://repo.maven.apache.org/maven2/org/apache/sling/"
               f"{parent['artifactId']}/maven-metadata.xml")
        with urlopen(url, timeout=60) as response:
            expected = select_target(parent, response.read())
        print(json.dumps(expected))
    elif action == "check-parent":
        expected = json.loads(Path("/inputs/expected-parent.json").read_text(encoding="utf-8"))
        check_parent(parent, expected)
        if expected["updateRequired"]:
            print(f"Parent update verified: {parent['groupId']}:{parent['artifactId']} "
                  f"{expected['version']} -> {parent['version']}")
        else:
            print(f"No parent update needed: already at released version {parent['version']}")
    else:
        raise ValueError(f"Unknown parent check: {action}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Parent POM check failed: {error}", file=sys.stderr)
        sys.exit(1)
