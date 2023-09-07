---
layout: default
title: "API curl tips"
canonical: "/puppetdb/latest/api/query/curl.html"
---

# API curl tips

[curl]: http://curl.haxx.se/docs/manpage.html
[dashboard]: ../../maintain_and_tune.markdown#monitor-the-performance-dashboard
[allowlist]: ../../configure.markdown#certificate-allowlist
[entities]: ./v4/entities.markdown
[pql]: ./tutorial-pql.markdown
[pdb-cli]: ../../pdb_client_tools.markdown

You can use [`curl`][curl] to directly interact with PuppetDB's REST API. This is useful for testing, prototyping, and quickly fetching arbitrary data.

The instructions below are simplified. For full usage details, see [the curl man page][curl]. For additional examples, please see the user guides for the individual [query REST endpoints][entities], or the other REST API services available.

## Using `curl` From `localhost` (non-SSL/HTTP)

With its default settings, PuppetDB accepts unsecured HTTP connections at port 8080 on `localhost`. This allows you to SSH into the PuppetDB server and run curl commands without specifying certificate information:

```sh
curl http://localhost:8080/pdb/query/v4/nodes
```

If you have allowed unsecured access to other hosts in order to [monitor the dashboard][dashboard], these hosts can also use plain HTTP curl commands.

## Using `curl` from remote hosts (SSL/HTTPS)

### Using a certificate/private key pair

To make secured requests from other hosts, you will need to supply the following
via the command line:

* Your site's CA certificate (`--cacert`)
* An SSL certificate signed by your site's Puppet CA (`--cert`)
* The private key for that certificate (`--key`)

Any node managed by Puppet agent will already have all of these, and you can
reuse them for contacting PuppetDB. You can also generate a new cert on the CA
Puppet Server with the `puppet cert generate` command.

> **Note:** If you have turned on [certificate allowlisting][allowlist], you must
make sure to authorize the certificate you are using:
>
> ```
> curl 'https://<your.puppetdb.server>:8081/pdb/query/v4/nodes' \
>   --tlsv1 \
>   --cacert /etc/puppetlabs/puppet/ssl/certs/ca.pem \
>   --cert /etc/puppetlabs/puppet/ssl/certs/<node>.pem \
>   --key /etc/puppetlabs/puppet/ssl/private_keys/<node>.pem
> ```

### Using an RBAC token (Puppet Enterprise® only)

To make secured requests from other hosts, you will need to supply the following
via the command line:

* Your site's CA certificate (`--cacert`)
* An RBAC token with permission to view and/or edit PuppetDB data (`-H 'X-Authentication: <token>'`)

Any node managed by Puppet agent will already have the CA certificate, and you
can reuse the CA certificate for contacting PuppetDB. You can read more about
generating RBAC tokens and how they work in the
[PE documention](https://puppet.com/docs/pe/latest/rbac_token_auth_intro.html).

> **Note:** The token the user is for must have the correct permissions for
viewing (`nodes:view_data:*`) or editing (`nodes:edit_data:*`) node data
depending on the operation.

    curl 'https://<your.puppetdb.server>:8081/pdb/query/v4/nodes' \
      -H "X-Authentication: <token contents>" \
      --tlsv1 \
      --cacert /etc/puppetlabs/puppet/ssl/certs/ca.pem

**Note:** PE 2016.2 users will need to set `client-auth = want` under the
`[jetty]` header of their jetty.ini configuration. Later versions of PE have
this setting managed by the `puppetlabs-puppet_enterprise` module by default.

### Locating Puppet certificate files

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
      --data-urlencode 'query=["=", "node_state", "active"]'

## Pretty querying of PuppetDB

PuppetDB returns unprettified JSON by default. PuppetDB provides the option of
prettifying your JSON responses with the `pretty` parameter. This parameter
accepts a Boolean value (`true` or `false`) to indicate whether the response
should be pretty-printed. Note that pretty printing comes at the cost of
performance on some of our endpoints, such as `/v4/catalogs`, `/v4/reports` and
`/v4/factsets`, due to the storage of some of their data as JSON/JSONB in PostgreSQL.

    curl -X GET http://localhost:8080/pdb/query/v4/nodes \
        --data-urlencode 'pretty=true'

## Querying PuppetDB with POST

PuppetDB supports querying by POST, which is useful for large
queries (exact limits depend on the client and webserver used). POST queries allow you to limit the number of entries in the response. The example below limits the query to return one entry.   

POST queries use the following syntax:

    curl -X POST http://localhost:8080/pdb/query/v4/nodes \
      -H 'Content-Type:application/json' \
      -d '{"query":["~","certname",".*.com"],"order_by":[{"field":"certname"}],"limit":1}'

## Querying PuppetDB based on specific resource attributes

You can use POST to query for a specific resource attribute. Note that this
requires you to escape your quotes (`"`). Alternatively, use the [PuppetDB
CLI][pdb-cli], together with the [Puppet Query Language (PQL)][pql] to make
queries without having to escape characters.

To query for the following resource attributes: 

    resources {
      tag = "foo" and
      exported = true
    } 

Use the following CURL command:

    curl -X POST http://localhost:8080/pdb/query/v4 \
      -H 'Content-Type:application/json' \
      -d '{"query": "resources { tag = \"foo\" and exported = true }"}'
