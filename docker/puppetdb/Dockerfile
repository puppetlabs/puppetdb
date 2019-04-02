FROM clojure:lein-alpine AS builder
RUN apk add --no-cache make
# Install only dependencies
WORKDIR /app
COPY project.clj /app/
COPY resources/puppetlabs/puppetdb/bootstrap.cfg \
     /app/resources/puppetlabs/puppetdb/bootstrap.cfg
RUN lein with-profile uberjar deps
# Build uberjar -- see .dockerignore
COPY . /app
RUN lein with-profile uberjar uberjar


FROM openjdk:8-jre-alpine
RUN apk add --no-cache tini curl openssl
COPY --from=builder /app/target/puppetdb.jar /

ARG vcs_ref
ARG build_date
ARG version="6.0.0"
# used by entrypoint to submit metrics to Google Analytics;
# published images should use "production" for this build_arg
ARG pupperware_analytics_stream="dev"
ENV PUPPERWARE_ANALYTICS_STREAM="$pupperware_analytics_stream"

ENV PUPPETDB_VERSION="$version"
ENV PUPPETDB_DATABASE_CONNECTION="//postgres:5432/puppetdb"
ENV LOGDIR=/opt/puppetlabs/server/data/puppetdb/logs
ENV PUPPETDB_USER=puppetdb
ENV PUPPETDB_PASSWORD=puppetdb
ENV PUPPETDB_NODE_TTL=7d
ENV PUPPETDB_NODE_PURGE_TTL=14d
ENV PUPPETDB_REPORT_TTL=14d
# NOTE: Docker ENV values cannot consume other ENV values, so full path to $LOGDIR is specified for -Xloggc
# this value may be set by users, keeping in mind that some of these values are mandatory
# -Djavax.net.debug=ssl may be particularly useful to set for debugging SSL
ENV PUPPETDB_JAVA_ARGS="-Djava.net.preferIPv4Stack=true -Xms256m -Xmx256m -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:/opt/puppetlabs/server/data/puppetdb/logs/puppetdb_gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=16 -XX:GCLogFileSize=64m"
# used by entrypoint to determine if puppetserver should be contacted for config
# set to false when container tests are run
ENV USE_PUPPETSERVER=true

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

COPY docker/puppetdb/logging /etc/puppetlabs/puppetdb/logging/
COPY docker/puppetdb/conf.d /etc/puppetlabs/puppetdb/conf.d/
COPY resources/puppetlabs/puppetdb/bootstrap.cfg /etc/puppetlabs/puppetdb/
RUN mkdir -p $LOGDIR

RUN addgroup $GROUP && adduser -S $USER -G $GROUP

ADD https://raw.githubusercontent.com/puppetlabs/pupperware/2630075434af829489b5fb3e01039701ed6fe752/shared/ssl.sh /ssl.sh
RUN chmod +x /ssl.sh
COPY docker/puppetdb/ssl-setup.sh /
RUN chmod +x /ssl-setup.sh

VOLUME /etc/puppetlabs/puppet/ssl/

ADD https://raw.githubusercontent.com/puppetlabs/wtfc/4dbbc91eb0d89c48e3494c1fab37c74c6b13178c/wtfc.sh /wtfc.sh
RUN chmod +x /wtfc.sh
COPY docker/puppetdb/docker-entrypoint.sh /
RUN chmod +x /docker-entrypoint.sh
COPY docker/puppetdb/docker-entrypoint.d /docker-entrypoint.d

EXPOSE 8080 8081

ENTRYPOINT ["/sbin/tini", "-g", "--", "/docker-entrypoint.sh"]
CMD ["services"]

# The start-period is just a wild guess how long it takes PuppetDB to come
# up in the worst case. The other timing parameters are set so that it
# takes at most a minute to realize that PuppetDB has failed.
HEALTHCHECK --interval=10s --timeout=10s --retries=6 \
  --start-period=5m CMD \
  curl --fail --silent \
  --resolve 'puppetdb:8080:127.0.0.1' \
  http://puppetdb:8080/status/v1/services/puppetdb-status \
  | grep -q '"state":"running"' \
  || exit 1

COPY docker/puppetdb/Dockerfile /
