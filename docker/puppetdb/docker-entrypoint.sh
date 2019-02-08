#!/bin/sh

set -e

for f in /docker-entrypoint.d/*.sh; do
    echo "Running $f"
    chmod +x "$f"
    "$f"
done

on_exit() {
    status=$?
    for f in /docker-exit.d/*.sh; do
        echo "Running $f"
        chmod +x "$f"
        "$f"
    done
    # Don't call ourselves when we exit below
    trap '' EXIT
    exit $status
}

# Shutdown hook
trap on_exit EXIT SIGINT SIGTERM

/sbin/su-exec root java $PUPPETDB_JAVA_ARGS -cp /puppetdb.jar \
    clojure.main -m puppetlabs.puppetdb.core "$@" \
    -c /etc/puppetlabs/puppetdb/conf.d/
