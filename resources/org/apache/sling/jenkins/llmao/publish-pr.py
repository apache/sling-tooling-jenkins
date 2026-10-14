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

"""Trusted GitHub publishing; never execute project code with the GitHub PAT."""

import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

BRANCH = "llmao/update-sling-parent-pom"
# Git invokes ! helpers through its shell. Only the environment reference is
# passed on the command line; get returns the credential over a private pipe.
CREDENTIAL_HELPER = ('!f() { if [ "$1" = get ]; then printf "%s\\n" '
                     '"username=x-access-token" "password=$LLMAO_GITHUB_PAT"; fi; }; f')


def validate_config(config):
    for key in ("forkRepository", "upstreamRepository"):
        if not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]*/[A-Za-z0-9_-][A-Za-z0-9_.-]*",
                            config.get(key, "")):
            raise ValueError(f"Invalid {key}: expected owner/repository")
    if config["forkRepository"].lower() == config["upstreamRepository"].lower():
        raise ValueError("The fork and upstream repositories must differ")
    if not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_./-]*", config.get("baseBranch", "")):
        raise ValueError("Invalid baseBranch")
    if config["baseBranch"] == BRANCH:
        raise ValueError("The automation branch must differ from the base branch")
    for key in ("authorName", "authorEmail"):
        value = config.get(key, "")
        if not value.strip() or any(character in value for character in "\r\n\0<>"):
            raise ValueError(f"Invalid {key}")


