---
title: "Inventory endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/inventory.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[subqueries]: ./ast.html#subquery-operators
[dotted]: ./ast.html#dot-notation
[environments]: ./environments.html
[factsets]: ./factsets.html
[catalogs]: ./catalogs.html
[facts]: ./facts.html
[fact-contents]: ./fact-contents.html
[events]: ./events.html
[edges]: ./edges.html
[resources]: ./resources.html
[nodes]: ./nodes.html
[query]: ./query.html
[ast]: ./ast.html

The `/inventory` endpoint enables an alternative query syntax for digging into
structured facts, and can be used instead of the `facts`, `fact-contents`, and
`factsets` endpoints for most fact-related queries.

## `/pdb/query/v4/inventory`

This will return an array of `node inventories` matching the given query.
Inventories for deactivated nodes are not included in the response.

### URL parameters

* `query`: optional. A JSON array containing the query in prefix notation
  (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the
  supported operators and fields. For general information about queries, see
  [our guide to query structure.][query]

### Query operators

See [the AST query language page][ast] for the full list of available operators.

> **Note:** This endpoint supports [dot notation][dotted] on the `facts` and
`trusted` response fields.

### Query fields

* `certname` (string): the name of the node associated with the inventory.

* `timestamp` (string): the time at which PuppetDB received the facts in the inventory.

* `environment` (string): the environment associated with the inventory's
  certname.

* `facts` (json): a JSON hash of fact names to fact values.

* `trusted` (json): a JSON hash of trusted data for the node.

### Response format

Successful responses will be in `application/json`.

The result will be a JSON array with one entry per certname. Each entry is of
the form:

    {
      "certname": <node certname>,
      "timestamp": <timestamp of fact reception>,
      "environment": <node environment>,
      "facts": {
                 <fact name>: <fact value>,
                 ...
               },
      "trusted": {
                   <data name>: <data value>,
                   ...
                 }
    }

### Examples

[Using `curl` from localhost][curl]

    curl -X GET http://localhost:8080/pdb/query/v4/inventory -d 'query=["=", "facts.operatingsystem", "Darwin"]'

    [ {
        "certname" : "mbp.local",
            "timestamp" : "2016-07-11T20:02:33.190Z",
            "environment" : "production",
            "facts" : {
                "kernel" : "Darwin",
                "operatingsystem" : "Darwin",
                "memoryfree" : "3.51 GB",
                "macaddress_p2p0" : "0e:15:c2:d6:f8:4e",
                "system_uptime" : {
                    "days" : 0,
                    "hours" : 1,
                    "uptime" : "1:52 hours",
                    "seconds" : 6733
                },
                "netmask_lo0" : "255.0.0.0",
                "sp_physical_memory" : "16 GB",
                "operatingsystemrelease" : "14.4.0",
                "macosx_productname" : "Mac OS X",
                "sp_boot_mode" : "normal_boot",
                "macaddress_awdl0" : "6e:31:ef:e6:36:54",
                ...
            },
            "trusted" : {
                "domain" : "local",
                "certname" : "mbp.local",
                "hostname" : "mbp",
                "extensions" : { },
                "authenticated" : "remote"
            }
    } ]
