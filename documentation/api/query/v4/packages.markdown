---
title: "PuppetDB 4.4: Packages endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/packages.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[subqueries]: ./ast.html#subquery-operators
[facts-format]: ../../wire_format/facts_format_v5.html
[factsets]: ./factsets.html
[reports]: ./reports.html
[catalogs]: ./catalogs.html
[nodes]: ./nodes.html
[facts]: ./facts.html
[fact_contents]: ./fact_contents.html
[events]: ./events.html
[edges]: ./edges.html
[resources]: ./resources.html
[inventory]: ./inventory.html

> **PE feature**: Package metadata collection, storage, and query is primarily
> a Puppet Enterprise feature. This data is queryable from PuppetDB if it is
> submitted via the `package_inventory` key in the payload of the `store facts`
> command, as described in the [facts format documentation.][facts-format]

## `/pdb/query/v4/packages`

Returns all installed packages, across all nodes, that match the provided
query.

### Query fields

* `certname` (string): The certname of the node the package data was collected
  from.

* `package_name` (string): The name of the package. (e.g. `emacs24`)

* `version` (string): The version of the package, in the format used by the
  package provider. (e.g. `24.5+1-6ubuntu1`)

* `provider` (string): The name of the provider which the package data came from;
  typically the name of the packaging system. (e.g. `apt`)

### Subquery relationships

The following list contains related entities that can be used to constrain the
result set by using implicit subqueries. For more information, consult the
documentation for [subqueries][subqueries].

* [`factsets`] [factsets]
* [`reports`] [reports]
* [`catalogs`] [catalogs]
* [`nodes`] [nodes]
* [`facts`] [facts]
* [`fact_contents`] [fact_contents]
* [`events`] [events]
* [`edges`] [edges]
* [`resources`] [resources]
* [`inventory`] [inventory]
* [`packages`] [packages]

### Response format

The response is a JSON array of hashes, where each hash has the form:

    {"certname": <string>,
     "package_name": <string>,
     "version": <string>,
     "provider": <string>}

The array is unsorted by default.

### Example

[You can use `curl`][curl] to query information about nodes:

    curl 'http://localhost:8080/pdb/query/v4' -d  'query=packages{ certname = "agent1" }'

    curl http://localhost:8080/pdb/query/v4/packages -d 'query=["=", "certname", "agent1"]'

## `/pdb/query/v4/packages/<CERTNAME>

This will return all packages installed on the provided certname. It behaves
exactly like a call to `/pdb/query/v4/packages` with a query string of `["=",
"certname", <CERTNAME>]`.


## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
