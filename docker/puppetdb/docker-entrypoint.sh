#!/bin/bash

master_running() {
    curl -sf "http://${PUPPETSERVER_HOSTNAME}:8140/production/status/test" \
        | grep -q '"is_alive":true'
}

PUPPETSERVER_HOSTNAME="${PUPPETSERVER_HOSTNAME:-puppet}"
if [ ! -d "/etc/puppetlabs/puppetdb/ssl" ] && [ "$USE_PUPPETSERVER" = true ]; then
  while ! master_running; do
    sleep 1
  done
  set -e
  puppet config set certname "$HOSTNAME"
  puppet config set server "$PUPPETSERVER_HOSTNAME"
  puppet agent --verbose --onetime --no-daemonize --waitforcert 120
  # /opt/puppetlabs/server/bin/puppetdb ssl-setup -f
  /ssl-setup.sh -f
fi

# exec /opt/puppetlabs/server/bin/puppetdb "$@"
exec java -cp /puppetdb.jar clojure.main -m puppetlabs.puppetdb.core "$@" \
    -c /etc/puppetlabs/puppetdb/conf.d/
