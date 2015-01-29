---
title: "PuppetDB 2.2 » API » v4 » Querying Nodes"
layout: default
canonical: "/puppetdb/latest/api/query/v4/nodes.html"
---

[resource]: ./resources.html
[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[8601]: http://en.wikipedia.org/wiki/ISO_8601

Nodes can be queried by making an HTTP request to the `/nodes` endpoint.


## `GET /v4/nodes`

This will return all nodes matching the given query. Deactivated nodes
aren't included in the response.

### URL Parameters

* `query`: Optional. A JSON array of query predicates, in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

To query for the latest fact/catalog/report timestamp submitted by 'example.local', the JSON query structure would be:

    ["=", "certname", "example.local"]

### Query Operators

See [the Operators page.](./operators.html)

### Query Fields

The below fields are allowed as filter criteria and are returned in all responses.

* `certname` (string): the name of the node that the report was received from.

* `catalog_environment` (string): the environment for the last received catalog

* `facts_environment` (string): the environment for the last received fact set

* `report_environment` (string): the environment for the last received report

* `catalog_timestamp` (timestamp): last time a catalog was received. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `facts_timestamp` (timestamp): last time a fact set was received. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `report_timestamp` (timestamp): last time a report run was complete. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `["fact", <FACT NAME>]` (string, coercible to number): the value of `<FACT NAME>` for a node. Inequality operators are allowed, and will skip non-numeric values.

    Note that nodes which are missing a fact referenced by a `not` query will match
    the query.


### Response format

The response is a JSON array of hashes, where each hash has the form:

    {"certname": <string>,
     "deactivated": <timestamp or null>,
     "catalog_timestamp": <timestamp or null>,
     "facts_timestamp": <timestamp or null>,
     "report_timestamp": <timestamp or null>,
     "catalog_environment": <string or null>,
     "facts_environment": <string or null>,
     "report_environment": <string or null>}

At least one of the `-timestamp` fields will be non-null.

The array is unsorted.

If no nodes match the query, an empty JSON array will be returned.

### Examples

[You can use `curl`][curl] to query information about nodes like so:

    curl 'http://localhost:8080/v4/nodes'
    curl -G 'http://localhost:8080/v4/nodes' --data-urlencode 'query=["=", ["fact", "kernel"], "Linux"]'

This query will return nodes whose kernel is Linux and whose uptime is less
than 30 days:

    ["and",
      ["=", ["fact", "kernel"], "Linux"],
      [">", ["fact", "uptime_days"], 30]]

## `GET /v4/nodes/<NODE>`

This will return status information for the given node, active or
not. It behaves exactly like a call to `/v4/nodes` with a query string of `["=", "certname", "<NODE>"]`.

### URL Parameters / Query Operators / Query Fields

This route is an extension of the plain `nodes` endpoint. It uses the exact same parameters, operators, and fields.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Response Format

The response is a single hash, of the same form used for the plain `nodes` endpoint:

    {"certname": <string>,
     "deactivated": <timestamp|null>,
     "catalog_timestamp": <timestamp>,
     "facts_timestamp": <timestamp>,
     "report_timestamp": <timestamp>,
     "catalog_environment": <string>,
     "facts_environment": <string>,
     "report_environment": <string>}

If a node of that certname doesn't exist, the response will instead be a hash of the form:

    {"error": "No information is known about <NODE>"}

## `GET /v4/nodes/<NODE>/facts`

[facts]: ./facts.html

This will return the facts for the given node. Facts from deactivated
nodes aren't included in the response.

This is a shortcut to the [`/v4/facts`][facts] endpoint. It behaves the same as a call to [`/v4/facts`][facts] with a query string of `["=", "certname", "<NODE>"]`.

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the `facts` endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.


## `GET /v4/nodes/<NODE>/facts/<NAME>`

This will return facts with the given name for the given node. Facts
from deactivated nodes aren't included in the response.

This is a shortcut to the [`/v4/facts`][facts] endpoint. It behaves the same as a call to [`/v4/facts`][facts] with a query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "name", "<NAME>"]]

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the [`facts`][facts] endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `GET /v4/nodes/<NODE>/facts/<NAME>/<VALUE>`

This will return facts with the given name and value for the given
node. Facts from deactivated nodes aren't included in the
response.

This is a shortcut to the [`/v4/facts`][facts] endpoint. It behaves the same as a call to [`/v4/facts`][facts] with a query string of:

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


## `GET /v4/nodes/<NODE>/resources`

This will return the resources for the given node. Resources from
deactivated nodes aren't included in the response.

This is a shortcut to the [`/v4/resources`][resource] route. It behaves the same as a call to [`/v4/resources`][resource] with a query string of `["=", "certname", "<NODE>"]`.

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the [`resources`][resource] endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `GET /v4/nodes/<NODE>/resources/<TYPE>`

This will return the resources of the indicated type for the given
node. Resources from deactivated nodes aren't included in the
response.

This is a shortcut to the [`/v4/resources/<TYPE>`][resource] route. It behaves the same as a call to [`/v4/resources`][resource] with a query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "type", "<TYPE>"]]

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the [`resources`][resource] endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `GET /v4/nodes/<NODE>/resources/<TYPE>/<TITLE>`

This will return the resource of the indicated type and title for the
given node. Resources from deactivated nodes aren't included in the
response.

This is a shortcut to the [`/v4/resources/<TYPE>/<TITLE>`][resource] route. It behaves the same as a call to [`/v4/resources`][resource] with a query string of:

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
