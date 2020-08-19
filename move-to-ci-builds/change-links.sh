#!/bin/bash
function fatal() {
    echo $* >&2
    exit 1
}

( awk '{if ($0 ~ /ci-builds/) { exit 1 } ; print $0 }' || fatal "Input was already processed?" ) \
| sed 's/builds.apache/ci-builds.apache/g' \
| sed 's/Sling\/job/Sling\/job\/modules\/job/g' \
| sed 's/test_results_analyzer/lastCompletedBuild\/testReport/g' \
| sed -E 's/buildStatus.icon[^\/]+\/([^\/]+)\/([a-z]+)/job\/Sling\/job\/modules\/job\/\1\/job\/\2\/badge\/icon/g'