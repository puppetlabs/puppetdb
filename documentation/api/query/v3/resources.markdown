---
title: "PuppetDB 1.4 » API » v3 » Querying Resources"
layout: default
canonical: "/puppetdb/latest/api/query/v3/resources.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

Resources are queried via an HTTP request to the
`/resources` REST endpoint.


## Routes

### `GET /v3/resources`

This will return all resources matching the given query. Resources for
deactivated nodes are not included in the response. There must be an
`Accept` header matching `application/json`.

#### Parameters

* `query`: Optional. A JSON array of query predicates, in prefix form,
  conforming to the format described below. If not provided, all results will
  be returned.

The `query` parameter is described by the following grammar:

    query: [ {bool} {query}+ ] | [ "not" {query} ] | [ {match} {field} {value} ]
    field:  string | [ string+ ]
    value:  string
    bool:   "or" | "and"
    match:  "=" | "~"

`field` may be any of:

`tag`
: a case-insensitive tag on the resource

`certname`
: the name of the node associated with the resource

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

For example, for file resources, tagged "magical", on any host except
for "example.local" the JSON query structure would be:

    ["and", ["not", ["=", "certname", "example.local"]],
            ["=", "type", "File"],
            ["=", "tag", "magical"],
            ["=", ["parameter", "ensure"], "enabled"]

See [the Operators page](./operators.html) for the full list of available operators. Note that
resource queries *do not support* inequality, and regexp matching *is not
supported* against node status or parameter values.

### `GET /v3/resources/<TYPE>`

This will return all resources for all nodes with the given
type. Resources from deactivated nodes aren't included in the
response. There must be an `Accept` header matching
`application/json`.

#### Parameters

* `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/resources` route, mentioned above. When
  supplied, the query is assumed to supply _additional_ criteria that
  can be used to return a _subset_ of the information normally
  returned by this route.

#### Examples

[Using `curl` from localhost][curl]:

    curl -X GET 'http://puppetdb:8080/v3/resources/User'

    [{"parameters" : {
        "uid" : "1000,
        "shell" : "/bin/bash",
        "managehome" : false,
        "gid" : "1000,
        "home" : "/home/foo,
        "groups" : "users,
        "ensure" : "present"
      },
      "sourceline" : 10,
      "sourcefile" : "/etc/puppet/manifests/site.pp",
      "exported" : false,
      "tags" : [ "foo", "bar" ],
      "title" : "foo",
      "type" : "User",
      "certname" : "host1.mydomain.com"
    }, {"parameters" : {
        "uid" : "1001,
        "shell" : "/bin/bash",
        "managehome" : false,
        "gid" : "1001,
        "home" : "/home/bar,
        "groups" : "users,
        "ensure" : "present"
      },
      "sourceline" : 20,
      "sourcefile" : "/etc/puppet/manifests/site.pp",
      "exported" : false,
      "tags" : [ "foo", "bar" ],
      "title" : "bar",
      "type" : "User",
      "certname" : "host2.mydomain.com"}]

### `GET /v3/resources/<TYPE>/<TITLE>`

This will return all resources for all nodes with the given type and
title. Resources from deactivated nodes aren't included in the
response. There must be an `Accept` header matching
`application/json`.

#### Parameters

* `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/resources` route, mentioned above. When
  supplied, the query is assumed to supply _additional_ criteria that
  can be used to return a _subset_ of the information normally
  returned by this route.

#### Examples

[Using `curl` from localhost][curl]:

    curl -X GET 'http://puppetdb:8080/v3/resources/User/foo'

    [{"parameters" : {
        "uid" : "1000,
        "shell" : "/bin/bash",
        "managehome" : false,
        "gid" : "1000,
        "home" : "/home/foo,
        "groups" : "users,
        "ensure" : "present"
      },
      "sourceline" : 10,
      "sourcefile" : "/etc/puppet/manifests/site.pp",
      "exported" : false,
      "tags" : [ "foo", "bar" ],
      "title" : "foo",
      "type" : "User",
      "certname" : "host1.mydomain.com"
    }]

## Response format

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
