#!/bin/bash
sed 's/builds.apache/ci-builds.apache/g' \
| sed 's/Sling\/job/Sling\/job\/modules\/job/g' \
| sed 's/test_results_analyzer/lastCompletedBuild\/testReport/g' \
| sed -E 's/buildStatus.icon[^\/]+\/([^\/]+)\/([a-z]+)/job\/Sling\/job\/modules\/job\/\1\/job\/\2\/badge\/icon/g'