#!/bin/sh

if [ ! -f "/etc/puppetlabs/puppet/ssl/certs/${HOSTNAME}.pem" ] && [ "$USE_PUPPETSERVER" = true ]; then
  set -e
  DNS_ALT_NAMES="${DNS_ALT_NAMES}" /ssl.sh "$HOSTNAME"
fi
if [ ! -d "/etc/puppetlabs/puppetdb/ssl" ] && [ "$USE_PUPPETSERVER" = true ]; then
  /ssl-setup.sh -f
fi
