---
title: "PuppetDB 1.6 » API » v4 » Querying Nodes"
layout: default
canonical: "/puppetdb/latest/api/query/v4/nodes.html"
---

[resource]: ./resources.html
[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html

Nodes can be queried by making an HTTP request to the `/nodes` REST
endpoint with a JSON-formatted parameter called `query`.

> **Note:** The v4 API is experimental and may change without notice. For stability, it is recommended that you use the v3 API instead.

## Routes

### `GET /v4/nodes`

This will return all nodes matching the given query. Deactivated nodes
aren't included in the response.

#### Parameters

* `query`: Optional. A JSON array of query predicates, in prefix form,
  conforming to the format described below.

The `query` parameter is a similar format to [resource queries][resource].

Only queries against `"name"`, `"catalog-environment"`, `"facts-environment"`, `"report-environment"` and facts are currently supported.

Fact terms must be of the form `["fact", <fact name>]`.

Node query supports [all available operators](./operators.html). Inequality
operators are only supported for fact queries, but regular expressions are
supported for all valid query parameters.

Inequality operators are strictly arithmetic, and will ignore any fact values
which are not numeric.

Note that nodes which are missing a fact referenced by a `not` query will match
the query.

This query will return nodes whose kernel is Linux and whose uptime is less
than 30 days:

    ["and",
      ["=", ["fact", "kernel"], "Linux"],
      [">", ["fact", "uptime_days"], 30]]

If no `query` parameter is supplied, all nodes will be returned.

#### Response format

The response is a JSON array of hashes of the form:

    {"name": <string>,
     "deactivated": <timestamp>,
     "catalog-timestamp": <timestamp>,
     "facts-timestamp": <timestamp>,
     "report-timestamp": <timestamp>,
     "catalog-environment": <string>,
     "facts-environment": <string>,
     "report-environment": <string}

The array is unsorted.

##### Fields

Valid fields returned by the query are as follows.

`name`
: the name of the node

`catalog-timestamp`
: last time a catalog was received

`facts-timestamp`
: last time a fact set was received

`report-timestamp`
: last time a report run was complete

`catalog-environment`
: the environment for the last received catalog

`facts-environment`
: the environment for the last received fact set

`report-environment`
: the environment for the last received report

#### Example

[You can use `curl`][curl] to query information about nodes like so:

    curl 'http://localhost:8080/v4/nodes'
    curl -G 'http://localhost:8080/v4/nodes' --data-urlencode 'query=["=", ["fact", "kernel"], "Linux"]'

### `GET /v4/nodes/<NODE>`

This will return status information for the given node, active or
not.

#### Response format

The response is a JSON array of node names that match the predicates, sorted
in ascending order:

`["foo.example.com", "bar.example.com", "baz.example.com"]`

### `GET /v4/nodes/<NODE>/facts`

This will return the facts for the given node. Facts from deactivated
nodes aren't included in the response.

#### Parameters

* `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/v4/facts` route. When supplied, the query is
  assumed to supply _additional_ criteria that can be used to return a
  _subset_ of the information normally returned by this route.

#### Response format

The response is the same format as for the [/v4/facts](./facts.html)
endpoint.

### `GET /v4/nodes/<NODE>/facts/<NAME>`

This will return facts with the given name for the given node. Facts
from deactivated nodes aren't included in the response.

#### Parameters

* `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/v4/facts` route. When supplied, the query is
  assumed to supply _additional_ criteria that can be used to return a
  _subset_ of the information normally returned by this route.

#### Response format

The response is the same format as for the [/v4/facts](./facts.html)
endpoint.


### `GET /v4/nodes/<NODE>/facts/<NAME>/<VALUE>`

This will return facts with the given name and value for the given
node. Facts from deactivated nodes aren't included in the
response.

#### Parameters

* `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/v4/facts` route. When supplied, the query is
  assumed to supply _additional_ criteria that can be used to return a
  _subset_ of the information normally returned by this route.

#### Response format

The response is the same format as for the [/v4/facts](./facts.html)
endpoint.

### `GET /v4/nodes/<NODE>/resources`

This will return the resources for the given node. Resources from
deactivated nodes aren't included in the response.

#### Parameters

* `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/v4/resources` route. When supplied, the query is
  assumed to supply _additional_ criteria that can be used to return a
  _subset_ of the information normally returned by this route.

#### Response format

The response is the same format as for the [/v4/resources][resource]
endpoint.

### `GET /v4/nodes/<NODE>/resources/<TYPE>`

This will return the resources of the indicated type for the given
node. Resources from deactivated nodes aren't included in the
response.

This endpoint behaves identically to the
[`/v4/resources/<TYPE>`][resource] endpoint, except the resources
returned include _only_ those belonging to the node given in the URL
for this route.

### `GET /v4/nodes/<NODE>/resources/<TYPE>/<TITLE>`

This will return the resource of the indicated type and title for the
given node. Resources from deactivated nodes aren't included in the
response.

This endpoint behaves identically to the
[`/v4/resources/<TYPE>`][resource] endpoint, except the resources
returned include _only_ those belonging to the node given in the URL
for this route.

## Paging

This query endpoint supports paged results via the common PuppetDB paging
query parameters.  For more information, please see the documentation
on [paging][paging].

