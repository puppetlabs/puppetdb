#!/bin/sh
#
# Wait on hosts to become available before proceeding
#
#
# Optional environment variables:
#   PUPPETDB_WAITFORHOST_SECONDS    Number of seconds to wait for host, defaults to 30

msg() {
    echo "($0) $1"
}

error() {
    msg "Error: $1"
    exit 1
}

wait_for_host() {
  /wtfc.sh --timeout=$PUPPETDB_WAITFORHOST_SECONDS --interval=1 --progress ping -c1 -W1 $1
  if [ $? -ne 0 ]; then
    error "dependent service at $1 cannot be resolved or contacted"
  fi
}

PUPPETDB_WAITFORHOST_SECONDS=${PUPPETDB_WAITFORHOST_SECONDS:-30}

wait_for_host "postgres"

if [ "$USE_PUPPETSERVER" = true ]; then
  wait_for_host "${PUPPETSERVER_HOSTNAME:-puppet}"
fi

if [ "$CONSUL_ENABLED" = "true" ]; then
  wait_for_host "${CONSUL_HOSTNAME:-consul}"
fi
