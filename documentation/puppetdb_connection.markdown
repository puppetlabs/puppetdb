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

The default value is `https://puppetdb:8081`.

For read operations, the PuppetDB terminus will always attempt to connect to the first PuppetDB instance specified (listed above as puppetdb1). If a server-side exception occurs, or the request takes too long (see [`server_url_timeout`](#server_url_timeout)), the PuppetDB terminus will attempt the same operation on the next instance in the list.

For write operations, all commands are sent to all PuppetDB instances in the list. 

Configurations with multiple `server_urls`, along with the `min_successful_submissions` and `submit_only_server_urls` settings, can be used with the Puppet Enterprise version of PuppetDB to set up a high-availability PuppetDB deployment. Please note that, absent the synchronization support in Puppet Enterprise, multiple PuppetDB instances can easily diverge from each other due to normal transient network issues. We do not recommend using these settings outside of a supported high-availability deployment. `

### `min_successful_submissions`

When writing data (submitting commands) to PuppetDB, this is the minimum number of machines to which the command must be successfully sent to consider the write successful. The default value is 1, which should be appropriate for most single- or dual-PuppetDB deployments. You may wish to change this value if you have particularly high data durability requirements. For example, if you want to ensure that you can withstand the loss of a node even when running PuppetDB in a degraded state, you could deploy a cluster of a 3 PuppetDB instances and set `min_successful_submissions` to 2. 

### `submit_only_server_urls`

This setting allows you specify PuppetDB instances to which commands should be sent, but which shouldn't ever be queried for data needed during a Puppet run. It uses the same format as `server_urls`. For example:

    submit_only_server_urls = https://puppetdb-submit-only.example.com:8081

If a server is listed in `submit_only_server_urls`, it shouldn't be listed in `server_urls`; the two lists should be disjoint.

Successful command submission to the PuppetDB instances in this list *do* count towards the `min_successful_submissions` setting, so you should consider incrementing that if you use this setting.

### `server_url_timeout`

The `server_url_timeout` setting sets the maximum amount of time (in seconds) the PuppetDB termini will wait for PuppetDB to respond to HTTP requests. If the user has specified multiple PuppetDB URLs and a timeout has occurred, it will attempt the same request on the next server in the list.

The default value is 30 seconds.

### `soft_write_failure`

This setting can let the Puppet master stay partially available during a PuppetDB outage. If set to `true`, Puppet can keep compiling and serving catalogs even if PuppetDB isn't accessible for command submission. (However, any catalogs that need to _query_ exported resources from PuppetDB will still fail.)

The default value is false.
