---
title: "PuppetDB 1.3 » API » v1 » Querying Nodes"
layout: default
canonical: "/puppetdb/latest/api/query/v1/nodes.html"
---

[resource]: ./resources.html
[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

Nodes can be queried by making an HTTP request to the `/nodes` REST
endpoint with a JSON-formatted parameter called `query`.

## Query format

* The HTTP method must be `GET`.
* There must be an `Accept` header specifying `application/json`.

The `query` parameter uses a format similar to [resource queries][resource].

Only queries against facts and filters based on node activeness are currently
supported.

These query terms must be of the form `["fact", "<fact name>"]` or `["node", "active"]`,
respectively.

Accepted operators are: `[= > < >= <= and or not]`

Inequality operators are strictly arithmetic, and will ignore any fact values
which are not numeric.

Note that nodes which are missing a fact referenced by a `not` query will match
the query.

In this example, the query will return active nodes whose kernel is Linux and whose uptime is less
than 30 days:

    ["and",
      ["=", ["node", "active"], true],
      ["=", ["fact", "kernel"], "Linux"],
      [">", ["fact", "uptime_days"], 30]]

If no `query` parameter is supplied, all nodes will be returned.

## Response format

The response is a JSON array of node names that match the predicates, sorted
in ascending order:

`["foo.example.com", "bar.example.com", "baz.example.com"]`

## Example

[Using `curl` from localhost][curl]:

Retrieving all nodes:

    curl -H "Accept: application/json" 'http://localhost:8080/nodes'

Retrieving all active nodes:

    curl -G -H "Accept: application/json" 'http://localhost:8080/nodes' --data-urlencode 'query=["=", ["node", "active"], true]'
