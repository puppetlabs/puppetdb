#!/usr/bin/env sh

set -x
set -e

curl --fail \
--resolve "puppetdb:8080:127.0.0.1" \
"http://puppetdb:8080/status/v1/services/puppetdb-status" \
|  grep -q '"state":"running"' \
|| exit 1
