---
title: "Fact-paths endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/fact-paths.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[fact-names]: ./fact-names.html
[subqueries]: ./ast.html#subquery-operators
[ast]: ./ast.html
[facts]: ./facts.html
[fact-contents]: ./fact-contents.html

The `/fact-paths` endpoint retrieves the set of all known fact paths for all
known nodes, and is intended as a counterpart to the [fact-names][fact-names]
endpoint, providing increased granularity around structured facts. The endpoint
may be useful for building autocompletion in GUIs or for other applications
that require a basic top-level view of fact paths.

## `/pdb/query/v4/fact-paths`

This will return all fact paths matching the given query.

### URL parameters

* `query`: optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [our guide to query structure.][query]

If a query parameter is not provided, all results will be returned.

### Query operators

See [the AST query language page][ast].

## Query fields

* `path` (path): the path associated with a fact node.
* `type` (string): the type of the value a the fact node.
* `depth` (integer): the depth of the paths returned

### Subquery relationships

The following list contains related entities that can be used to constrain the result set using implicit subqueries. For more information consult the documentation for [subqueries][subqueries].

* [`facts`][facts]: all facts that contain a fact-path.
* [`fact_contents`][fact-contents]: all factset paths and values using a fact-path.

## Response format

Successful responses will be in `application/json`. Errors will be returned as
a non-JSON string.

The result will be a JSON array with one entry per certname. Each entry is of
the form:

    {
      "path": <fact-node path>
      "type": <fact-node type>
    }

### Examples

[Using `curl` from localhost][curl]:

Get all fact paths that match the regex array query for paths related to
partition sda3:

    curl -X GET http://localhost:8080/pdb/query/v4/fact-paths \
      --data-urlencode 'query=["~>", "path", ["partitions", "sda3.*", ".*"]]'

    [ {
      "path" : [ "partitions", "sda3", "mount" ],
      "type" : "string"
    }, {
      "path" : [ "partitions", "sda3", "size" ],
      "type" : "string"
    }, {
      "path" : [ "partitions", "sda3", "uuid" ],
      "type" : "string"
    } ]

Get all fact paths of integer type:

    curl -X GET http://localhost:8080/pdb/query/v4/fact-paths \
      --data-urlencode 'query=["=", "type", "integer"]'

    [ {
      "path" : [ "blockdevice_sda_size" ],
      "type" : "integer"
    }, {
      "path" : [ "uptime_days" ],
      "type" : "integer"
    }, {
      "path" : [ "blockdevice_sdb_size" ],
      "type" : "integer"
    }, {
      "path" : [ "uptime_seconds" ],
      "type" : "integer"
    }, {
      "path" : [ "uptime_hours" ],
      "type" : "integer"
    } ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging URL
parameters. For more information, please see the documentation on
[paging][paging].
