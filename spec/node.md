# Nodes

## v2

### Routes

#### `GET /v2/nodes`

This will return all nodes matching the given query. Deactivated nodes
aren't included in the response. There must be an `Accept` header
containing `application/json`.

##### Parameters

  `query`: Required. A JSON array of query predicates, in prefix form,
  conforming to the format described below.

The `query` parameter is a similar format to resource queries.

Only queries against facts are currently supported.

These terms must be of the form `["fact", <fact name>]`.

Accepted operators are: `[= > < >= <= and or not]`

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

##### Response format

The response is a JSON array of node names matching the predicates, sorted
in ascending order:

`["foo.example.com", "bar.example.com", "baz.example.com"]`

##### Example

[You can use `curl`](curl.md) to query information about nodes like so:

    curl -H "Accept: application/json" 'http://localhost:8080/nodes'
    curl -G -H "Accept: application/json" 'http://localhost:8080/nodes' --data-urlencode 'query=["=", ["fact", "kernel"], "Linux"]'

#### `GET /v2/nodes/:node`

This will return status information for the given node, active or
not. There must be an `Accept` header containing `application/json`.

##### Response format

The response is the same format as for the [/v2/status](status.md)
endpoint.

#### `GET /v2/nodes/:node/facts`

This will return the facts for the given node. Facts from deactivated
nodes aren't included in the response. There must be an `Accept`
header containing `application/json`.

##### Parameters

  `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/v2/facts` route. When supplied, the query is
  assumed to supply _additional_ criteria that can be used to return a
  _subset_ of the information normally returned by this route.

##### Response format

The response is the same format as for the [/v2/facts](facts.md)
endpoint.

#### `GET /v2/nodes/:node/facts/:name`

This will return facts with the given name for the given node. Facts
from deactivated nodes aren't included in the response. There must be
an `Accept` header containing `application/json`.

##### Parameters

  `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/v2/facts` route. When supplied, the query is
  assumed to supply _additional_ criteria that can be used to return a
  _subset_ of the information normally returned by this route.

##### Response format

The response is the same format as for the [/v2/facts](facts.md)
endpoint.


#### `GET /v2/nodes/:node/facts/:name/:value`

This will return facts with the given name and value for the given
node. Facts from deactivated nodes aren't included in the
response. There must be an `Accept` header containing
`application/json`.

##### Parameters

  `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/v2/facts` route. When supplied, the query is
  assumed to supply _additional_ criteria that can be used to return a
  _subset_ of the information normally returned by this route.

##### Response format

The response is the same format as for the [/v2/facts](facts.md)
endpoint.

#### `GET /v2/nodes/:node/resources`

This will return the resources for the given node. Resources from
deactivated nodes aren't included in the response. There must be an
`Accept` header containing `application/json`.

##### Parameters

  `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/v2/resources` route. When supplied, the query is
  assumed to supply _additional_ criteria that can be used to return a
  _subset_ of the information normally returned by this route.

##### Response format

The response is the same format as for the [/v2/resources](resource.md)
endpoint.

#### `GET /v2/nodes/:node/resources/:type`

This will return the resources of the indicated type for the given
node. Resources from deactivated nodes aren't included in the
response. There must be an `Accept` header containing
`application/json`.

This endpoint behaves identically to the
[/v2/resources/:type](resource.md) endpoint, except the resources
returned include _only_ those belonging to the node given in the URL
for this route.

#### `GET /v2/nodes/:node/resources/:type/:title`

This will return the resource of the indicated type and title for the
given node. Resources from deactivated nodes aren't included in the
response. There must be an `Accept` header containing
`application/json`.

This endpoint behaves identically to the
[/v2/resources/:type](resource.md) endpoint, except the resources
returned include _only_ those belonging to the node given in the URL
for this route.
