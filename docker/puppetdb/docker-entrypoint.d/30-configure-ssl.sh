#!/bin/sh

if [ ! -f "/etc/puppetlabs/puppet/ssl/certs/${HOSTNAME}.pem" ] && [ "$USE_PUPPETSERVER" = true ]; then
  set -e
  /ssl.sh
fi
if [ ! -d "/etc/puppetlabs/puppetdb/ssl" ] && [ "$USE_PUPPETSERVER" = true ]; then
  /ssl-setup.sh -f
fi
