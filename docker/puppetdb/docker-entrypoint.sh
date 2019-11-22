#!/bin/sh

set -e

chmod +x /docker-entrypoint.d/*.sh
# sync prevents aufs from sometimes returning EBUSY if you exec right after a chmod
sync
for f in /docker-entrypoint.d/*.sh; do
    echo "Running $f"
    "$f"
done

if [ "$IS_RELEASE" = "true" ]; then
  exec /opt/puppetlabs/bin/puppetdb "$@"
else
  exec java $PUPPETDB_JAVA_ARGS -cp /puppetdb.jar \
       clojure.main -m puppetlabs.puppetdb.core "$@" \
       -c /etc/puppetlabs/puppetdb/conf.d/
fi
