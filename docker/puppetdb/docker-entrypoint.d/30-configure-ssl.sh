#!/bin/sh

if [ ! -f "${SSLDIR}/certs/${CERTNAME}.pem" ] && [ "$USE_PUPPETSERVER" = true ]; then
  set -e

  DNS_ALT_NAMES="${HOSTNAME},${DNS_ALT_NAMES}" /ssl.sh "$CERTNAME"
fi
if [ ! -d "/etc/puppetlabs/puppetdb/ssl" ] && [ "$USE_PUPPETSERVER" = true ]; then
  /ssl-setup.sh -f
fi
