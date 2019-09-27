FROM clojure:openjdk-8-lein AS builder
RUN apt-get update && \
    apt-get install -y --no-install-recommends make
# Install only dependencies
WORKDIR /app
COPY project.clj /app/
COPY resources/puppetlabs/puppetdb/bootstrap.cfg \
     /app/resources/puppetlabs/puppetdb/bootstrap.cfg
RUN lein with-profile uberjar deps
# Build uberjar -- see .dockerignore
COPY . /app
RUN lein with-profile uberjar uberjar


FROM openjdk:8-jre-slim-buster
RUN apt-get update && \
    apt-get install -y --no-install-recommends tini curl openssl dnsutils netcat && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/puppetdb.jar /

ARG vcs_ref
ARG build_date
ARG version="6.0.0"
# used by entrypoint to submit metrics to Google Analytics;
# published images should use "production" for this build_arg
ARG pupperware_analytics_stream="dev"
ENV PUPPERWARE_ANALYTICS_STREAM="$pupperware_analytics_stream"
ENV PUPPERWARE_ANALYTICS_ENABLED=false

ENV PUPPETDB_VERSION="$version"
ENV PUPPETDB_POSTGRES_HOSTNAME="postgres"
ENV PUPPETDB_POSTGRES_PORT="5432"
ENV PUPPETDB_POSTGRES_DATABASE="puppetdb"
# NOTE: SSLDIR should never be set externally or it will break jetty.ini
ENV SSLDIR=/opt/puppetlabs/server/data/puppetdb/certs
ENV CERTNAME=puppetdb
ENV PUPPETDB_USER=puppetdb
ENV PUPPETDB_PASSWORD=puppetdb
ENV PUPPETDB_NODE_TTL=7d
ENV PUPPETDB_NODE_PURGE_TTL=14d
ENV PUPPETDB_REPORT_TTL=14d
ENV PUPPETDB_JAVA_ARGS="-Djava.net.preferIPv4Stack=true -Xms256m -Xmx256m"
# used by entrypoint to determine if puppetserver should be contacted for config
# set to false when container tests are run
ENV USE_PUPPETSERVER=true
ENV CONSUL_ENABLED=false
ENV CONSUL_HOSTNAME=consul
ENV CONSUL_PORT=8500
ENV NETWORK_INTERFACE=eth0

LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetdb" \
      org.label-schema.name="PuppetDB" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.version="$PUPPETDB_VERSION" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetdb" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/Dockerfile"

# Values from /etc/default/puppetdb
ENV JAVA_BIN="/usr/bin/java"
ENV USER="puppetdb"
ENV GROUP="puppetdb"
ENV INSTALL_DIR="/opt/puppetlabs/server/apps/puppetdb"
ENV CONFIG="/etc/puppetlabs/puppetdb/conf.d"
ENV BOOTSTRAP_CONFIG="/etc/puppetlabs/puppetdb/bootstrap.cfg"
ENV SERVICE_STOP_RETRIES=60

COPY docker/puppetdb/logback.xml /etc/puppetlabs/puppetdb/logback.xml
COPY docker/puppetdb/request-logging.xml /etc/puppetlabs/puppetdb/request-logging.xml
COPY docker/puppetdb/conf.d /etc/puppetlabs/puppetdb/conf.d/
COPY resources/puppetlabs/puppetdb/bootstrap.cfg /etc/puppetlabs/puppetdb/
RUN mkdir -p /opt/puppetlabs/server/data/puppetdb/logs

RUN addgroup $GROUP && adduser --system $USER --ingroup $GROUP

ADD https://raw.githubusercontent.com/puppetlabs/pupperware/8cdc056be09c44ed1ba96f963d71bd074d063b17/shared/ssl.sh /ssl.sh
RUN chmod +x /ssl.sh

ADD https://raw.githubusercontent.com/puppetlabs/wtfc/6aa5eef89728cc2903490a618430cc3e59216fa8/wtfc.sh /wtfc.sh
RUN chmod +x /wtfc.sh
COPY docker/puppetdb/docker-entrypoint.sh /
RUN chmod +x /docker-entrypoint.sh
COPY docker/puppetdb/docker-entrypoint.d /docker-entrypoint.d

EXPOSE 8080 8081

ENTRYPOINT ["/usr/bin/tini", "-g", "--", "/docker-entrypoint.sh"]
CMD ["services"]

COPY docker/puppetdb/healthcheck.sh /
RUN chmod +x /healthcheck.sh
# The start-period is just a wild guess how long it takes PuppetDB to come
# up in the worst case. The other timing parameters are set so that it
# takes at most a minute to realize that PuppetDB has failed.
# Probe failure during --start-period will not be counted towards the maximum number of retries
HEALTHCHECK --start-period=5m --interval=10s --timeout=10s --retries=6 CMD ["/healthcheck.sh"]

# VOLUME definitions are always at end of Dockerfile to address an LCOW bug
# https://github.com/moby/moby/issues/39892
# puppetdb data and generated certs
VOLUME /opt/puppetlabs/server/data/puppetdb

COPY docker/puppetdb/Dockerfile /
