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

- `PUPPERWARE_DISABLE_ANALYTICS`

  Set to `false` to disable Google Analytics.

- `USE_PUPPETSERVER`

  Set to `false` to skip acquiring SSL certificates from a Puppet Server.



[1]: https://github.com/puppetlabs/puppetdb/blob/master/docker/puppetdb/Dockerfile
[2]: https://github.com/puppetlabs/puppetdb/tree/master/docker
[3]: https://puppet.com/docs/puppetdb/latest/index.html
[4]: https://github.com/puppetlabs/pupperware
