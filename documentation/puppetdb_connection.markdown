---
title: "PuppetDB 3.2 » Configuration » Using SSL with PostgreSQL"
layout: default
canonical: "/puppetdb/latest/puppetdb_connection.html"
---

[puppetdb_root]: ./index.html
[connect_to_puppetdb]: ./connect_puppet_master.html
[confdir]: /puppet/latest/reference/dirs_confdir.html
[puppetdb_conf]: ./connect_puppet_master.html#edit-puppetdb\.conf

The `puppetdb.conf` file contains the hostname and port of the [PuppetDB][puppetdb_root] server. It is only used if you are using PuppetDB and have [connected your Puppet master to it][connect_to_puppetdb].

Summary
-----

The Puppet master makes HTTPS connections to PuppetDB to store catalogs, facts and new reports. It also uses PuppetDB to answer queries such those necessary to support exported resources. If the PuppetDB instance is down, depending on the configuration of the Puppet master, it could cause the Puppet run to fail. This document discusses configuration options for the `puppetdb.conf` file, including settings to make the PuppetDB terminus more tolerant of failures.

## Location

The `puppetdb.conf` file is always located at `$confdir/puppetdb.conf`. Its location is **not** configurable.

The location of the `confdir` varies; it depends on the OS, Puppet distribution, and user account. [See the confdir documentation for details.][confdir]

## Example

    [main]
    server_urls = https://puppetdb.example.com:8081

## Format

The `puppetdb.conf` file uses the same INI-like format as `puppet.conf`, but only uses a `[main]` section

`[main]` Settings
-----

The `[main]` section defines all of the PuppetDB terminus settings.

### `server_urls`

This setting specifies how the Puppet master should connect to PuppetDB. The configuration should look something like this example:

    server_urls = https://puppetdb.example.com:8081

Puppet _requires_ the use of PuppetDB's secure, HTTPS port. You cannot use the unencrypted, plain HTTP port.

You can use a comma separated list of URLs if there are multiple PuppetDB instances available. A `server_urls` config that supports two PuppetDBs would look like:

    server_urls = https://puppetdb1.example.com:8081,https://puppetdb2.example.com:8081

The PuppetDB terminus will always attempt to connect to the first PuppetDB instance specified (listed above as puppetdb1). If a server-side exception occurs, or the request takes too long (see [`server_url_timeout`](#server_url_timeout)), the PuppetDB terminus will attempt the same operation on the next instance in the list.

The default value is https://puppetdb:8081

### `server_url_timeout`

The `server_url_timeout` setting sets the maximum amount of time (in seconds) the PuppetDB termini will wait for PuppetDB to respond to HTTP requests. If the user has specified multiple PuppetDB URLs and a timeout has occurred, it will attempt the same request on the next server in the list.

The default value is 30 seconds.

### `soft_write_failure`

This setting can let the Puppet master stay partially available during a PuppetDB outage. If set to `true`, Puppet can keep compiling and serving catalogs even if PuppetDB isn't accessible for command submission. (However, any catalogs that need to _query_ exported resources from PuppetDB will still fail.)

The default value is false.

### `server` and `port`

> **Deprecated:** `server_urls` replaces these setting. These settings will be removed in the future.

Hostname and port of the PuppetDB instance. `server_urls` takes precedence if both are defined.

