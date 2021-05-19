---
title: "Configuring a Puppet/PuppetDB connection"
layout: default
canonical: "/puppetdb/latest/puppetdb_connection.html"
---

# Configuring a Puppet/PuppetDB connection

[puppetdb_root]: ./index.markdown
[connect_to_puppetdb]: ./connect_puppet_server.markdown
[confdir]: https://puppet.com/docs/puppet/latest/dirs_confdir.html
[puppetdb_conf]: ./connect_puppet_server.markdown#edit-puppetdb\.conf

The `puppetdb.conf` file contains the hostname and port of the [PuppetDB][puppetdb_root] server. It is only used if you are using PuppetDB and have [connected your Puppet Server to it][connect_to_puppetdb].

The Puppet Server makes HTTPS connections to PuppetDB to store catalogs, facts, and new reports. It also uses PuppetDB to answer queries, such as those necessary to support exported resources. If the PuppetDB instance is down, depending on the configuration of the Puppet Server, it could cause the Puppet run to fail. This document discusses configuration options for the `puppetdb.conf` file, including settings to make the PuppetDB terminus more tolerant of failures.

## Location

The `puppetdb.conf` file is always located at `$confdir/puppetdb.conf`. Its location is **not** configurable.

The location of the `confdir` varies, depending on the OS, Puppet distribution, and user account. [See the configuration directory documentation for details.][confdir]

## Example

    [main]
    server_urls = https://puppetdb.example.com:8081

## Format

The `puppetdb.conf` file uses the same INI-like format as `puppet.conf`, but only uses a `[main]` section.

## `[main]` Settings

The `[main]` section defines all of the PuppetDB terminus settings.

### `server_urls`

This setting specifies how the Puppet Server should connect to PuppetDB. The configuration should look something like:

    server_urls = https://puppetdb.example.com:8081

Puppet **requires** the use of PuppetDB's secure HTTPS port. You cannot use the unencrypted HTTP port.

You can use a comma-separated list of URLs if there are multiple PuppetDB instances available. A `server_urls` config that supports two PuppetDBs would look like:

    server_urls = https://puppetdb1.example.com:8081,https://puppetdb2.example.com:8081

The default value is `https://puppetdb:8081`.

The PuppetDB terminus will always attempt to connect to the first PuppetDB instance specified (listed above as `puppetdb1`). If a server-side exception occurs, or the request takes too long (see [`server_url_timeout`](#serverurltimeout)), the PuppetDB terminus will attempt the same operation on the next instance in the list.

### `submit_only_server_urls`

This setting allows you specify PuppetDB instances to which commands should be sent, but which shouldn't ever be queried for data needed during a Puppet run. It uses the same format as `server_urls`. For example:

    submit_only_server_urls = https://puppetdb-submit-only.example.com:8081

If a server is listed in `submit_only_server_urls`, it shouldn't be listed in `server_urls`; the two lists should be disjoint.

Successful command submission to the PuppetDB instances in this list **do** count towards the `min_successful_submissions` setting, so consider incrementing accordingly if you use this setting.

### `server_url_timeout`

The `server_url_timeout` setting sets the maximum amount of time (in seconds) the PuppetDB-termini will wait for PuppetDB to respond to HTTP requests. If the user has specified multiple PuppetDB URLs and a timeout has occurred, it will attempt the same request on the next server in the list.

The default value is 30 seconds.

### `soft_write_failure`

This setting can let the Puppet Server stay partially available during a PuppetDB outage. If set to `true`, Puppet will keep compiling and serving catalogs even if PuppetDB isn't accessible for command submission. (However, any catalogs that need to **query** exported resources from PuppetDB will still fail.)

The default value is false.

### `include_unchanged_resources` (PE only)

> **Warning:** This setting is intended for use only in Puppet Enterprise (PE).
> Using this setting without a PE PuppetDB package will only result in degraded
> PuppetDB performance, and PuppetDB will not store the unchanged resources data.

This setting tells the PuppetDB terminus whether or not it should include
unchanged resources data in a report when sending it to PuppetDB. If you do not
want to store information about unchanged resources in a report, set this value
to `false`.

The default value in PE is true.

#### `sticky_read_failover`

> **Note:** For use with Puppet Enterprise only.

When using multiple `server_urls`, this flag can be set to `true` to cause queries to be made to the last PuppetDB instance that was successfully contacted.

The default value is false.

#### `command_broadcast`

> **Note:** For use with Puppet Enterprise only.

When set to `true` in installations using multiple `server_urls`, commands are sent to all configured PuppetDB instances. 

In open source Puppet and PE versions earlier than 2016.5, the default setting is `false`. In PE 2016.5 and later, the default setting is `true`. 


#### `min_successful_submissions`

> **Note:** For use with Puppet Enterprise only.

When writing data (submitting commands) to PuppetDB, this is the minimum number of machines to which the command must be successfully sent to consider the write successful. If the configured number of machines cannot be reached, Puppet runs will fail.

The default value is one, which should be appropriate for most single- or dual-PuppetDB deployments.

This setting must be used in conjunction with `command_broadcast`.
