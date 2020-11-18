---
title: "Producers endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/producers.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[subqueries]: ./ast.html#subquery-operators
[factsets]: ./factsets.html
[reports]: ./reports.html
[catalogs]: ./catalogs.html

Producers are the Puppet Servers that send reports, catalogs, and factsets to PuppetDB.

When PuppetDB stores a report, catalog, or factset, it keeps track of the producer
of the report/catalog/factset. PuppetDB also keeps a list of producers it has seen.
You can query this list by making an HTTP request to the `/producers` endpoint.

## `/pdb/query/v4/producers`

This will return all producers known to PuppetDB.

### URL parameters

* `query`: optional. A JSON array containing the query in prefix notation. If
  not provided, all results will be returned. See the sections below for the
  supported operators and fields. For general info about queries,
  see [our guide to query structure.][query]

### Query operators

See [the AST query language page](./ast.html)

### Query fields

* `name` (string): the certname of a producer.

### Subquery relationships

The following list contains related entities that can be used to constrain the result set by using implicit subqueries. For more information, consult the documentation for [subqueries][subqueries].

* [`factsets`][factsets]: factsets received for a producer.
* [`reports`][reports]: reports received for a producer.
* [`catalogs`][catalogs]: catalogs received for a producer.

### Response format

The response is a JSON array of hashes, where each hash has the form:

    {"name": <string>}

The array is unsorted.

### Example

[You can use `curl`][curl] to query information about nodes:

    curl 'http://localhost:8080/pdb/query/v4/producers'

## `/pdb/query/v4/producers/<PRODUCER>`

This will return the name of the producer if it currently exists in PuppetDB.

### URL parameters / query operators / query fields

This route supports the same URL parameters and query fields/operators
as the '/pdb/query/v4/producers' route above.

### Response format

The response is a JSON hash of the form:

    {"name": <string>}

### Examples

[You can use `curl`][curl] to query information about nodes like so:

    curl 'http://localhost:8080/pdb/query/v4/producers/server.example.com'

    {
      "name" : "server.example.com"
    }

## `/pdb/query/v4/producers/<PRODUCER>/[catalogs|factsets|reports]`

These routes are identical to issuing a request to
`/pdb/query/v4/[catalogs|factsets|reports]`, with a query
parameter of `["=","producer","<PRODUCER>"]`. All query
parameters and route suffixes from the original routes are
supported. The result format is also the same. Additional query
parameters are ANDed with the producer clause. See
[/pdb/query/v4/catalogs][catalogs], [/pdb/query/v4/factsets][factsets], or
[/pdb/query/v4/reports][reports] for more information.

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
