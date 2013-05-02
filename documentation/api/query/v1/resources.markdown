---
title: "PuppetDB 1.3 » API » v1 » Querying Resources"
layout: default
canonical: "/puppetdb/latest/api/query/v1/resources.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

Resources are queried via an HTTP request to the
`/resources` REST endpoint.

## Query format

Queries for resources must conform to the following format:

* A `GET` is used.
* There is a single parameter, `query`.
* There is an `Accept` header containing `application/json`.
* The `query` parameter is a JSON array of query predicates, in prefix
  form, conforming to the format described below.

The `query` parameter adheres to the following grammar:

    query: [ {type} {query}+ ] | [ {match} {field} {value} ]
    field:  string | [ string+ ]
    value:  string
    type:   "or" | "and" | "not"
    match:  "="

`field` strings may be any of the following:

`tag`
: a case-insensitive tag on the resource

`["node", "name"]`
: the name of the node associated with the resource

`["node", "active"]`
: `true` if the node has not been deactivated, `false` if it has

`["parameter", "<parameter name>"]`
: a parameter of the resource

`type`
: the resource type

`title`
: the resource title

`exported`
: whether or not the resource is exported

`sourcefile`
: the manifest file where the resource was declared

`sourceline`
: the line of the manifest in which the resource was declared

For example, the JSON query structure for file resources, tagged "magical", and present on any active host except
for "example.local" would be:

    ["and", ["not", ["=", ["node", "name"], "example.local"]],
            ["=", ["node", "active"], true],
            ["=", "type", "File"],
            ["=", "tag",  "magical"],
            ["=", ["parameter", "ensure"], "enabled"]]

The following conditionals for type behaviors are defined:

`or`
: If *any* condition is true, the result is true.

`and`
: If *all* conditions are true, the result is true.

`not`
: If *none* of the conditions are true, the result is true.

The following match operator behaviors are defined:

`=`
: Exact string equality of the field and the value.

## Response format

An array of zero or more resource objects, with each object in the
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

## Example

[Using `curl` from localhost][curl]:

Retrieving the resource `File['/etc/ipsec.conf']`:

    curl -G -H "Accept: application/json" 'http://localhost:8080/resources' --data-urlencode 'query=["and", ["=", "type", "File"], ["=", "title", "/etc/ipsec.conf"]]'
