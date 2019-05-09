---
title: "PuppetDB 5.2: Connecting Puppet masters to PuppetDB"
layout: default
canonical: "/puppetdb/latest/connect_puppet_master.html"
---

[puppetdb_download]: http://downloads.puppetlabs.com/puppetdb
[puppetdb_conf]: ./puppetdb_connection.html
[routes_yaml]: {{puppet}}/config_file_routes.html
[exported]: {{puppet}}/lang_exported.html
[install_via_module]: ./install_via_module.html
[report_processors]: {{puppet}}/reporting_about.html
[event]: ./api/query/v4/events.html
[report]: ./api/query/v4/reports.html
[store_report]: ./api/command/v1/commands.html#store-report-version-7
[report_format]: ./api/wire_format/report_format_v5.html
[puppetdb_server_urls]: ./puppetdb_connection.html#serverurls

> Note: To use PuppetDB, your site's Puppet master(s) must be running
> Puppet version 5.0.0 or later.

After PuppetDB is installed and running, configure your master to use it. When properly connected to PuppetDB, the master does the following:

* Send every node's catalog, facts, and reports to PuppetDB
* Query PuppetDB when compiling node catalogs that collect [exported resources][exported]

> Note: if you've [installed PuppetDB using the PuppetDB module][install_via_module], then the `puppetdb::master::config` class is taking care of all of this for you.

 **Working on your Puppet master server(s),** follow all of the instructions below:

## Step 1: Install plug-ins

Currently, Puppet masters need additional Ruby plug-ins in order to use PuppetDB. Unlike custom facts or functions, these cannot be loaded from a module and must be installed in Puppet's main source directory.

### On platforms with packages

[Enable the Puppet Collection repo]({{puppet}}/puppet_collections.html) and then install the `puppetdb-termini` package:

    $ sudo puppet resource package puppetdb-termini ensure=latest

### On platforms without packages

If your Puppet master isn't running Puppet from a supported package, you will need to install the plugins manually:

* [Download the PuppetDB source code][puppetdb_download], unzip it, and navigate into the resulting directory in your terminal.

* Run `sudo cp -R puppet/lib/puppet/ /opt/puppetlabs/puppet/lib/ruby/vendor_ruby/puppet`

## Step 2: Edit configuration files

### Locate Puppet's config directory

Find your Puppet master's config directory by running `sudo puppet config print confdir`. It will usually be at either `/etc/puppet/` or `/etc/puppetlabs/puppet/`.

You will edit (or create) three files in this directory:

### 1. Edit puppetdb.conf

The [puppetdb.conf][puppetdb_conf] file will probably not yet exist. Create it, and add the PuppetDB server's URL that includes the hostname and port:

    [main]
    server_urls = https://puppetdb.example.com:8081

PuppetDB's port for secure traffic defaults to 8081 with the context root of '/'. If you have not changed the defaults, the above configuration (with the correct hostname) is sufficient. For more information on configuring `server_urls`, including support for multiple PuppetDB backends, see [configuring the PuppetDB server_urls][puppetdb_server_urls].

### 2. Edit puppet.conf

To enable saving facts and catalogs in PuppetDB, edit the `[master]` block of puppet.conf to reflect the following settings:

    [master]
      storeconfigs = true
      storeconfigs_backend = puppetdb

> Note: The `thin_storeconfigs` and `async_storeconfigs` settings should be absent or set to `false`. If you previously used the Puppet queue daemon (puppetqd), you should now disable it.

#### Enabling report storage

PuppetDB includes support for storing Puppet reports. This feature can be
enabled by simply adding the `puppetdb` report processor in your `puppet.conf`
file. If you don't already have a `reports` setting in your `puppet.conf`
file, you'll probably want to add a line like this:

    reports = store,puppetdb

This will retain Puppet's default behavior of storing the reports to disk as YAML,
while also sending the reports to PuppetDB.

You can configure how long PuppetDB stores these reports, and you can do some
very basic querying. For more information, see:

* [The `event` query endpoint][event]
* [The `report` query endpoint][report]
* [The `store report` command][store_report]
* [The report wire format][report_format]

More information about Puppet report processors in general can be found
[here][report_processors].

### 3. Edit routes.yaml

The [routes.yaml][routes_yaml] file will probably not yet exist. Find the path to this Puppet configuration file by running `puppet master --configprint route_file`.

Create the file, if necessary, and add the following:

    ---
    master:
      facts:
        terminus: puppetdb
        cache: yaml

### Ensure proper ownership of the config files

The files created above need to be owned by the `puppet` user. Ensure that
this ownership is applied by running the following command:

    $ sudo chown -R puppet:puppet `sudo puppet config print confdir`

## Step 3: Set security policy

PuppetDB listens on TCP port 8081 (HTTPS). Ensure that this port is open between
the Puppet master and PuppetDB services. If the services run on the same server, additional configuration might not be needed. If the services are on separate
servers, ensure that the server and network firewalls allow for traffic flow.

PuppetDB works without modification with SELinux in enforcing mode.

## Step 4: Restart Puppet master

Use your system's service tools to restart the Puppet master service. For open source Puppet users, the command to do this will vary, depending on the frontend web server being used.

> Your Puppet master is now using PuppetDB to store and retrieve catalogs, facts, and exported resources. You can test your setup by triggering a Puppet agent run on an arbitrary node, then logging into your PuppetDB server and viewing the `/var/log/puppetlabs/puppetdb/puppetdb.log` file, which will include calls to the "replace facts", "replace catalog", and "store report" commands:
>
>     2012-05-17 13:08:41,664 INFO  [command-proc-67] [puppetdb.command] [85beb105-5f4a-4257-a5ed-cdf0d07aa1a5] [replace facts] screech.example.com
>     2012-05-17 13:08:45,993 INFO  [command-proc-67] [puppetdb.command] [3a910863-6b33-4717-95d2-39edf92c8610] [replace catalog] screech.example.com
