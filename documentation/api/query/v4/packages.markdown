---
title: "Package endpoints"
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

> **PE feature**: Package metadata collection, storage, and querying is
> a Puppet Enterprise-only feature.

## `/pdb/query/v4/packages`

Returns all installed packages, across all nodes. One record is returned for
each `(package_name, version, provider)` combination that exists in your
infrastructure.

### Query fields

* `package_name` (string): The name of the package. (e.g. `emacs24`)

* `version` (string): The version of the package, in the format used by the
  package provider. (e.g. `24.5+1-6ubuntu1`)

* `provider` (string): The name of the provider which the package data came from;
  typically the name of the packaging system. (e.g. `apt`)

### Response format

The response is a JSON array of hashes, where each hash has the form:

    {"package_name": <string>,
     "version": <string>,
     "provider": <string>}

The array is unsorted by default.

### Example

[You can use `curl`][curl] or `puppet query` to query information about packages:

    puppet query "packages { package_name ~ 'ssl'}"

    curl -G http://localhost:8080/pdb/query/v4/packages --data-urlencode 'query=["~", "package_name", "ssl"]'


## `/pdb/query/v4/package-inventory`

Returns all installed packages along with the certname of the nodes they are
installed on.

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

[You can use `curl`][curl] or `puppet query` to query information about nodes:

    puppet query "package_inventory{ certname = 'agent1' }"

    curl -G http://localhost:8080/pdb/query/v4/package-inventory --data-urlencode 'query=["=", "certname", "agent1"]'

    puppet query "package_inventory[certname]{ package_name ~ 'openssl' and version ~ '1\.0\.1[\-a-f]' }"

## `/pdb/query/v4/package-inventory/<CERTNAME>`

This will return all packages installed on the provided certname. It behaves
exactly like a call to `/pdb/query/v4/packages` with a query string of `["=",
"certname", <CERTNAME>]`.

## Paging

These query endpoints support paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
