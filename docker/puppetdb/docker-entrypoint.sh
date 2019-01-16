#!/bin/sh

set -e

for f in /docker-entrypoint.d/*.sh; do
    echo "Running $f"
    chmod +x "$f"
    "$f"
done

exec java $PUPPETDB_JAVA_ARGS -cp /puppetdb.jar \
    clojure.main -m puppetlabs.puppetdb.core "$@" \
    -c /etc/puppetlabs/puppetdb/conf.d/
