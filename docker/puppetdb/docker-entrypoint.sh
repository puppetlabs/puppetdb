#!/bin/bash

master_running() {
    # This netcat call doesn't work when a load balancer is in place as it just
    # detects that the LB is up (see PDB-4192)
    nc -z "$PUPPETSERVER_HOSTNAME" 8140
}

PUPPETSERVER_HOSTNAME="${PUPPETSERVER_HOSTNAME:-puppet}"
if [ ! -d "/etc/puppetlabs/puppetdb/ssl" ] && [ "$USE_PUPPETSERVER" = true ]; then
  while ! master_running; do
    sleep 1
  done
  set -e
  /opt/puppetlabs/bin/puppet config set certname "$HOSTNAME"
  /opt/puppetlabs/bin/puppet config set server "$PUPPETSERVER_HOSTNAME"
  /opt/puppetlabs/bin/puppet agent --verbose --onetime --no-daemonize --waitforcert 120
  /opt/puppetlabs/server/bin/puppetdb ssl-setup -f
fi

exec /opt/puppetlabs/server/bin/puppetdb "$@"
