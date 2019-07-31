---
title: "Environments endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/environments.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[subqueries]: ./ast.html#subquery-operators
[factsets]: ./factsets.html
[reports]: ./reports.html
[catalogs]: ./catalogs.html
[facts]: ./facts.html
[fact-contents]: ./fact-contents.html
[events]: ./events.html
[resources]: ./resources.html
[inventory]: ./inventory.html

Environments are semi-isolated groups of nodes managed by Puppet. Nodes are assigned to environments by their own configuration, or by the Puppet master's external node classifier.

When PuppetDB collects info about a node, it keeps track of the environment the node is assigned to. PuppetDB also keeps a list of environments it has seen. You can query this list by making an HTTP request to the `/environments` endpoint.

## `/pdb/query/v4/environments`

This will return all environments known to PuppetDB.

### URL parameters

* `query`: optional. A JSON array containing the query in prefix notation. If
  not provided, all results will be returned. See the sections below for the
  supported operators and fields. For general info about queries,
  see [our guide to query structure.][query]

### Query operators

See [the AST query language page](./ast.html)

### Query fields

* `name` (string): the name of an environment.

### Subquery relationships

The following list contains related entities that can be used to constrain the result set by using implicit subqueries. For more information, consult the documentation for [subqueries][subqueries].

* [`inventory`][inventory]: inventory for an environment.
* [`factsets`][factsets]: factsets received for an environment.
* [`reports`][reports]: reports received for an environment.
* [`catalogs`][catalogs]: catalogs received for an environment.
* [`facts`][facts]: fact names and values received for an environment.
* [`fact_contents`][fact-contents]: fact paths and values received for an environment.
* [`events`][events]: report events triggered for an environment.
* [`resources`][resources]: catalog resources received for an environment.

### Response format

The response is a JSON array of hashes, where each hash has the form:

    {"name": <string>}

The array is unsorted.

### Example

[You can use `curl`][curl] to query information about nodes:

    curl 'http://localhost:8080/pdb/query/v4/environments'

## `/pdb/query/v4/environments/<ENVIRONMENT>`

This will return the name of the environment if it currently exists in PuppetDB.

### URL parameters / query operators / query fields

This route supports the same URL parameters and query fields/operators
as the '/pdb/query/v4/environments' route above.

### Response format

The response is a JSON hash of the form:

    {"name": <string>}

### Examples

[You can use `curl`][curl] to query information about nodes like so:

    curl 'http://localhost:8080/pdb/query/v4/environments/production'

    {
      "name" : "production"
    }

## `/pdb/query/v4/environments/<ENVIRONMENT>/[events|facts|reports|resources]`

These routes are identical to issuing a request to
`/pdb/query/v4/[events|facts|reports|resources]`, with a query
parameter of `["=","environment","<ENVIRONMENT>"]`. All query
parameters and route suffixes from the original routes are
supported. The result format is also the same. Additional query
parameters are ANDed with the environment clause. See
[/pdb/query/v4/events][events], [/pdb/query/v4/facts][facts],
[/pdb/query/v4/reports][reports], or
[/pdb/query/v4/resources][resources] for more information.

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
