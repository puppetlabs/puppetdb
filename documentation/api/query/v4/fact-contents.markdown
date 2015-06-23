---
title: "PuppetDB 3.0 » API » v4 » Querying Fact Contents"
layout: default
canonical: "/puppetdb/latest/api/query/v4/fact-contents.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html

You can query fact information with greater power by using the '/fact-contents' endpoint. This endpoint provides the capability to descend into structured facts and query tree nodes deep within this data by using the concept of paths and values.

Structured fact data is normally represented as a hash, which allows hashes, arrays and real types as its values as you can see in this example:

    {
      "cpus" : {
        "cpu1" : {
          "bogomips": 6000,
        }
      },
      "networking" : {
        "eth0" : {
          "ipaddresses" : [ "1.1.1.5" ],
          "macaddresses" : [ "aa:bb:cc:dd:ee:00" ]
        }
      }
    }

With the fact points endpoint you can query data at a particular part of the tree using the `path` field, and then either analyze or filter on the `value` of that node.

### `GET /pdb/query/v4/fact-contents`

This will return all fact contents that match the given query.

### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

    If a query parameter is not provided, all results will be returned.

### Query Operators

See [the Operators page.](./operators.html)

### Query Fields

* `certname` (string): the certname associated with the factset
* `environment` (string): the environment associated with the parent fact
* `name` (string): the name of the parent fact
* `path` (path): the path of traversal to get to this node
* `value` (multi): the value of this node

### Response Format

Successful responses will be in `application/json`. Errors will be returned as
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

Get the first mac address for eth0:

    curl -X GET 'http://localhost:8080/pdb/query/v4/fact-contents' \
    --data-urlencode \
      'query=["=", "path", [ "networking", "eth0", "macaddresses", 0 ]]'

which returns:

    [ {
      "certname" : "node-0",
      "path" : [ "networking", "eth0", "macaddresses", 0 ],
      "name" : "networking",
      "value" : "aa:bb:cc:dd:ee:00",
      "environment" : "foo"
    } ]

Get all nodes with values higher then 3:

    curl -X GET 'http://localhost:8080/pdb/query/v4/fact-contents' \
      --data-urlencode 'query=[">", "value", 3]'

which returns:

    [ {
      "certname" : "node-0",
      "path" : [ "load_avg" ],
      "name" : "load_avg",
      "value" : 3.16,
      "environment" : "foo"
    }, {
      "certname" : "node-0",
      "path" : [ "cpus" ],
      "name" : "cpus",
      "value" : 6,
      "environment" : "foo"
    }, {
      "certname" : "node-0",
      "path" : [ "foo", "bar", 1, "foobar" ],
      "name" "foo",
      "value" : 5,
      "environment" : "foo"
    } ]

For matching against a path element, we have added a new operator: `~>`. We call it the regexp array operator.

This operator allows you to match against path elements using an array of regular expressions that individually match against the stored paths for each fact-node.

The example shows a query that extracts all `macaddresses` for all ethernet devices (that is, devices starting with `eth`):

    curl -G 'http://localhost:8080/pdb/query/v4/fact-contents' \
        --data-urlencode 'query=["~>", "path", ["networking","eth.*","macaddresses",".*"]]'

which returns:

    [ {
      "certname" : "node-0",
      "path" : [ "networking", "eth0", "macaddresses", 1 ],
      "value" : "aa:bb:cc:dd:ee:01",
      "name" : "networking",
      "environment" : "foo"
    }, {
      "certname" : "node-0",
      "path" : [ "networking", "eth0", "macaddresses", 0 ],
      "value" : "aa:bb:cc:dd:ee:00",
      "name": "networking",
      "environment" : "foo"
    } ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging]. When "value" is specified as the `order_by` parameter, the
ordering will be lexicographical.
