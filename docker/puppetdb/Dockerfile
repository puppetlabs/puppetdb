ARG version="6.0.0"
ARG namespace="puppet"
FROM ubuntu:18.04 as build
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
            language-pack-en \
            openjdk-8-jdk-headless \
            curl \
            git \
            ruby \
            ruby-dev \
            g++ \
            make && \
    git config --global user.name "Puppet Release Team" && \
    git config --global user.email "release@puppet.com" && \
    curl --output /usr/local/bin/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    chmod 0755 /usr/local/bin/lein && \
    /usr/local/bin/lein && \
    gem install --no-doc bundler fpm

COPY . /puppetdb
# manually copy over bootstrap.cfg to work around symlink issues on windows
COPY resources/puppetlabs/puppetdb/bootstrap.cfg /puppetdb/resources/ext/config/bootstrap.cfg
WORKDIR /puppetdb
RUN lein clean && lein install && \
    EZBAKE_ALLOW_UNREPRODUCIBLE_BUILDS=true EZBAKE_NODEPLOY=true COW=base-bionic-amd64.cow MOCK='' GEM_SOURCE=https://rubygems.org lein with-profile ezbake ezbake local-build && \
    mv /puppetdb/output/deb/bionic/*/*.deb /puppetdb.deb


FROM "$namespace"/puppetdb-base:"$version"

LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetdb" \
      org.label-schema.name="PuppetDB" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetdb" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/Dockerfile"

COPY --from=build /puppetdb.deb /puppetdb.deb

LABEL org.label-schema.version="$version" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date"

RUN wget http://nightlies.puppet.com/apt/puppet6-nightly-release-"$UBUNTU_CODENAME".deb && \
    dpkg -i puppet6-nightly-release-"$UBUNTU_CODENAME".deb && \
    rm puppet6-nightly-release-"$UBUNTU_CODENAME".deb && \
    apt-get update && \
    apt-get install --no-install-recommends -y /puppetdb.deb && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /opt/puppetlabs/server/data/puppetdb/logs && \
# We want to use the HOCON database.conf and config.conf files, so get rid
# of the packaged files
    rm -f /etc/puppetlabs/puppetdb/conf.d/database.ini && \
    rm -f /etc/puppetlabs/puppetdb/conf.d/config.ini

COPY docker/puppetdb/logback.xml \
     docker/puppetdb/request-logging.xml \
     /etc/puppetlabs/puppetdb/
COPY docker/puppetdb/conf.d /etc/puppetlabs/puppetdb/conf.d/

COPY docker/puppetdb/Dockerfile /
