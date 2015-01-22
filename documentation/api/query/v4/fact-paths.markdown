---
title: "PuppetDB 2.2 » API » v4 » Querying Fact Paths"
layout: default
canonical: "/puppetdb/latest/api/query/v4/fact-paths.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[fact-names]: ./query/v4/fact-names.html

The `/fact-paths` endpoint retrieves the set of all known fact paths for all
known nodes, and is intended with the introduction of structured facts as the
successor to the [fact-names][fact-names] endpoint.  The endpoint may be useful for
building autocompletion in GUIs or for other applications that require a
basic top-level view of fact paths.

## `GET /fact-paths`

This will return all fact paths matching the given query.

### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

If a query parameter is not provided, all results will be returned.

### Query Operators

See [the Operators page.](./operators.html)

## Query Fields

* `path` (path): the path associated with a fact node
* `type` (string): the type of the value a the fact node

## Response Format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON string.

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

    curl -X GET http://localhost:8080/v4/fact-paths --data-urlencode 'query=["~>", "path", ["partitions", "sda3.*", ".*"]]'

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

    curl -X GET http://localhost:8080/v4/fact-paths --data-urlencode 'query=["=", "type", "integer"]'

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
parameters.  For more information, please see the documentation on
[paging][paging].
