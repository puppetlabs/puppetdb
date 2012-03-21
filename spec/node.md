# Nodes

Querying nodes is accomplished by making an HTTP request to the `/nodes` REST
endpoint with a JSON-formatted parameter called `query`.

# Query format

* The HTTP method must be `GET`.

* There must be an `Accept` header specifying `application/json`.

The `query` parameter is a similar format to resource queries.

Only queries against facts are currently supported, and these terms must be of
the form `["fact" <fact name>]`.

Accepted operators are: `[= > < >= <= and or not]`

Inequality operators are strictly arithmetic, and will ignore any fact values
which are not numeric.

Note that nodes which are missing a fact referenced by a `not` query will match
the query.

This query will return nodes whose kernel is Linux and whose uptime is less
than 30 days:

    ["and"
      ["=" ["fact" "kernel"] "Linux"]
      [">" ["fact" "uptime_days"] 30]]

If no `query` parameter is supplied, all nodes will be returned.

# Response format

The response is a JSON array of node names matching the predicates, sorted
in ascending order:

`["foo.example.com" "bar.example.com" "baz.example.com"]`
