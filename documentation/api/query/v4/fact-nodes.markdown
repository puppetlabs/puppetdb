---
title: "PuppetDB 2.1 » API » v4 » Querying Fact nodes"
layout: default
canonical: "/puppetdb/latest/api/query/v4/fact-nodes.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html

You can query fact information with greater power by using the '/fact-nodes' endpoint. This endpoint provides the capability to descend into structured facts and query tree nodes deep within this data by using the concept of paths and values.

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

With the fact nodes endpoint it allows you to query data at a particular node of the tree using the `path` field, and then either analyze or filter on the `value` of that node.

> **Note:** The v4 API is experimental and may change without notice. For stability, we recommend that you use the v3 API instead.

### `GET /v4/fact-nodes`

This will return all fact nodes matching the given query.

### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

    If a query parameter is not provided, all results will be returned.

### Query Operators

See [the Operators page.](./operators.html)

### Query Fields

* `certname` (string): the certname associated with the factset
* `environment` (string): the environment associated with the fact
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
      "path": <path to tree node>,
      "facts": <value of tree node>
    }

### Examples

[Using `curl` from localhost][curl]:

Get the first mac address for eth0:

    curl -X GET 'http://puppetdb:8080/v4/fact-nodes' --data-urlencode 'query=["=", "path",[ "networking", "eth0", "macaddresses", 0 ]]'

which returns:

    [ {
      "certname" : "node-0",
      "path" : [ "networking", "eth0", "macaddresses", 0 ],
      "value" : "aa:bb:cc:dd:ee:00",
      "environment" : "foo"
    } ]

Get all nodes with values higher then 3:

    curl -X GET 'http://puppetdb:8080/v4/fact-nodes' --data-urlencode 'query=[">", "value", 3]'

which returns:

    [ {
      "certname" : "node-0",
      "path" : [ "load_avg" ],
      "value" : 3.16,
      "environment" : "foo"
    }, {
      "certname" : "node-0",
      "path" : [ "cpus" ],
      "value" : 6,
      "environment" : "foo"
    }, {
      "certname" : "node-0",
      "path" : [ "foo", "bar", 1, "foobar" ],
      "value" : 5,
      "environment" : "foo"
    } ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
