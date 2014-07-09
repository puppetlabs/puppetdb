---
title: "PuppetDB 2.1 » API » v3 » Querying Nodes"
layout: default
canonical: "/puppetdb/latest/api/query/v3/nodes.html"
---

[resource]: ./resources.html
[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html

Nodes can be queried by making an HTTP request to the `/nodes` endpoint.



## `GET /v3/nodes`

This will return all nodes matching the given query. Deactivated nodes
aren't included in the response.

### URL Parameters

* `query`: Optional. A JSON array of query predicates, in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

    The `query` parameter is a similar format to [resource queries][resource].

    If no `query` parameter is supplied, all nodes will be returned.

### Query Querators

Node query supports [all available operators](./operators.html). Inequality
operators are only supported for fact queries, but regular expressions are
supported for both name and facts.

Inequality operators are strictly arithmetic, and will ignore any fact values
which are not numeric.

Note that nodes which are missing a fact referenced by a `not` query will match
the query.

### Query Fields

Only queries against `"name"` and facts are currently supported.

Fact terms must be of the form `["fact", <fact name>]`.

### Response format

The response is a JSON array of hashes of the form:

    {"name": <string>,
     "deactivated": <timestamp>,
     "catalog_timestamp": <timestamp>,
     "facts_timestamp": <timestamp>,
     "report_timestamp": <timestamp>}

The array is sorted alphabetically by `name`.

### Examples

[You can use `curl`][curl] to query information about nodes like so:

    curl 'http://localhost:8080/v3/nodes'
    curl -G 'http://localhost:8080/v3/nodes' --data-urlencode 'query=["=", ["fact", "kernel"], "Linux"]'

This query will return nodes whose kernel is Linux and whose uptime is less
than 30 days:

    ["and",
      ["=", ["fact", "kernel"], "Linux"],
      [">", ["fact", "uptime_days"], 30]]

## `GET /v3/nodes/<NODE>`

This will return status information for the given node, active or
not. It behaves exactly like a call to `/v3/nodes` with a query string of `["=", "certname", "<NODE>"]`.

### URL Parameters / Query Operators / Query Fields

This route is an extension of the plain `nodes` endpoint. It uses the exact same parameters, operators, and fields.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Response Format

The response is a single hash, of the same form used for the plain `nodes` endpoint:

    {"name": <string>,
     "deactivated": <timestamp>,
     "catalog_timestamp": <timestamp>,
     "facts_timestamp": <timestamp>,
     "report_timestamp": <timestamp>}

If a node of that certname doesn't exist, the response will instead be a hash of the form:

    {"error": "No information is known about <NODE>"}

## `GET /v3/nodes/<NODE>/facts`

[facts]: ./facts.html

This will return the facts for the given node. Facts from deactivated
nodes aren't included in the response.

This is a shortcut to the [`/v3/facts`][facts] endpoint. It behaves the same as a call to [`/v3/facts`][facts] with a query string of `["=", "certname", "<NODE>"]`.

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the `facts` endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.


## `GET /v3/nodes/<NODE>/facts/<NAME>`

This will return facts with the given name for the given node. Facts
from deactivated nodes aren't included in the response.

This is a shortcut to the [`/v3/facts`][facts] endpoint. It behaves the same as a call to [`/v3/facts`][facts] with a query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "name", "<NAME>"]]

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the [`facts`][facts] endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `GET /v3/nodes/<NODE>/facts/<NAME>/<VALUE>`

This will return facts with the given name and value for the given
node. Facts from deactivated nodes aren't included in the
response.

This is a shortcut to the [`/v3/facts`][facts] endpoint. It behaves the same as a call to [`/v3/facts`][facts] with a query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "name", "<NAME>"],
        ["=", "value", "<VALUE>"]]

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the [`facts`][facts] endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

(However, for this particular route, there aren't any practical criteria left.)


## `GET /v3/nodes/<NODE>/resources`

This will return the resources for the given node. Resources from
deactivated nodes aren't included in the response.

This is a shortcut to the [`/v3/resources`][resource] route. It behaves the same as a call to [`/v3/resources`][resource] with a query string of `["=", "certname", "<NODE>"]`.

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the [`resources`][resource] endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `GET /v3/nodes/<NODE>/resources/<TYPE>`

This will return the resources of the indicated type for the given
node. Resources from deactivated nodes aren't included in the
response.

This is a shortcut to the [`/v3/resources/<TYPE>`][resource] route. It behaves the same as a call to [`/v3/resources`][resource] with a query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "type", "<TYPE>"]]

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the [`resources`][resource] endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `GET /v3/nodes/<NODE>/resources/<TYPE>/<TITLE>`

This will return the resource of the indicated type and title for the
given node. Resources from deactivated nodes aren't included in the
response.

This is a shortcut to the [`/v3/resources/<TYPE>/<TITLE>`][resource] route. It behaves the same as a call to [`/v3/resources`][resource] with a query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "type", "<TYPE>"],
        ["=", "title", "<TITLE>"]]

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the [`resources`][resource] endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.


## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters.  For more information, please see the documentation
on [paging][paging].