class NoRedirect(HTTPRedirectHandler):
    # Never forward Authorization to a redirected host.
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class GitHub:
    def __init__(self, token=""):
        self.token = token

    def __call__(self, path, data=None, method=None):
        headers = {"Accept": "application/vnd.github+json", "User-Agent": "sling-llmao-jenkins",
                   "X-GitHub-Api-Version": "2022-11-28"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request("https://api.github.com" + path, headers=headers,
                          data=None if data is None else json.dumps(data).encode("utf-8"), method=method)
        try:
            with build_opener(NoRedirect()).open(request, timeout=60) as response:
                return json.load(response)
        except HTTPError as error:
            # Response bodies/headers can contain sensitive diagnostics.
            raise RuntimeError(f"GitHub API returned HTTP {error.code}") from None


class Git:
    def __init__(self, directory, environment):
        self.directory = directory
        self.environment = environment

    def __call__(self, *arguments, input=None):
        command = ["git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
                   "-c", "credential.helper=", "-c", "http.followRedirects=false"]
        if self.environment.get("LLMAO_GITHUB_PAT"):
            command += ["-c", "credential.helper=" + CREDENTIAL_HELPER]
        command += ["-C", str(self.directory), *arguments]
        result = subprocess.run(command, input=input, capture_output=True, env=self.environment)
        if result.returncode:
            message = result.stderr.decode("utf-8", errors="replace")
            token = self.environment.get("LLMAO_GITHUB_PAT")
            if token:
                message = message.replace(token, "[REDACTED]")
            raise RuntimeError(f"git {arguments[0]} failed: {message.strip()}")
        return result.stdout

    def text(self, *arguments):
        return self(*arguments).decode("utf-8").strip()


class Publisher:
    def __init__(self, config, workspace, github, environment, url_for=None):
        validate_config(config)
        self.config = config
        self.workspace = Path(workspace)
        self.github = github
        self.environment = environment
        self.url_for = url_for or (lambda repository: f"https://github.com/{repository}.git")

    def check_fork(self):
        repository = self.github(f"/repos/{self.config['forkRepository']}")
        network = [(repository.get(key) or {}).get("full_name", "").lower() for key in ("parent", "source")]
        if not repository.get("fork") or self.config["upstreamRepository"].lower() not in network:
            raise ValueError("The configured repository is not a fork of the configured upstream")
        if repository.get("private"):
            raise ValueError("Only public forks are supported")

    def prepare(self):
        git = Git(self.workspace, self.environment)
        if not (self.workspace / ".git").is_dir():
            raise ValueError("Publishing requires a standalone Git checkout")
        if git.text("status", "--porcelain"):
            raise ValueError("The project checkout must be clean before preparing publication")
        if any(entry.startswith(b"S ") for entry in git("ls-files", "-t", "-z").split(b"\0")):
            raise ValueError("Publishing does not support sparse or skip-worktree checkouts")
        git("check-ref-format", f"refs/heads/{self.config['baseBranch']}")
        self.check_fork()
        # Never replace an unfamiliar existing remote or touch origin.
        for remote, repository in (("fork", self.config["forkRepository"]),
                                   ("llmao-upstream", self.config["upstreamRepository"])):
            url = self.url_for(repository)
            if remote in git.text("remote").splitlines():
                if git.text("remote", "get-url", remote) != url:
                    raise ValueError(f"Existing {remote} remote has a different URL")
            else:
                git("remote", "add", remote, url)
        git("fetch", "--no-tags", self.url_for(self.config["upstreamRepository"]),
            f"refs/heads/{self.config['baseBranch']}")
        base = git.text("rev-parse", "FETCH_HEAD")
        git("checkout", "--detach", base)
        tip = git.text("ls-remote", "--heads", self.url_for(self.config["forkRepository"]),
                       f"refs/heads/{BRANCH}")
        return {"baseCommit": base, "forkBranchTip": tip.split()[0] if tip else ""}

    def open_pr(self):
        owner = self.config["forkRepository"].split("/")[0]
        query = urlencode({"state": "open", "head": f"{owner}:{BRANCH}",
                           "base": self.config["baseBranch"], "per_page": 100})
        result = self.github(f"/repos/{self.config['upstreamRepository']}/pulls?{query}")
        matches = [pr for pr in result
                   if (pr.get("head", {}).get("repo") or {}).get("full_name", "").lower()
                   == self.config["forkRepository"].lower()
                   and pr["head"].get("ref") == BRANCH
                   and (pr.get("base", {}).get("repo") or {}).get("full_name", "").lower()
                   == self.config["upstreamRepository"].lower()
                   and pr["base"].get("ref") == self.config["baseBranch"]]
        if len(matches) > 1:
            raise ValueError("Multiple matching open PRs exist; refusing to choose one")
        return matches[0] if matches else None

    def snapshot_tree(self, git):
        # Use a fresh index and hash raw bytes, not git add: project attributes,
        # clean filters, hooks, and signing commands must not execute with a PAT.
        paths = set(git("--work-tree=" + str(self.workspace), "ls-files", "--cached", "--others",
                        "--exclude-standard", "-z").split(b"\0")) - {b""}
        entries = []
        for raw_path in sorted(paths):
            relative = Path(os.fsdecode(raw_path))
            if relative.is_absolute() or ".." in relative.parts or ".git" in relative.parts:
                raise ValueError("Unsafe path in publication snapshot")
            path = self.workspace / relative
            for parent in relative.parents:
                if (self.workspace / parent).is_symlink():
                    raise ValueError("Refusing to traverse a symlink directory in the snapshot")
            try:
                mode = path.lstat().st_mode
            except FileNotFoundError:
                entries.append(b"0 " + b"0" * 40 + b"\t" + raw_path + b"\0")
                continue
            if stat.S_ISLNK(mode):
                content = os.fsencode(os.readlink(path))
                git_mode = "120000"
            elif stat.S_ISREG(mode):
                content = path.read_bytes()
                git_mode = "100755" if mode & stat.S_IXUSR else "100644"
            else:
                raise ValueError("Only regular files and symlinks can be published (no submodules)")
            blob = git("hash-object", "-w", "--stdin", input=content).strip()
            entries.append(git_mode.encode() + b" " + blob + b"\t" + raw_path + b"\0")
        git("update-index", "-z", "--index-info", input=b"".join(entries))
        return git.text("write-tree")

    def publish(self, plan, expected):
        for key in ("baseCommit", "forkBranchTip"):
            value = plan.get(key, "")
            if not re.fullmatch(r"[0-9a-f]{40}", value) and not (key == "forkBranchTip" and value == ""):
                raise ValueError(f"Invalid publication plan {key}")
        if Git(self.workspace, self.environment).text("rev-parse", "HEAD") != plan["baseCommit"]:
            raise ValueError("The checkout base changed after publication preparation")
        with tempfile.TemporaryDirectory(prefix="llmao-publish-") as directory:
            git = Git(directory, self.environment)
            git("init", "--quiet")
            git("fetch", "--no-tags", self.url_for(self.config["upstreamRepository"]),
                f"refs/heads/{self.config['baseBranch']}")
            if git.text("rev-parse", "FETCH_HEAD") != plan["baseCommit"]:
                raise ValueError("Upstream advanced after verification; rerun against the new base")
            git("read-tree", plan["baseCommit"])
            tree = self.snapshot_tree(git)
            if tree == git.text("rev-parse", f"{plan['baseCommit']}^{{tree}}"):
                return {"status": "skipped", "reason": "no-changes", "branch": BRANCH}
            self.check_fork()
            pr = self.open_pr()
            title = f"Update Sling parent to {expected['targetVersion']}"
            commit = git.text("commit-tree", tree, "-p", plan["baseCommit"], "-m", title)
            git("update-ref", f"refs/heads/{BRANCH}", commit)
            git("remote", "add", "fork", self.url_for(self.config["forkRepository"]))
            # Empty expected tip means the branch must not exist. An explicit
            # SHA avoids leases weakened by background fetches or other writers.
            git("push", "--porcelain", f"--force-with-lease=refs/heads/{BRANCH}:{plan['forkBranchTip']}",
                "fork", f"refs/heads/{BRANCH}:refs/heads/{BRANCH}")
            status = "reused" if pr else "created"
            data = {"title": title, "body": (
                f"Automated update of `{expected['groupId']}:{expected['artifactId']}` "
                f"from {expected['version']} to {expected['targetVersion']}.\n\n"
                "Jenkins independently checked the parent coordinates/version and ran "
                "`mvn -B -ntp clean verify` with Java 21.\n\n"
                "Please review the generated changes before merging.")}
            build_url = self.config.get("buildUrl", "")
            if re.fullmatch(r"https?://[^\s<>]+", build_url):
                data["body"] += f"\n\nJenkins build: {build_url}"
            if pr is not None:
                # Keep reused PR metadata accurate when a newer parent is
                # released or a different Jenkins build refreshes the branch.
                pr = self.github(f"/repos/{self.config['upstreamRepository']}/pulls/{int(pr['number'])}",
                                 data, method="PATCH")
            else:
                data.update(head=f"{self.config['forkRepository'].split('/')[0]}:{BRANCH}",
                            base=self.config["baseBranch"], draft=True)
                try:
                    pr = self.github(f"/repos/{self.config['upstreamRepository']}/pulls", data)
                except (RuntimeError, URLError, TimeoutError):
                    # A POST can succeed even if the response is lost. Look up
                    # the exact head/base again instead of blindly retrying it.
                    pr = self.open_pr()
                    if pr is None:
                        raise
                    status = "reused"
            url = pr.get("html_url", "")
            if pr.get("state") != "open":
                raise ValueError("The PR closed during publication; rerun to find or create an open PR")
            if not re.fullmatch(r"https://github\.com/" + re.escape(self.config["upstreamRepository"])
                                + r"/pull/[0-9]+", url, re.IGNORECASE):
                raise ValueError("GitHub returned an unexpected PR URL")
            return {"status": status, "url": url, "number": pr["number"], "branch": BRANCH,
                    "commit": commit}


def git_environment(config, home, token):
    # Do not inherit executor Git configuration, credential helpers, or homes.
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith("GIT_") and key not in ("LLMAO_PAT", "LLMAO_GITHUB_PAT")}
    environment.update(HOME=home, XDG_CONFIG_HOME=home, GIT_CONFIG_NOSYSTEM="1",
                       GIT_TERMINAL_PROMPT="0", GIT_AUTHOR_NAME=config["authorName"],
                       GIT_AUTHOR_EMAIL=config["authorEmail"], GIT_COMMITTER_NAME=config["authorName"],
                       GIT_COMMITTER_EMAIL=config["authorEmail"])
    if token:
        environment["LLMAO_GITHUB_PAT"] = token
    return environment


def main():
    action = sys.argv[1]
    config = json.loads(Path("/inputs/publish-config.json").read_text(encoding="utf-8"))
    token = os.environ.get("LLMAO_GITHUB_PAT", "") if action == "publish" else ""
    if action == "publish" and not token:
        raise ValueError("The GitHub publishing credential is missing")
    with tempfile.TemporaryDirectory(prefix="llmao-git-home-") as home:
        environment = git_environment(config, home, token)
        publisher = Publisher(config, "/workspace", GitHub(token), environment)
        if action == "prepare":
            result = publisher.prepare()
        elif action == "publish":
            result = publisher.publish(json.loads(Path("/inputs/publication-plan.json").read_text()),
                                       json.loads(Path("/inputs/expected-parent.json").read_text()))
        else:
            raise ValueError("Unknown publishing action")
        print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        message = str(error)
        token = os.environ.get("LLMAO_GITHUB_PAT")
        if token:
            message = message.replace(token, "[REDACTED]")
        print(f"Pull request publishing failed: {message}", file=sys.stderr)
        sys.exit(1)
