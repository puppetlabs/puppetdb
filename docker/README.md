# [puppetlabs/puppetdb](https://github.com/puppetlabs/puppetdb)

The Dockerfile for this image is available in the PuppetDB repository
[here][1].

The PuppetDB container requires a working postgres container or other suitably
configured PostgreSQL database. With that in place, you can run PuppetDB like
so:

    docker run --link postgres:postgres --link puppet:puppet puppet/puppetdb

You can change configuration settings by mounting volumes containing
configuration files or by using this image as a base image. For the defaults,
see the [Dockerfile and supporting folders][2].

For more details about PuppetDB, see the [official documentation][3].

See the [pupperware repository][4] for how to run a full Puppet stack using
Docker Compose.

## Configuration

| Name                                    | Usage / Default                                                                                                                                                                    |
|-----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **CERTNAME**                            | The DNS name used on this services SSL certificate<br><br>`puppetdb`                                                                                                               |
| **DNS_ALT_NAMES**                       | Additional DNS names to add to the services SSL certificate<br><br>Unset                                                                                                           |
| **WAITFORCERT**                         | Number of seconds to wait for certificate to be signed<br><br>`120`                                                                                                                |
| **USE_PUPPETSERVER**                    | Set to `false` to skip acquiring SSL certificates from a Puppet Server.<br><br>`true`                                                                                              |
| **PUPPETSERVER_HOSTNAME**               | The DNS hostname of the puppet master<br><br>`puppet`                                                                                                                              |
| **PUPPETSERVER_PORT**                   | The port of the puppet master<br><br>`8140`                                                                                                                                        |
| **PUPPETDB_POSTGRES_HOSTNAME**          | The DNS hostname of the postgres service<br><br>`postgres`                                                                                                                         |
| **PUPPETDB_POSTGRES_PORT**              | The port for postgres<br><br>`5432`                                                                                                                                                |
| **PUPPETDB_POSTGRES_DATABASE**          | The name of the puppetdb database in postgres<br><br>`puppetdb`                                                                                                                    |
| **PUPPETDB_USER**                       | The puppetdb database user<br><br>`puppetdb`                                                                                                                                       |
| **PUPPETDB_PASSWORD**                   | The puppetdb database password<br><br>`puppetdb`                                                                                                                                   |
| **PUPPETDB_NODE_TTL**                   | Mark as ‘expired’ nodes that haven’t seen any activity (no new catalogs, facts, or reports) in the specified amount of time<br><br>`7d`                                            |
| **PUPPETDB_NODE_PURGE_TTL**             | Automatically delete nodes that have been deactivated or expired for the specified amount of time<br><br>`14d`                                                                     |
| **PUPPETDB_REPORT_TTL**                 | Automatically delete reports that are older than the specified amount of time<br><br>`14d`                                                                                         |
| **PUPPETDB_JAVA_ARGS**                  | Arguments passed directly to the JVM when starting the service<br><br>`-Djava.net.preferIPv4Stack=true -Xms256m -Xmx256m -XX:+UseParallelGC -Xlog:gc*:file=/opt/puppetlabs/server/data/puppetdb/logs/puppetdb_gc.log::filecount=16,filesize=65536 -Djdk.tls.ephemeralDHKeySize=2048` |
| **PUPPERWARE_ANALYTICS_ENABLED**        | Set to 'true' to enable Google Analytics.<br><br>`false`                                                                                                                           |

### Cert File Locations

The directory structure follows the following conventions.  The full path is always available inside the container as the environment variable `$SSLDIR`

- 'ssl-ca-cert'
  `/opt/puppetlabs/server/data/puppetdb/certs/certs/ca.pem`

- 'ssl-cert'
  `/opt/puppetlabs/server/data/puppetdb/certs/certs/<certname>.pem`

- 'ssl-key'
  `/opt/puppetlabs/server/data/puppetdb/certs/private_keys/<certname>.pem`

## Analytics Data Collection

 The puppetdb container collects usage data. This is disabled by default. You can enable it by passing `--env PUPPERWARE_ANALYTICS_ENABLED=true`
to your `docker run` command.

### What data is collected?
* Version of the puppetdb container.
* Anonymized IP address is used by Google Analytics for Geolocation data, but the IP address is not collected.

### Why does the puppetdb container collect data?

 We collect data to help us understand how the containers are used and make decisions about upcoming changes.

### How can I opt out of puppetdb container data collection?

 This is disabled by default.


[1]: https://github.com/puppetlabs/puppetdb/blob/master/docker/puppetdb/Dockerfile
[2]: https://github.com/puppetlabs/puppetdb/tree/master/docker
[3]: https://puppet.com/docs/puppetdb/latest/index.html
[4]: https://github.com/puppetlabs/pupperware
