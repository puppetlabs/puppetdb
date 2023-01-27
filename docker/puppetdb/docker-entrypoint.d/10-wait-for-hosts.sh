#!/bin/sh
#
# Wait on hosts to become available before proceeding
#
#
# Optional environment variables:
#   PUPPETDB_WAITFORHOST_SECONDS     Number of seconds to wait for DNS names of
#                                    Postgres and Puppetserver to resolve, defaults to 30
#   PUPPETDB_WAITFORHEALTH_SECONDS   Number of seconds to wait for health
#                                    checks of Puppetserver to succeed, defaults to 360
#                                    to match puppetserver healthcheck max wait
#   PUPPETDB_WAITFORPOSTGRES_SECONDS Additional number of seconds to wait on Postgres,
#                                    after PuppetServer is healthy, defaults to 60
#   PUPPETDB_POSTGRES_HOSTNAME       Specified in Dockerfile, defaults to postgres
#   PUPPETSERVER_HOSTNAME            DNS name of puppetserver to wait on, defaults to puppet


msg() {
    echo "($0) $1"
}

error() {
    msg "Error: $1"
    exit 1
}

# Alpine as high as 3.9 seems to have failures reaching addresses sporadically
# In local repro scenarios, performing a DNS lookup with dig increases reliability
wait_for_host_name_resolution() {
  # host and dig are in the bind-tools Alpine package
  # k8s nodes may not be reachable with a ping
  # performing a dig prior to a host may help prime the cache in Alpine
  # https://github.com/Microsoft/opengcs/issues/303
  /wtfc.sh --timeout="${2}" --interval=1 --progress "dig $1 && host -t A $1"
  # additionally log the DNS lookup information for diagnostic purposes
  NAME_RESOLVED=$?
  dig $1
  if [ $NAME_RESOLVED -ne 0 ]; then
    error "dependent service at $1 cannot be resolved or contacted"
  fi
}

wait_for_host_port() {
  # -v verbose -w connect / final net read timeout -z scan and don't send data
  /wtfc.sh --timeout=${3} --interval=1 --progress "nc -v -w 1 -z '${1}' ${2}"
  if [ $? -ne 0 ]; then
    error "host $1:$2 does not appear to be listening"
  fi
}

PUPPETDB_WAITFORHOST_SECONDS=${PUPPETDB_WAITFORHOST_SECONDS:-30}
PUPPETDB_WAITFORPOSTGRES_SECONDS=${PUPPETDB_WAITFORPOSTGRES_SECONDS:-60}
PUPPETDB_WAITFORHEALTH_SECONDS=${PUPPETDB_WAITFORHEALTH_SECONDS:-360}
PUPPETDB_POSTGRES_HOSTNAME="${PUPPETDB_POSTGRES_HOSTNAME:-postgres}"
PUPPETSERVER_HOSTNAME="${PUPPETSERVER_HOSTNAME:-puppet}"

# wait for postgres DNS
wait_for_host_name_resolution $PUPPETDB_POSTGRES_HOSTNAME $PUPPETDB_WAITFORHOST_SECONDS

# wait for puppetserver DNS, then healthcheck
if [ "$USE_PUPPETSERVER" = true ]; then
  wait_for_host_name_resolution $PUPPETSERVER_HOSTNAME $PUPPETDB_WAITFORHOST_SECONDS
  HEALTH_COMMAND="curl --silent --fail --insecure 'https://${PUPPETSERVER_HOSTNAME}:"${PUPPETSERVER_PORT:-8140}"/status/v1/simple' | grep -q '^running$'"
fi

if [ -n "$HEALTH_COMMAND" ]; then
  /wtfc.sh --timeout=$PUPPETDB_WAITFORHEALTH_SECONDS --interval=1 --progress "$HEALTH_COMMAND"
  if [ $? -ne 0 ]; then
    error "Required health check failed"
  fi
fi

# wait for postgres
wait_for_host_port $PUPPETDB_POSTGRES_HOSTNAME "${PUPPETDB_POSTGRES_PORT:-5432}" $PUPPETDB_WAITFORPOSTGRES_SECONDS
