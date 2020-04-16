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

ARG vcs_ref
ARG build_date
ARG version="6.0.0"
# used by entrypoint to submit metrics to Google Analytics;
# published images should use "production" for this build_arg
ARG pupperware_analytics_stream="dev"

LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetdb" \
      org.label-schema.name="PuppetDB" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetdb" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/Dockerfile"

ENV PUPPERWARE_ANALYTICS_ENABLED=false \
    PUPPETDB_POSTGRES_HOSTNAME="postgres" \
    PUPPETDB_POSTGRES_PORT="5432" \
    PUPPETDB_POSTGRES_DATABASE="puppetdb" \
    LOGDIR=/opt/puppetlabs/server/data/puppetdb/logs \
# NOTE: SSLDIR should never be set externally or it will break jetty.ini
    SSLDIR=/opt/puppetlabs/server/data/puppetdb/certs \
    CERTNAME=puppetdb \
    PUPPETDB_USER=puppetdb \
    PUPPETDB_PASSWORD=puppetdb \
    PUPPETDB_NODE_TTL=7d \
    PUPPETDB_NODE_PURGE_TTL=14d \
    PUPPETDB_REPORT_TTL=14d \
# NOTE: Docker ENV values cannot consume other ENV values, so full path to $LOGDIR is specified for -Xloggc
# this value may be set by users, keeping in mind that some of these values are mandatory
# -Djavax.net.debug=ssl may be particularly useful to set for debugging SSL
    PUPPETDB_JAVA_ARGS="-Xms256m -Xmx256m -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:/opt/puppetlabs/server/data/puppetdb/logs/puppetdb_gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=16 -XX:GCLogFileSize=64m" \
# used by entrypoint to determine if puppetserver should be contacted for config
# set to false when container tests are run
    USE_PUPPETSERVER=true \
    CONSUL_ENABLED=false \
    CONSUL_HOSTNAME=consul \
    CONSUL_PORT=8500 \
    NETWORK_INTERFACE=eth0 \
# Values from /etc/default/puppetdb
    JAVA_BIN="/usr/bin/java" \
    USER="puppetdb" \
    GROUP="puppetdb" \
    INSTALL_DIR="/opt/puppetlabs/server/apps/puppetdb" \
    CONFIG="/etc/puppetlabs/puppetdb/conf.d" \
    BOOTSTRAP_CONFIG="/etc/puppetlabs/puppetdb/bootstrap.cfg" \
    SERVICE_STOP_RETRIES=60

EXPOSE 8080 8081

ENTRYPOINT ["/usr/bin/tini", "-g", "--", "/docker-entrypoint.sh"]
CMD ["services"]

COPY docker/puppetdb/logback.xml \
     docker/puppetdb/request-logging.xml \
     resources/puppetlabs/puppetdb/bootstrap.cfg \
     /etc/puppetlabs/puppetdb/
COPY docker/puppetdb/conf.d /etc/puppetlabs/puppetdb/conf.d/
COPY docker/puppetdb/docker-entrypoint.d /docker-entrypoint.d

# The start-period describes how long it takes PuppetDB to come
# up in the worst case. The other timing parameters are set so that it
# takes at most 2 minutes to realize that PuppetDB has failed.
# Probe failure during --start-period will not be counted towards the maximum number of retries
HEALTHCHECK --start-period=3m --interval=10s --timeout=10s --retries=12 CMD ["/healthcheck.sh"]

ADD https://raw.githubusercontent.com/puppetlabs/pupperware/8cdc056be09c44ed1ba96f963d71bd074d063b17/shared/ssl.sh \
    https://raw.githubusercontent.com/puppetlabs/wtfc/6aa5eef89728cc2903490a618430cc3e59216fa8/wtfc.sh \
    docker/puppetdb/docker-entrypoint.sh \
    docker/puppetdb/healthcheck.sh \
    /

COPY --from=builder /app/target/puppetdb.jar /

# dyanmic LABELs and ENV vars placed lower for the sake of Docker layer caching
ENV PUPPERWARE_ANALYTICS_STREAM="$pupperware_analytics_stream" \
    PUPPETDB_VERSION="$version"

LABEL org.label-schema.version="$version" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date"

RUN mkdir -p $LOGDIR && \
    addgroup $GROUP && adduser --system $USER --ingroup $GROUP && \
    chmod +x /ssl.sh /wtfc.sh /docker-entrypoint.sh /healthcheck.sh && \
    apt-get update && \
    apt-get install -y --no-install-recommends tini curl openssl dnsutils netcat net-tools && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# VOLUME definitions are always at end of Dockerfile to address an LCOW bug
# https://github.com/moby/moby/issues/39892
# puppetdb data and generated certs
VOLUME /opt/puppetlabs/server/data/puppetdb

COPY docker/puppetdb/Dockerfile /
