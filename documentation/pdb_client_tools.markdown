---
title: "PuppetDB 4.0: PuppetDB CLI"
layout: default
canonical: "/puppetdb/latest/pdb_client_tools.html"
---

[installpuppet]: /puppet/latest/reference/install_pre.html
[repos]: /guides/puppetlabs_package_repositories.html
[export]: ./anonymization.html

# PuppetDB CLI

## Installation

### Step 1: Install and configure Puppet

If Puppet isn't fully installed and configured [install it][installpuppet] and
request, sign, and retrieve a certificate for the node.

Your node should be running the Puppet agent and have a signed certificate from
your Puppet master server. If you run `puppet agent --test`, it should
successfully complete a run, ending with `Notice: Applied catalog in X.XX
seconds`.

### Step 2: Enable the Puppet Labs package repository

If you didn't already use it to install Puppet, you will need to
[enable the Puppet Labs package repository][repos] for your system.

### Step 3: Install and configure the PuppetDB CLI

Use Puppet to install the PuppetDB CLI:

  puppet resource package puppet-client-tools ensure=latest
  
If the node you installed the CLI on is not the same node as your PuppetDB
server, you will need to add the CLI node's certname to the PuppetDB
certificate-whitelist and specify the paths to the CLI node's cacert, cert, and
private key when using the CLI either with flags or a configuration file.
  
To configure the PuppetDB CLI to talk to your PuppetDB with flags, add a
configuration file at `$HOME/.puppetlabs/client-tools/puppetdb.conf`. For more
details see the installed man page:

  man /opt/puppetlabs/client/tools/share/man/man8/puppetdb_conf.8
  
### Step 4: Enjoy!

Here are some examples of using the CLI.

#### Using `puppet-query`

Query PuppetDB using PQL:

  puppet query 'nodes [ certname ]{ limit 1 }'
  
Or query PuppetDB using the AST syntax:

  puppet query '["from", "nodes", ["extract", "certname"], ["limit", "1"]]'
  
For more information on the `query` command: 

  man /opt/puppetlabs/client/tools/share/man/man8/puppet-query.8
  
#### Using `puppet-db`
  
Handle your PuppetDB exports:

  puppet db export pdb-archive.tgz --anonymization full

Or handle your PuppetDB imports:

  puppet db import pdb-archive.tgz

For more information on the `db` command:

  man /opt/puppetlabs/client/tools/share/man/man8/puppet-db.8
  
For more information about PuppetDB exports and imports [see][export].
