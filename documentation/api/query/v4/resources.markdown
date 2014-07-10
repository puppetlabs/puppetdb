---
title: "PuppetDB 2.1 » API » v4 » Querying Resources"
layout: default
canonical: "/puppetdb/latest/api/query/v4/resources.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html

You can query resources by making an HTTP request to the
`/resources` endpoint.

> **Note:** The v4 API is experimental and may change without notice. For stability, we recommend that you use the v3 API instead.


## `GET /v4/resources`

This will return all resources matching the given query. Resources for
deactivated nodes are not included in the response.

### URL Parameters

* `query`: Optional. A JSON array of query predicates, in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

    If no query is provided, all resources will be returned.

### Query Operators

See [the Operators page](./operators.html) for the full list of available operators. Note that:

* The inequality operators are only supported for the `line` field.
* Regexp matching is **not** supported against parameter values.

### Query Fields

* `tag` (string): a case-insensitive tag on the resource. (Appears in the response as `tags`, which is an array of strings.)

* `certname` (string): the name of the node associated with the resource.

* `[parameter <PARAMETER NAME>]` (string): the value of the `<PARAMETER NAME>` parameter of the resource.

* `type` (string, with first letter always capitalized): the resource type.

* `title` (string): the resource title.

* `exported` (boolean): whether or not the resource is exported.

* `file` (string): the manifest file the resource was declared in.

* `line` (number): the line of the manifest on which the resource was declared.

* `environment` (string): the environment of the node associated to the resource.


### Response format

An array of zero or more resource objects, with each object having the
following form:

    {"certname":   "the certname of the associated host",
     "resource":   "the resource's unique hash",
     "type":       "File",
     "title":      "/etc/hosts",
     "exported":   "true",
     "tags":       ["foo", "bar"],
     "file": "/etc/puppet/manifests/site.pp",
     "line": "1",
     "environment": "production",
     "parameters": {<parameter>: <value>,
                   <parameter>: <value>,
                   ...}}

### Examples

For file resources tagged "magical", on any host except
for "example.local," the JSON query structure would be:

    ["and", ["not", ["=", "certname", "example.local"]],
            ["=", "type", "File"],
            ["=", "tag", "magical"],
            ["=", ["parameter", "ensure"], "enabled"]


## `GET /v4/resources/<TYPE>`

This will return all resources for all nodes with the given
type. Resources from deactivated nodes aren't included in the
response.

This behaves exactly like a call to `/v4/resources` with a query string of `["=", "type", "<TYPE>"]`.

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the plain `resources` endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Examples

[Using `curl` from localhost][curl]:

    curl -X GET 'http://puppetdb:8080/v4/resources/User'

    [{"parameters" : {
        "uid" : "1000,
        "shell" : "/bin/bash",
        "managehome" : false,
        "gid" : "1000,
        "home" : "/home/foo,
        "groups" : "users,
        "ensure" : "present"
      },
      "line" : 10,
      "file" : "/etc/puppet/manifests/site.pp",
      "exported" : false,
      "environment": "production",
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
      "line" : 20,
      "file" : "/etc/puppet/manifests/site.pp",
      "exported" : false,
      "environment": "production",
      "tags" : [ "foo", "bar" ],
      "title" : "bar",
      "type" : "User",
      "certname" : "host2.mydomain.com"}]

## `GET /v4/resources/<TYPE>/<TITLE>`

This will return all resources for all nodes with the given type and
title. Resources from deactivated nodes aren't included in the
response.

This behaves exactly like a call to `/v4/resources` with a query string of:

    ["and",
        ["=", "type", "<TYPE>"],
        ["=", "title", "<TITLE>"]]

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the plain `resources` endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Examples

[Using `curl` from localhost][curl]:

    curl -X GET 'http://puppetdb:8080/v4/resources/User/foo'

    [{"parameters" : {
        "uid" : "1000,
        "shell" : "/bin/bash",
        "managehome" : false,
        "gid" : "1000,
        "home" : "/home/foo,
        "groups" : "users,
        "ensure" : "present"
      },
      "line" : 10,
      "file" : "/etc/puppet/manifests/site.pp",
      "exported" : false,
      "environment": "production",
      "tags" : [ "foo", "bar" ],
      "title" : "foo",
      "type" : "User",
      "certname" : "host1.mydomain.com"
    }]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters.  For more information, please see the documentation
on [paging][paging].

