---
title: "Resources endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/resources.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[subqueries]: ./ast.html#subquery-operators
[ast]: ./ast.html
[catalogs]: ./catalogs.html
[environments]: ./environments.html
[nodes]: ./nodes.html
[dotted]: ./ast.html#dot-notation

You can query resources by making an HTTP request to the
`/resources` endpoint.

## `/pdb/query/v4/resources`

This will return all resources matching the given query. Resources for
deactivated nodes are not included in the response.

### URL parameters

* `query`: optional. A JSON array of query predicates, in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [our guide to query structure.][query]

If no query is provided, all resources will be returned.

Note: This endpoint supports [dot notation][dotted] on the `parameters` field.

### Query operators

See [the AST query language page][ast] for the full list of available operators. Note that:

* The inequality operators are only supported for the `line` field.

### Query fields

* `tag` (string): a case-insensitive tag on the resource. (Appears in the response as `tags`, which is an array of strings.)

* `certname` (string): the name of the node associated with the resource.

* `[parameter, <PARAMETER NAME>]` (string): the value of the `<PARAMETER NAME>` parameter of the resource.

* `type` (string, with first letter always capitalized): the resource type.

* `title` (string): the resource title.

* `exported` (Boolean): whether or not the resource is exported.

* `file` (string): the manifest file in which the resource was declared.

* `line` (number): the line of the manifest on which the resource was declared.

* `environment` (string): the environment of the node associated to the resource.

* `resource` (string): a SHA-1 hash of the resource's type, title, and parameters, for identification.

* `parameters` (json): a JSON hash of the resource's parameters.

### Subquery relationships

The following list contains related entities that can be used to constrain the result set using implicit subqueries. For more information, consult the documentation for [subqueries][subqueries].

* [`catalogs`][catalogs]: the catalog containing a resource.
* [`environments`][environments]: the environment associated with a resource.
* [`nodes`][nodes]: the node associated with a resource.

### Response format

An array of zero or more resource objects, with each object having the
following form:

    {
      "certname": "the certname of the associated host",
       "resource": "the resource's unique hash",
       "type": "File",
       "title": "/etc/hosts",
       "exported": "true",
       "tags": ["foo", "bar"],
       "file": "/etc/puppetlabs/code/environments/production/manifests/site.pp",
       "line": "1",
       "environment": "production",
       "parameters": {<parameter>: <value>,
                      <parameter>: <value>,
                      ...}
    }

### Examples

For file resources tagged "magical", on any host except
for "example.local," the JSON query structure would be:

    ["and", ["not", ["=", "certname", "example.local"]],
            ["=", "type", "File"],
            ["=", "tag", "magical"],
            ["=", ["parameter", "ensure"], "enabled"]]


## `/pdb/query/v4/resources/<TYPE>`

This will return all resources for all nodes with the given
type. Resources from deactivated nodes aren't included in the
response.

This behaves exactly like a call to `/pdb/query/v4/resources` with a
query string of `["=", "type", "<TYPE>"]`.

### URL parameters / query operators / query fields / response format

This route is an extension of the `resources` endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Examples

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/query/v4/resources/User

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
      "file" : "/etc/puppetlabs/code/environments/production/manifests/site.pp",
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
      "resource" : "514cc3d67baf20c1c5e053e6a74b249558031311",
      "file" : "/etc/puppetlabs/code/environments/production/manifests/site.pp",
      "exported" : false,
      "environment": "production",
      "tags" : [ "foo", "bar" ],
      "title" : "bar",
      "type" : "User",
      "certname" : "host2.mydomain.com"}]

    curl -X GET http://localhost:8080/pdb/query/v4/resources -d 'query=["=","parameters.groups", "users"]'

    [{"parameters" : {
        "uid" : "1001,
        "shell" : "/bin/bash",
        "managehome" : false,
        "gid" : "1001,
        "home" : "/home/bar,
        "groups" : "users,
        "ensure" : "present"
     },
     "line" : 20,
     "resource" : "514cc3d67baf20c1c5e053e6a74b249558031311",
     "file" : "/etc/puppetlabs/code/environments/production/manifests/site.pp",
     "exported" : false,
     "environment": "production",
     "tags" : [ "foo", "bar" ],
     "title" : "bar",
     "type" : "User",
     "certname" : "host2.mydomain.com"}]

## `/pdb/query/v4/resources/<TYPE>/<TITLE>`

This will return all resources for all nodes with the given type and
title. Resources from deactivated nodes aren't included in the
response.

This behaves exactly like a call to `/pdb/query/v4/resources` with a
query string of:

    ["and",
        ["=", "type", "<TYPE>"],
        ["=", "title", "<TITLE>"]]

### URL parameters / query operators / query fields / response format

This route is an extension of the `resources` endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Examples

[Using `curl` from localhost][curl]:

    curl -X GET 'http://localhost:8080/pdb/query/v4/resources/User/foo'

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
      "resource" : "514cc3d67baf20c1c5e053e6a74b249558031311",
      "file" : "/etc/puppetlabs/code/environments/production/manifests/site.pp",
      "exported" : false,
      "environment": "production",
      "tags" : [ "foo", "bar" ],
      "title" : "foo",
      "type" : "User",
      "certname" : "host1.mydomain.com"
    }]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].

