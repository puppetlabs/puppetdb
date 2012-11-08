# Resources

Querying resources is accomplished by making an HTTP request to the
`/resources` REST endpoint.

# Query format

Queries for resources must conform to the following format:

* A `GET` is used.

* There is a single parameter, `query`.

* There is an `Accept` header containing `application/json`.

* The `query` parameter is a JSON array of query predicates, in prefix
  form, conforming to the format described below.

The `query` parameter is described by the following grammar:

    query: [ {type} {query}+ ] | [ {match} {field} {value} ]
    field:  string | [ string+ ]
    value:  string
    type:   "or" | "and" | "not"
    match:  "=" | "~"

`field` may be any of:

`tag`
: a case-insensitive tag on the resource

`certname`
: the name of the node associated with the resource

`[node active]`
: `true` if the node has not been deactivated, `false` if it has

`[parameter <resource_param>]`
: a parameter of the resource

`type`
: the resource type

`title`
: the resource title

`exported`
: whether or not the resource is exported

`sourcefile`
: the manifest file the resource was declared in

`sourceline`
: the line of the manifest on which the resource was declared

For example, for file resources, tagged "magical", on any active host except
for "example.local" the JSON query structure would be:

    ["and", ["not", ["=", "certname", "example.local"]],
            ["=", ["node", "active"], true],
            ["=", "type", "File"],
            ["=", "tag", "magical"],
            ["=", ["parameter", "ensure"], "enabled"]

The available operators are [defined in operators.md](operators.md). Note that
resource queries *do not support* inequality, and regexp matching *is not
supported* against node status or parameter values.

# Response format

An array of zero or more resource objects, with each object having the
following form:

    {"certname":   "the certname of the associated host",
     "resource":   "the resource's unique hash",
     "type":       "File",
     "title":      "/etc/hosts",
     "exported":   "true",
     "tags":       ["foo", "bar"],
     "sourcefile": "/etc/puppet/manifests/site.pp",
     "sourceline": "1",
     "parameters": {<parameter>: <value>,
                   <parameter>: <value>,
                   ...}}

# Example

[You can use `curl`](curl.md) to query information about resources like so:

    curl -G -H "Accept: application/json" 'http://localhost:8080/resources' --data-urlencode 'query=["and", ["=", "type", "File"], ["=", "title", "/etc/ipsec.conf"]]'
