---
title: "Fact-contents endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/fact-contents.html"
---

# Fact-contents endpoint

[curl]: ../curl.markdown#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.markdown
[query]: query.markdown
[subqueries]: ./ast.markdown#subquery-operators
[ast]: ./ast.markdown
[environments]: ./environments.markdown
[facts]: ./facts.markdown
[factsets]: ./factsets.markdown
[nodes]: ./nodes.markdown

You can query fact information with greater power by using the `/fact-contents`
endpoint. This endpoint provides the capability to descend into structured
facts and query tree nodes deep within this data by using the concept of paths
and values.

## Paths and Values
Structured facts can be thought of as trees. For example,

    "mountpoints": {
      "/": {
        "available": "6.35 GiB",
        "available_bytes": 6820597760,
        "capacity": "74.05%",
        "device": "/dev/sda2",
        "filesystem": "ext4",
        "options": [ "rw", "relatime", "data=ordered" ],
        "size": "24.48 GiB",
        "size_bytes": 26288123904,
        "used": "18.13 GiB",
        "used_bytes": 19467526144
      },
      "/boot": {
        "available": "472.39 MiB",
        "available_bytes": 495337472,
        "capacity": "7.55%",
        "device": "/dev/sda1",
        "filesystem": "vfat",
        "options": [ "rw", "relatime", "fmask=0022", "dmask=0022",
                     "codepage=437", "iocharset=iso8859-1", "shortname=mixed",
                     "errors=remount-ro" ],
        "size": "510.98 MiB",
        "size_bytes": 535805952,
        "used": "38.59 MiB",
        "used_bytes": 40468480
      }
    }

A `fact path` is an array representing a route from the root of the tree to one
of the leaf values, with successive keys representing descent through hashes,
and integers representing descent through arrays (the integer being the
index, starting at 0). Structured fact leaf values may be hashes, arrays,
integers, floats, strings, or booleans.

In the context of the fact above, the first mount option for the device mounted
at "/" is specified by

    ["mountpoints", "/", "options", 0]

The size of the device mounted at "/boot" is specified by

    ["mountpoints", "/boot", "size"]

By combining `path` and `value` queries on the fact-contents endpoint using an
`and` clause, you can filter results based on the leaf values of structured
facts.

## `/pdb/query/v4/fact-contents`

This will return all fact contents that match the given query.

### URL parameters

* `query`: optional. A JSON array containing the query in prefix notation
  (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the
  supported operators and fields. For general info about queries, see [our
  guide to query structure.][query]

If a query parameter is not provided, all results will be returned.

### Query operators

See [the AST query language page][ast].

## Query fields

* `certname` (string): the certname associated with the factset.
* `environment` (string): the environment associated with the parent fact.
* `name` (string): the name of the parent fact.
* `path` (path): the path of traversal to get to this node.
* `value` (multi): the value of this node.

### Subquery relationships

The following list contains related entities that can be used to constrain the
result set using implicit subqueries. For more information, consult the
documentation for [subqueries][subqueries].

* [`environment`][environments]: the environment for a fact-content.
* [`facts`][facts]: the fact where this a fact-content occurs.

## Response format

Successful responses will be in `application/json`. Errors will be returned as a
non-JSON string.

The result will be a JSON array with one entry per certname. Each entry is of
the form:

    {
      "certname": <node name>,
      "environment": <node environment>,
      "name": <fact name>,
      "path": <path to tree node>,
      "facts": <value of tree node>
    }

### Examples

[Using `curl` from localhost][curl]:

Get the first mount option for the device at "/":

    curl -X GET 'http://localhost:8080/pdb/query/v4/fact-contents' \
    --data-urlencode \
      'query=["=", "path", [ "mountpoints", "/", "options", 0 ]]'

Which returns:

    [ {
        "certname" : "desktop.localdomain",
        "environment" : "production",
        "name" : "mountpoints",
        "path" : [ "mountpoints", "/", "options", 0 ],
        "value" : "rw"
    } ]

Get all nodes with 5 minute load averages greater than 5:

    curl -X GET http://localhost:8080/pdb/query/v4/fact-contents \
      --data-urlencode 'query=["and",["=","path",["load_averages", "5m"]], [">","value", 5]]'

Which returns:

    [ {
        "certname" : "desktop.localdomain",
        "environment" : "production",
        "name" : "load_averages",
        "path" : [ "load_averages", "5m" ],
        "value" : 5.29
    }, {
         "certname" : "foo.com",
         "environment" : "production",
         "name" : "load_averages",
         "path" : [ "load_averages", "5m" ],
         "value" : 5.3
    } ]

To match path elements against regular expressions, use `~>`, the regex array
operator. For example, to get all mac addresses for virtualbox network
interfaces:

    curl -X GET http://localhost:8080/pdb/query/v4/fact-contents \
        --data-urlencode 'query=["~>","path",["networking","interfaces","vboxnet\\d","mac"]]'

Which returns:

    [ {
        "certname" : "desktop.localdomain",
        "environment" : "production",
        "name" : "networking",
        "path" : [ "networking", "interfaces", "vboxnet2", "mac" ],
        "value" : "0a:00:27:00:00:02"
    }, {
        "certname" : "desktop.localdomain",
        "environment" : "production",
        "name" : "networking",
        "path" : [ "networking", "interfaces", "vboxnet0", "mac" ],
        "value" : "0a:00:27:00:00:00"
    }, {
        "certname" : "desktop.localdomain",
        "environment" : "production",
        "name" : "networking",
        "path" : [ "networking", "interfaces", "vboxnet1", "mac" ],
        "value" : "0a:00:27:00:00:01"
    } ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging]. When the `order_by` parameter is set to "value", the
ordering will be lexicographical.
