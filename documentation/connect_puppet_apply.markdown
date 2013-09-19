---
title: "PuppetDB 1.3 » Connecting Standalone Puppet Nodes to PuppetDB"
layout: default
canonical: "/puppetdb/latest/connect_puppet_apply.html"
---

[exported]: /puppet/2.7/reference/lang_exported.html
[package]: /references/latest/type.html#package
[file]: /references/latest/type.html#file
[yumrepo]: /references/latest/type.html#yumrepo
[apt]: http://forge.puppetlabs.com/puppetlabs/apt
[puppetdb_download]: http://downloads.puppetlabs.com/puppetdb
[puppetdb_conf]: /guides/configuring.html#puppetdbconf
[routes_yaml]: /guides/configuring.html#routesyaml
[exported]: /puppet/2.7/reference/lang_exported.html
[jetty]: ./configure.html#jetty-http-settings
[settings_namespace]: /puppet/2.7/reference/lang_variables.html#master-set-variables
[ssl_script]: ./install_from_source.html#step-3-option-a-run-the-ssl-configuration-script

> Note:  To use PuppetDB, the nodes at your site must be running Puppet 2.7.12 or later.

PuppetDB can also be used with standalone Puppet deployments where each node runs `puppet apply`. Once connected to PuppetDB, `puppet apply` will do the following:

* Send the node's catalog to PuppetDB
* Query PuppetDB when compiling catalogs that collect [exported resources][exported]

**Note that standalone deployments can only store catalogs and cannot use the inventory service.** This is due to a limitation in Puppet.

You will need to take the following steps to configure your standalone nodes to connect to PuppetDB. Note that since you must change Puppet's configuration on every managed node, **we strongly recommend that you do so with Puppet itself.** 

## Step 1: Configure SSL

PuppetDB requires client authentication for its SSL connections and the PuppetDB terminus plugins require SSL to talk to PuppetDB. You must configure Puppet and PuppetDB to work around this double-bind by using one of the following options:

### Option A: Set Up an SSL Proxy for PuppetDB

1. Edit [the `jetty` section of the PuppetDB config files][jetty] to remove all SSL-related settings.
2. Install a general purpose web server (like Apache or Nginx) on the PuppetDB server.
3. Configure the web server to listen on port 8081 with SSL enabled and proxy all traffic to `localhost:8080` (or whatever unencrypted hostname and port were set in [jetty.ini][jetty]). The proxy server can use any certificate --- as long as Puppet has never downloaded a CA cert from a puppet master, it will not verify the proxy server's certificate. If your nodes have downloaded CA certs, you must either make sure the proxy server's cert was signed by the same CA, or delete the CA cert. 

<!--TODO-->More detailed instructions for setting up this proxy will be added to this guide at a later date.

### Option B: Issue Certificates to All Puppet Nodes

When talking to PuppetDB, puppet apply can use the certificates issued by a puppet master's certificate authority . You can issue certificates to every node by setting up a puppet master server with dummy manifests, running `puppet agent --test` once on every node, signing every certificate request on the puppet master, and running `puppet agent --test` again on every node.

Do the same on your PuppetDB node, then [re-run the SSL setup script][ssl_script]. PuppetDB will now trust connections from your Puppet nodes.

You will have to sign a certificate for every new node you add to your site. 


## Step 2: Install Terminus Plugins on Every Puppet Node

Currently, Puppet needs extra Ruby plugins in order to use PuppetDB. Unlike custom facts or functions, these cannot be loaded from a module and must be installed in Puppet's main source directory. 

* First, ensure that the appropriate Puppet Labs package repository ([Puppet Enterprise](/guides/puppetlabs_package_repositories.html#puppet-enterprise-repositories), or [open source](/guides/puppetlabs_package_repositories.html#open-source-repositories)) is enabled. You can use a [package][] resource to do this or use the apt::source (from the [puppetlabs-apt][apt] module) and [yumrepo][] types. 
* Next, use Puppet to ensure that the `puppetdb-terminus` package is installed:

{% highlight ruby %}
    package {'puppetdb-terminus':
      ensure => installed,
    }
{% endhighlight %}


### On Platforms Without Packages

If your puppet master isn't running Puppet from a supported package, you will need to install the plugins using [file][] resources. 

* [Download the PuppetDB source code][puppetdb_download]; unzip it, locate the `puppet/lib/puppet` directory and put it in the `files` directory of the Puppet module you are using to enable PuppetDB integration.
* Identify the install location of Puppet on your nodes.
* Create a [file][] resource in your manifests for each of the plugin files, to move them into place on each node. 

{% highlight ruby %}
    # <modulepath>/puppetdb/manifests/terminus.pp
    class puppetdb::terminus {
      $puppetdir = "$rubysitedir/puppet"
      
      file {$puppetdir:
        ensure => directory,
        recurse => remote, # Copy these files without deleting the existing files
        source => "puppet:///modules/puppetdb/puppet",
        owner => root,
        group => root,
        mode => 0644,
      }
    }
{% endhighlight %}

## Step 3: Manage Config Files on Every Puppet Node

All of the config files you need to manage will be in Puppet's config directory (`confdir`). When managing these files with puppet apply, you can use the [`$settings::confdir`][settings_namespace] variable to automatically discover the location of this directory.

### Manage puppetdb.conf

You can specify the contents of [puppetdb.conf][puppetdb_conf] directly in your manifests. It should contain the PuppetDB server's hostname and port:

    [main]
    server = puppetdb.example.com
    port = 8081

* PuppetDB's port for secure traffic defaults to 8081.
* Puppet _requires_ use of PuppetDB's secure, HTTPS port. You cannot use the unencrypted, plain HTTP port.

If no puppetdb.conf file exists, the following default values will be used:

    server = puppetdb
    port = 8081

### Manage puppet.conf

You will need to create a template for puppet.conf based on your existing configuration. Then, modify the template by adding the following settings to the `[main]` block:

    [main]
      storeconfigs = true
      storeconfigs_backend = puppetdb

> Note: The `thin_storeconfigs` and `async_storeconfigs` settings should be absent or set to `false`.

### Manage routes.yaml

Typically, you can specify the contents of [routes.yaml][routes_yaml] directly in your manifests; if you are already using it for some other purpose, you will need to manage it with a template based on your existing configuration. The path to this Puppet configuration file can be found with the command `puppet master --configprint route_file`.

Ensure that the following keys are present:

    ---
    apply:
      facts:
        terminus: facter
        cache: facter

This will disable fact storage and prevent puppet apply from using stale facts.

