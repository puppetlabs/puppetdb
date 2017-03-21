---
title: "PuppetDB 4.4: Packages entity"
layout: default
canonical: "/puppetdb/latest/api/query/v4/packages.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[subqueries]: ./ast.html#subquery-operators
[facts-format]: ../../wire_format/facts_format_v5.html
[ast]: ./ast.html
[pql]: ./pql.html
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

Package metadata collection, storage, and query is primarily a Puppet Enterprise
feature. This data is queryable from PuppetDB if it is submitted via the
`package_inventory` key in the payload of the `store facts` command, as
described in the [facts format documentation.][facts-format]

## Packages entity (root query endpoint)

Package data is available via the root query endpoint, using the `packages`
entity name in [PQL][pql] or with a `["from" "packages"]` [AST][ast] query.

### Query fields

* `certname` (string): The certname of the node the package data was collected
  from.

* `package_name` (string): The name of the package. (e.g. `emacs24`)

* `version` (string): The version of the package, in the format used by the
  package provider. (e.g. `24.5+1-6ubuntu1`)

* `provider` (string): The name of the provider which the package data came from;
  typically the name of the packaging system. (e.g. `apt`)

### Response format

The response is a JSON array of hashes, where each hash has the form:

    {"certname": <string>,
     "package_name": <string>,
     "version": <string>,
     "provider": <string>}

The array is unsorted by default.

### Example

[You can use `curl`][curl] to query information about nodes:

    curl 'http://localhost:8080/pdb/query/v4' -d  'query=packages {certname="agent1"}'


## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
