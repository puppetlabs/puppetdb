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

The following environment variables are supported:

- `PUPPERWARE_ANALYTICS_ENABLED`

  Set to 'true' to enable Google Analytics. Defaults to 'false'.

- `USE_PUPPETSERVER`

  Set to 'false' to skip acquiring SSL certificates from a Puppet Server. Defaults to 'true'.

- `PUPPETDB_DATABASE_CONNECTION`

  The value for the 'subname' field in puppetdb/conf.d/database.conf. Defaults to '//postgres:5432/puppetdb'.

- `PUPPETDB_USER`

  The user to connect to the postgres database as. Defaults to 'puppetdb'.

- `PUPPETDB_PASSWORD`

  The password to connect to the postgres database with. Defaults to 'puppetdb'.

- `PUPPETDB_NODE_TTL`

  How long nodes should be preserved in puppetdb without receiving any updates (new catalogs, facts, or reports)
  before being marked expired. Defaults to '7d'.

- `PUPPETDB_NODE_PURGE_TTL`

  Delete nodes that have been deactivated or expired for the specified amount of time. Defaults to '14d'.

- `PUPPETDB_REPORT_TTL`

  Automatically delete reports that are older than the specified amount of time. Defaults to '14d'.

- `PUPPETDB_JAVA_ARGS`

  Additional Java args to pass to the puppetdb process. Defaults to '-Djava.net.preferIPv4Stack=true -Xms256m -Xmx256m'.

- `CONSUL_ENABLED`

  Whether or not to register the `puppet` service with an external consul server. Defaults to 'false'.

- `CONSUL_HOSTNAME`

  If consul is enabled, the hostname for the external consul server. Defaults to 'consul'.

- `CONSUL_PORT`

  If consul is enabled, the port to access consul at. Defaults to '8500'.

- `PUPPETSERVER_HOSTNAME`

  The hostname for the puppetserver instance. This determines where to request certificates from. Defaults to 'puppet'.

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
