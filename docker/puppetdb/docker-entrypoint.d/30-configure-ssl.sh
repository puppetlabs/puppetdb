#!/bin/sh
if [ ! -f "/etc/puppetlabs/puppet/ssl/certs/${HOSTNAME}.pem" ] && [ "$USE_PUPPETSERVER" = true ]; then
  # if this is our first run, run puppet agent to get certs in place
  set -e
  /ssl.sh
fi
if [ ! -d "/etc/puppetlabs/puppetdb/ssl" ] && [ "$USE_PUPPETSERVER" = true ]; then
  /ssl-setup.sh -f
fi
