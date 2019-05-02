#!/bin/sh
#
# Wait on hosts to become available before proceeding
#
#
# Optional environment variables:
#   PUPPETDB_WAITFORHOST_SECONDS     Number of seconds to wait for host, defaults to 30
#   PUPPETDB_WAITFORPOSTGRES_SECONDS Number of seconds to wait on Postgres, defaults to 150
#   PUPPETDB_WAITFORHEALTH_SECONDS   Number of seconds to wait for health
#                                    checks of Consul / Puppetserver to succeed, defaults to 600

msg() {
    echo "($0) $1"
}

error() {
    msg "Error: $1"
    exit 1
}

wait_for_host_name_resolution() {
  # host and dig are in the bind-tools Alpine package
  # k8s nodes may not be reachable with a ping
  /wtfc.sh --timeout=$PUPPETDB_WAITFORHOST_SECONDS --interval=1 --progress host $1
  # additionally log the DNS lookup information for diagnostic purposes
  dig $1
  if [ $? -ne 0 ]; then
    error "dependent service at $1 cannot be resolved or contacted"
  fi
}

wait_for_host_port() {
  # -v verbose -w connect / final net read timeout -z scan and don't send data
  /wtfc.sh --timeout=${3:-$PUPPETDB_WAITFORHOST_SECONDS} --interval=1 --progress "nc -v -w 1 -z '${1}' ${2}"
  if [ $? -ne 0 ]; then
    error "host $1:$2 does not appear to be listening"
  fi
}

PUPPETDB_WAITFORHOST_SECONDS=${PUPPETDB_WAITFORHOST_SECONDS:-30}
PUPPETDB_WAITFORPOSTGRES_SECONDS=${PUPPETDB_WAITFORPOSTGRES_SECONDS:-150}
PUPPETDB_WAITFORHEALTH_SECONDS=${PUPPETDB_WAITFORHEALTH_SECONDS:-600}
PUPPETSERVER_HOSTNAME="${PUPPETSERVER_HOSTNAME:-puppet}"
CONSUL_HOSTNAME="${CONSUL_HOSTNAME:-consul}"
CONSUL_PORT="${CONSUL_PORT:-8500}"

wait_for_host_port "${PUPPETDB_POSTGRES_HOSTNAME:-postgres}" "${PUPPETDB_POSTGRES_PORT:-5432}" $PUPPETDB_WAITFORPOSTGRES_SECONDS

if [ "$USE_PUPPETSERVER" = true ]; then
  wait_for_host_name_resolution $PUPPETSERVER_HOSTNAME
  HEALTH_COMMAND="curl --silent --fail --insecure 'https://${PUPPETSERVER_HOSTNAME}:"${PUPPETSERVER_PORT:-8140}"/status/v1/simple' | grep -q '^running$'"
fi

if [ "$CONSUL_ENABLED" = "true" ]; then
  wait_for_host_name_resolution $CONSUL_HOSTNAME
  # with Consul enabled, wait on Consul instead of Puppetserver
  HEALTH_COMMAND="curl --silent --fail 'http://${CONSUL_HOSTNAME}:${CONSUL_PORT}/v1/health/checks/puppet' | grep -q '\\"\""Status"\\\"": \\"\""passing\\"\""'"
fi

if [ -n "$HEALTH_COMMAND" ]; then
  /wtfc.sh --timeout=$PUPPETDB_WAITFORHEALTH_SECONDS --interval=1 --progress "$HEALTH_COMMAND"
  if [ $? -ne 0 ]; then
    error "Required health check failed"
  fi
fi
