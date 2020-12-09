#!/bin/sh

# when certs need to be generated and haven't been user supplied
if [ ! -f "${SSLDIR}/certs/${CERTNAME}.pem" ] && [ "$USE_PUPPETSERVER" = true ]; then
  set -e

  # previously DNS_ALT_NAMES was omitted if empty, but ssl.sh already skips setting when empty
  DNS_ALT_NAMES="${DNS_ALT_NAMES}" CERTNAME="$CERTNAME" /ssl.sh
fi

# cert files are present from Puppetserver OR have been user supplied
if [ -f "${SSLDIR}/private_keys/${CERTNAME}.pem" ]; then
  openssl pkcs8 -inform PEM -outform DER -in ${SSLDIR}/private_keys/${CERTNAME}.pem -topk8 -nocrypt -out ${SSLDIR}/private_keys/${CERTNAME}.pk8

  # enable SSL in Jetty
  sed -i '/^# ssl-/s/^# //g' /etc/puppetlabs/puppetdb/conf.d/jetty.ini

  # make sure Java apps running as puppetdb can read these files
  echo "Setting ownership for $SSLDIR to puppetdb:puppetdb"
  chown -R puppetdb:puppetdb ${SSLDIR}

  # and that they're further restricted
  chmod u=rwx,g=,o= ${SSLDIR}/private_keys
  chmod u=rw,g=,o= ${SSLDIR}/private_keys/*

  chmod u=rwx,g=,o= ${SSLDIR}/certs
  chmod u=rw,g=,o= ${SSLDIR}/certs/*
fi
