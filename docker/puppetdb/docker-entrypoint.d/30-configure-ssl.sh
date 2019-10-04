#!/bin/sh

# when certs need to be generated and haven't been user supplied
if [ ! -f "${SSLDIR}/certs/${CERTNAME}.pem" ] && [ "$USE_PUPPETSERVER" = true ]; then
  set -e

  # Only pass in extra DNS_ALT_NAMES if DNS_ALT_NAMES is already set
  #
  # This will help preserve backwards compatibility for container upgrades
  if [ -n "${DNS_ALT_NAMES}" ]; then
    DNS_ALT_NAMES="${HOSTNAME},$(hostname -s),$(hostname -f),${DNS_ALT_NAMES}"
  fi
  /ssl.sh "$CERTNAME"
fi

# cert files are present from Puppetserver OR have been user supplied
if [ -f "${SSLDIR}/private_keys/${CERTNAME}.pem" ]; then
  openssl pkcs8 -inform PEM -outform DER -in ${SSLDIR}/private_keys/${CERTNAME}.pem -topk8 -nocrypt -out ${SSLDIR}/private_keys/${CERTNAME}.pk8

  # for Jetty to use statically named files
  ln -s -f ${SSLDIR}/private_keys/${CERTNAME}.pem ${SSLDIR}/private_keys/private.pem
  ln -s -f ${SSLDIR}/certs/${CERTNAME}.pem ${SSLDIR}/certs/public.pem

  # enable SSL in Jetty
  sed -i '/^# ssl-/s/^# //g' /etc/puppetlabs/puppetdb/conf.d/jetty.ini

  # make sure Java apps running as puppetdb can read these files
  chown -R puppetdb:puppetdb ${SSLDIR}

  # and that they're further restricted
  chmod u=rwx,g=,o= ${SSLDIR}/private_keys
  chmod u=rw,g=,o= ${SSLDIR}/private_keys/*

  chmod u=rwx,g=,o= ${SSLDIR}/certs
  chmod u=rw,g=,o= ${SSLDIR}/certs/*
fi
