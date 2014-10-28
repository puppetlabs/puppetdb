---
title: "PuppetDB 2.2 » Connecting Puppet Masters to PuppetDB"
layout: default
canonical: "/puppetdb/latest/connect_puppet_master.html"
---

[puppetdb_download]: http://downloads.puppetlabs.com/puppetdb
[puppetdb_conf]: /puppet/latest/reference/config_file_puppetdb.html
[routes_yaml]: /puppet/latest/reference/config_file_routes.html
[exported]: /puppet/latest/reference/lang_exported.html
[install_via_module]: ./install_via_module.html
[report_processors]: /guides/reporting.html
[event]: ./api/query/v3/event.html
[report]: ./api/query/v3/report.html
[store_report]: ./api/commands.html#store-report-version-1
[report_format]: ./api/wire_format/report_format_v3.html
[url_prefix_setting]: ./configure.html#url-prefix

> Note: To use PuppetDB, your site's puppet master(s) must be running Puppet 3.5.1 or later .

After PuppetDB is installed and running, you should configure your puppet master(s) to use it. Once connected to PuppetDB, puppet masters will do the following:

* Send every node's catalog to PuppetDB
* Send every node's facts to PuppetDB
* Query PuppetDB when compiling node catalogs that collect [exported resources][exported]

> Note: if you've [installed PuppetDB using the PuppetDB puppet module][install_via_module], then the `puppetdb::master::config` class is taking care of all of this for you.

 **Working on your puppet master server(s),** follow all of the instructions below:

## Step 1: Install Plugins

Currently, puppet masters need additional Ruby plugins in order to use PuppetDB. Unlike custom facts or functions, these cannot be loaded from a module and must be installed in Puppet's main source directory.

### On Platforms With Packages

[Enable the Puppet Labs repo](/guides/puppetlabs_package_repositories.html#open-source-repositories) and then install the `puppetdb-terminus` package:

    $ sudo puppet resource package puppetdb-terminus ensure=latest

### On Platforms Without Packages

If your puppet master isn't running Puppet from a supported package, you will need to install the plugins manually:

* [Download the PuppetDB source code][puppetdb_download], unzip it and navigate into the resulting directory in your terminal.
* Run `sudo cp -R ext/master/lib/puppet /usr/lib/ruby/site_ruby/1.8/puppet`. Replace the second path with the path to your Puppet installation if you have installed it somewhere other than `/usr/lib/ruby/site_ruby`.

## Step 2: Edit Config Files

### Locate Puppet's Config Directory

Find your puppet master's config directory by running `sudo puppet config print confdir`. It will usually be at either `/etc/puppet/` or `/etc/puppetlabs/puppet/`.

You will need to edit (or create) three files in this directory:

### 1. Edit puppetdb.conf

The [puppetdb.conf][puppetdb_conf] file will probably not exist yet. Create it, and add the PuppetDB server's hostname and port:

    [main]
    server_urls = https://puppetdb.example.com:8081/

PuppetDB's port for secure traffic defaults to 8081 with the url prefix '/'. Puppet _requires_ use of PuppetDB's secure, HTTPS port. You cannot use the unencrypted, plain HTTP port. If you have specified a [`url-prefix` setting in your PuppetDB configuration][url_prefix_setting] that prefix must be reflected in your urls.

Note that a comma separated list of the URLs can be provided if there are more than one PuppetDB instances available, for instance:

    [main]
    server_urls = https://puppetdb1.example.com:8081/,https://puppetdb2.example.com:8081/

For availability reasons there is a setting named `soft_write_failure` that will cause the PuppetDB terminus to fail in a soft-manner if PuppetDB is not accessable for command submission. This will mean that users who are either not using storeconfigs, or only exporting resources will still have their catalogs compile during a PuppetDB outage.

The `server_url_timeout` field sets the maximum amount of time (in seconds) the PuppetDB terminus will wait for an HTTP request to be responded to by PuppetDB. If the user has specified more than one PuppetDB URL and a timeout has occurred, it will attempt the same request in the next server in the list.

If no puppetdb.conf file exists, the following default values will be used:

    server_urls = https://puppetdb:8081/
    server_url_timeout = 30
    soft_write_failure = false

### 2. Edit puppet.conf

To enable saving facts and catalogs in PuppetDB, add the following settings to the `[master]` block of puppet.conf (or edit them if already present):

    [master]
      storeconfigs = true
      storeconfigs_backend = puppetdb

> Note: The `thin_storeconfigs` and `async_storeconfigs` settings should be absent or set to `false`. If you have previously used the puppet queue daemon (puppetqd), you should now disable it.

#### Enabling report storage

PuppetDB includes support for storing Puppet reports.  This feature can be
enabled by simply adding the `puppetdb` report processor in your `puppet.conf`
file.  If you don't already have a `reports` setting in your `puppet.conf`
file, you'll probably want to add a line like this:

    reports = store,puppetdb

This will keep Puppet's default behavior of storing the reports to disk as YAML,
while also sending the reports to PuppetDB.

You can configure how long PuppetDB stores these reports, and you can do some
very basic querying.  For more information, see:

* [The `event` query endpoint][event]
* [The `report` query endpoint][report]
* [The `store report` command][store_report]
* [The report wire format][report_format]

More information about Puppet report processors in general can be found
[here][report_processors].

### 3. Edit routes.yaml

The [routes.yaml][routes_yaml] file will probably not exist yet. The path to this Puppet configuration file can be found with the command `puppet master --configprint route_file`.

Create it if necessary, and add the following:

    ---
    master:
      facts:
        terminus: puppetdb
        cache: yaml

## Step 3: Restart Puppet Master

Use your system's service tools to restart the puppet master service. For open source users, the command to do this will vary depending on the front-end web server being used.

> Your puppet master should now be using PuppetDB to store and retrieve catalogs, facts, and exported resources. You can test this by triggering a puppet agent run on an arbitrary node, then logging into your PuppetDB server and viewing the `/var/log/puppetdb/puppetdb.log` file --- you should see calls to the "replace facts" and "replace catalog" commands:
>
>     2012-05-17 13:08:41,664 INFO  [command-proc-67] [puppetdb.command] [85beb105-5f4a-4257-a5ed-cdf0d07aa1a5] [replace facts] screech.example.com
>     2012-05-17 13:08:45,993 INFO  [command-proc-67] [puppetdb.command] [3a910863-6b33-4717-95d2-39edf92c8610] [replace catalog] screech.example.com
