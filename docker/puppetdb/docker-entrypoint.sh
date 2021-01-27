#!/bin/bash
# bash is required to pass ENV vars with dots as sh cannot

set -e

. /etc/puppetlabs/puppetdb/conf.d/.dockerenv

chmod +x /docker-entrypoint.d/*.sh
# sync prevents aufs from sometimes returning EBUSY if you exec right after a chmod
sync
for f in /docker-entrypoint.d/*.sh; do
    echo "Running $f"
    "$f"
done

exec /opt/puppetlabs/bin/puppetdb "$@"
