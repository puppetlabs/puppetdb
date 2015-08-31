---
layout: default
title: "PuppetDB 3.1 » API » Query » Curl Tips"
canonical: "/puppetdb/latest/api/query/curl.html"
---

[Facts]: ./v4/facts.html
[Nodes]: ./v4/nodes.html
[fact-names]: ./v4/fact-names.html
[Resources]: ./v4/resources.html
[Metrics]: ./v4/metrics.html
[curl]: http://curl.haxx.se/docs/manpage.html
[dashboard]: ../../maintain_and_tune.html#monitor-the-performance-dashboard
[whitelist]: ../../configure.html#certificate-whitelist


You can use [`curl`][curl] to directly interact with PuppetDB's REST API. This is useful for testing, prototyping, and quickly fetching arbitrary data.

The instructions below are simplified. For full usage details, see [the curl manpage][curl] . For additional examples, please see the docs for the individual REST endpoints:

* [facts][]
* [fact-names][]
* [nodes][]
* [resources][]
* [metrics][]

## Using `curl` From `localhost` (Non-SSL/HTTP)

With its default settings, PuppetDB accepts unsecured HTTP connections at port 8080 on `localhost`. This allows you to SSH into the PuppetDB server and run curl commands without specifying certificate information:

    curl http://localhost:8080/pdb/query/v4/nodes
    curl 'http://localhost:8080/metrics/v1/mbeans/java.lang:type=Memory'

If you have allowed unsecured access to other hosts in order to [monitor the dashboard][dashboard], these hosts can also use plain HTTP curl commands.

## Using `curl` From Remote Hosts (SSL/HTTPS)

To make secured requests from other hosts, you will need to supply the following via the command line:

* Your site's CA certificate (`--cacert`)
* An SSL certificate signed by your site's Puppet CA (`--cert`)
* The private key for that certificate (`--key`)

Any node managed by puppet agent will already have all of these and you can re-use them for contacting PuppetDB. You can also generate a new cert on the CA puppet master with the `puppet cert generate` command.

**Note:** If you have turned on [certificate whitelisting][whitelist], you must make sure to authorize the certificate you are using:

    curl 'https://<your.puppetdb.server>:8081/pdb/query/v4/nodes' \
      --tlsv1 \
      --cacert /etc/puppet/ssl/certs/ca.pem \
      --cert /etc/puppet/ssl/certs/<node>.pem \
      --key /etc/puppet/ssl/private_keys/<node>.pem \


For Puppet Enterprise, the paths to the SSL certificates are different, so use the following example instead:

    curl 'https://<your.puppetdb.server>:8081/pdb/query/v4/nodes' \
      --tlsv1 \
      --cacert /etc/puppetlabs/puppet/ssl/certs/ca.pem \
      --cert /etc/puppetlabs/puppet/ssl/certs/<node>.pem \
      --key /etc/puppetlabs/puppet/ssl/private_keys/<node>.pem

### Locating Puppet Certificate Files

Locate Puppet's `ssldir` as follows:

    sudo puppet config print ssldir

Within this directory:

* The CA certificate is found at `certs/ca.pem`
* The corresponding private key is found at `private_keys/<name>.pem`
* Other certificates are found at `certs/<name>.pem`


## Dealing with complex query strings

Many query strings will contain characters like `[` and `]`, which must be URL-encoded. To handle this, you can use `curl`'s `--data-urlencode` option.

If you do this with an endpoint that accepts `GET` requests, **you must also use the `-G` or `--get` option.** This is because `curl` defaults to `POST` requests when the `--data-urlencode` option is present.

    curl -G http://localhost:8080/pdb/query/v4/nodes \
      --data-urlencode 'query=["=", ["node", "active"], true]'

## Querying PuppetDB with POST

PuppetDB supports querying by POST, which is useful for particularly large
queries (exact limits depend on the client and webserver used.)

POST queries use the following syntax:

    curl -X POST http://localhost:8080/pdb/query/v4/nodes \
    -H 'Content-Type:application/json' \
    -d '{"query":["~","certname",".*.com"],"order_by":[{"field":"certname"}],"limit":1}'
