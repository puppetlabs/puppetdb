#!/bin/sh

if [ "$CONSUL_ENABLED" = "true" ]; then
  ipaddress="$(ifconfig $NETWORK_INTERFACE | grep 'inet' | tr -s ' ' | cut -d ' ' -f 3)"

  cat <<SERVICEDEF > /puppet-service.json
{
  "name": "puppetdb",
  "id": "$HOSTNAME",
  "port": 8080,
  "address": "$ipaddress",
  "checks": [
    {
      "http": "http://$HOSTNAME:8080/status/v1/services/puppetdb-status",
      "interval": "10s",
      "deregister_critical_service_after": "10m"
    }
  ]
}
SERVICEDEF

  curl \
    --request PUT \
    --data @puppet-service.json \
    http://$CONSUL_HOSTNAME:$CONSUL_PORT/v1/agent/service/register
fi
