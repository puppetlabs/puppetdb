---
title: "PE PuppetDB 3.0 » PE Configuration"
layout: default
canonical: "/puppetdb/latest/pe-configure.html
---

[configure]: ./configure.html
[node-ttl]: ./configure.html#node-ttl
[ha-guide]: ./ha-guide.html

Summary
-----

The Puppet Enterprise version of PuppetDB has some additional configuration
options. 

`[sync]` Settings
-----

The `[sync]` section of the PuppetDB configuration file is used to configure
synchronization, for a high-availability system. See the
[HA configuration guide][ha-guide] for complete system configuration
instructions.

### `remotes`

The `remotes` configuration key indicates that PuppetDB should poll a remote
PuppetDB server for changes. When it finds changed or updated records on that
server, it will download the records and submit them to the local command queue.

In the configuration file, you specify a `remote` for each server you want to
pull data from. (It is perfectly reasonable, and expected, for two servers to
pull data from each other.) For each remote, you must provide:

 - The remote server url. This is a root url which must include the protocol and
   port to use. (eg. "https://puppetdb.myco.net:8081")

 - The interval at which to poll the remote server for new data. This is
   formatted as a timespan with units (e.g. '2m'). See the
   [node-ttl][node-ttl documentation], for further reference.

Please note that, although the configuration format supports many remotes,
PuppetDB itself does not yet. It is currently an error to configure more than
one remote.

You should not configure PuppetDB to sync with itself. 

#### HOCON

If you are using HOCON to configure PuppetDB, use the following structure in
your .conf file:

    sync: {
      remotes: [{server_url: "https://remote-puppetdb.myco.net:8081",
                 interval: 2m}]
    }

#### ini

If you are using a .ini file to configure PuppetDB, use the following structure:

    [sync]
    server_urls = https://remote-puppetdb.myco.net:8081
    intervals = 2m

Multiple values may be provided by comma-separating them, with no whitespace.
You must have exactly the same number of entries in the `server_urls` and
`intervals` values. *NB*: PuppetDB does not yet support syncing with multiple
remote hosts. 
