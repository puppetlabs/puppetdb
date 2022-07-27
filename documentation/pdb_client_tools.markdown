---
title: "PuppetDB CLI"
layout: default
---

# PuppetDB CLI

[installpuppet]: https://puppet.com/docs/puppet/latest/install_pre.html
[repos]: https://puppet.com/docs/puppet/latest/puppet_platform.html
[export]: ./anonymization.markdown
[installpeclienttools]: https://puppet.com/docs/pe/latest/installing_pe_client_tools.html

## Installation

For Puppet Enterprise you have the ability to install the PuppetDB CLI via the
`pe-client-tools` package. If you are installing `pe-client-tools` please see
[the pe-client-tools installation instructions][installpeclienttools] for
instructions on installing the PuppetDB CLI on either a workstation managed or
unmanaged by Puppet.

### Step 1: Install and configure Puppet

If Puppet isn't fully installed and configured [install it][installpuppet] and
request, sign, and retrieve a certificate for the node.

Your node should be running the Puppet agent and have a signed certificate from
your Puppet Server. If you run `puppet agent --test`, it should
successfully complete a run, ending with `Notice: Applied catalog in X.XX
seconds`.

> **Note:** It is helpful to add the Puppet bin, `/opt/puppetlabs/bin`, and man,
`/opt/puppetlabs//client-tools/share/man`, directories to your `PATH` and
`MANPATH` directories respectively. For example,
>
> ```bash
> $ export PATH=/opt/puppetlabs/bin:$PATH
> $ export MANPATH=/opt/puppetlabs/client-tools/share/man:$MANPATH
> ```
>
> The rest of this documentation assumes that these two directories have been
added to their proper path configurations.

### Step 2: Install and configure the PuppetDB CLI

Install the PuppetDB CLI from Rubygems:

    $ gem install --bindir /opt/puppetlabs/bin puppetdb_cli

If you are installing the PuppetDB CLI on a machine that does not have Puppet
installed, such as your own workstation, you can install the executables to Ruby's
standard bindir by omitting the `--bindir` option.

    $ gem install puppetdb_cli

If the node you installed the CLI on is not the same node as your PuppetDB
server, you will need to add the CLI node's certname to the PuppetDB
certificate-whitelist and specify the paths to the CLI node's cacert, cert, and
private key when using the CLI either with flags or a configuration file.

To configure the PuppetDB CLI to talk to your PuppetDB with flags, add a
configuration file at `$HOME/.puppetlabs/client-tools/puppetdb.conf` (or
`%USERPROFILE%\.puppetlabs\client-tools\puppetdb.conf` for Windows). For more
details see the installed man page:

    $ man puppetdb_conf

The PuppetDB CLI configuration files (the user-specified or global files) can
take the following settings:

- `server_urls` Either a JSON String (for a single url) or Array (for multiple
  urls) of your PuppetDB servers to query or manage via the CLI commands. (You
  can set this with the `puppetdb_urls` parameter in the
  `puppet_enterprise::profile::controller` class for PE.)

  Default value: https://127.0.0.1:8080

- `cacert` The path for the CA cert.

  *nix sytems - /etc/puppetlabs/puppet/ssl/certs/ca.pem

  Windows - C:\ProgramData\PuppetLabs\puppet\etc\ssl\certs\ca.pem

- `cert` An SSL certificate signed by your site's Puppet CA. Note that the PE
 version of the CLI supports token auth via `puppet-access` and this option
 should not be necessary.

- `key` The private key for that certificate. Note that the PE version of the
 CLI supports token auth via `puppet-access` and this option should not be
 necessary.

#### Example configuration file (pe-client-tools)

The PE version of the PuppetDB CLI supports token auth so the only
necessary configuration items are `server_urls` and `cacert`.

> **Note:** You can still use certificate authentication with the PE version (see
below for an example configuration) but setting `cert` and `key` in the PuppetDB
CLI configuration will prevent you from using token authentication (for example,
certificate authentication takes precendence over token authentication).

```json
{
  "puppetdb": {
    "server_urls": "https://<PUPPETDB_HOST>:8081",
    "cacert": "/etc/puppetlabs/puppet/ssl/certs/ca.pem"
  }
}
```

On Windows, escape slashes in the CA certificate path.

```json
{
  "puppetdb": {
    "server_urls": "https://<PUPPETDB_HOST>:8081",
    "cacert": "C:\\ProgramData\\PuppetLabs\\puppet\\etc\\ssl\\certs\\ca.pem"
  }
}
```

#### Example configuration file (puppet-client-tools)

The open source version of the PuppetDB CLI requires certificate authentication
for SSL connections to PuppetDB. To configure certificate authentication set
`cacert`, `cert` and `key`.

```json
{
  "puppetdb": {
    "server_urls": "https://<PUPPETDB_HOST>:8081",
    "cacert": "/etc/puppetlabs/puppet/ssl/certs/ca.pem",
    "cert": "/etc/puppetlabs/puppet/ssl/certs/<WORKSTATION_HOST>.pem",
    "key": "/etc/puppetlabs/puppet/ssl/private_keys/<WORKSTATION_HOST>.pem"
  }
}
```

On Windows, escape slashes in paths.

```json
{
  "puppetdb": {
    "server_urls": "https://<PUPPETDB_HOST>:8081",
    "cacert": "C:\\ProgramData\\PuppetLabs\\puppet\\ssl\\certs\\ca.pem",
    "cert": "C:\\ProgramData\\PuppetLabs\\puppet\\ssl\\certs\\<WORKSTATION_HOST>.pem",
    "key": "C:\\ProgramData\\PuppetLabs\\puppet\\ssl\\private_keys\\<WORKSTATION_HOST>.pem"
  }
}
```

### Step 3: Enjoy!

Here are some examples of using the CLI.

#### Using `puppet query`

Query PuppetDB using PQL:

    $ puppet query "nodes [ certname ]{ limit 1 }"

Or query PuppetDB using the AST syntax:

    $ puppet query "['from', 'nodes', ['extract', 'certname'], ['limit', 1]]"

For more information on the `query` command:

    $ man puppet-query

#### Using `puppet db`

Handle your PuppetDB exports:

    $ puppet db export pdb-archive.tgz --anonymization full

Or handle your PuppetDB imports:

    $ puppet db import pdb-archive.tgz

For more information on the `db` command:

    $ man puppet-db

For more information about PuppetDB exports, imports, and anonymization
[see][export].
