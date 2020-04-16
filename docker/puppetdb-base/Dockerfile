FROM ubuntu:18.04

ARG vcs_ref
ARG build_date
ARG version="6.0.0"
# used by entrypoint to submit metrics to Google Analytics;
# published images should use "production" for this build_arg
ARG pupperware_analytics_stream="dev"
ENV PUPPERWARE_ANALYTICS_ENABLED=false \
    UBUNTU_CODENAME="bionic" \
    DUMB_INIT_VERSION="1.2.1" \
    PUPPETDB_POSTGRES_HOSTNAME="postgres" \
    PUPPETDB_POSTGRES_PORT="5432" \
    PUPPETDB_POSTGRES_DATABASE="puppetdb" \
# NOTE: SSLDIR should never be set externally or it will break jetty.ini
    SSLDIR=/opt/puppetlabs/server/data/puppetdb/certs \
    LOGDIR=/opt/puppetlabs/server/data/puppetdb/logs \
    CERTNAME=puppetdb \
    PUPPETDB_USER=puppetdb \
    PUPPETDB_PASSWORD=puppetdb \
    PUPPETDB_NODE_TTL=7d \
    PUPPETDB_NODE_PURGE_TTL=14d \
    PUPPETDB_REPORT_TTL=14d \
    PUPPETDB_JAVA_ARGS="-Djava.net.preferIPv4Stack=true -Xms256m -Xmx256m" \
# used by entrypoint to determine if puppetserver should be contacted for config
# set to false when container tests are run
    USE_PUPPETSERVER=true \
    CONSUL_ENABLED=false \
    CONSUL_HOSTNAME=consul \
    CONSUL_PORT=8500 \
    NETWORK_INTERFACE=eth0

LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetdb" \
      org.label-schema.name="PuppetDB" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.version="$PUPPETDB_VERSION" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetdb" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/Dockerfile"

EXPOSE 8080 8081

ENTRYPOINT ["dumb-init", "/docker-entrypoint.sh"]
CMD ["foreground"]

# The start-period is just a wild guess how long it takes PuppetDB to come
# up in the worst case. The other timing parameters are set so that it
# takes at most a minute to realize that PuppetDB has failed.
# Probe failure during --start-period will not be counted towards the maximum number of retries
HEALTHCHECK --start-period=5m --interval=10s --timeout=10s --retries=6 CMD ["/healthcheck.sh"]

ADD https://raw.githubusercontent.com/puppetlabs/pupperware/8cdc056be09c44ed1ba96f963d71bd074d063b17/shared/ssl.sh \
    https://raw.githubusercontent.com/puppetlabs/wtfc/6aa5eef89728cc2903490a618430cc3e59216fa8/wtfc.sh \
    docker-entrypoint.sh \
    healthcheck.sh \
    /

COPY docker-entrypoint.d /docker-entrypoint.d

ENV PUPPERWARE_ANALYTICS_STREAM="$pupperware_analytics_stream" \
    PUPPETDB_VERSION="$version"

LABEL org.label-schema.version="$version" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date"

RUN apt-get update && \
    apt-get install --no-install-recommends -y wget ca-certificates curl dnsutils netcat && \
    chmod +x /ssl.sh /wtfc.sh /docker-entrypoint.sh /healthcheck.sh && \
    wget https://github.com/Yelp/dumb-init/releases/download/v"$DUMB_INIT_VERSION"/dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    dpkg -i dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# VOLUME definitions are always at end of Dockerfile to capture all written data
# Initially set this way for LCOW bug - https://github.com/moby/moby/issues/39892
# puppetdb data and generated certs
VOLUME /opt/puppetlabs/server/data/puppetdb

COPY Dockerfile /
