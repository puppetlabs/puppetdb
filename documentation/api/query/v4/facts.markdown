---
title: "PuppetDB 2.2 » API » v4 » Querying Facts"
layout: default
canonical: "/puppetdb/latest/api/query/v4/facts.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html

You can query facts by making an HTTP request to the `/facts` endpoint.

In Puppet's world, you only interact with facts from one node at a time, so any given fact consists of only a **fact name** and a **value.** But since PuppetDB interacts with a whole population of nodes, each PuppetDB fact also includes a **certname** and an **environment.**


## `GET /v4/facts`

This will return all facts matching the given query. Facts for
deactivated nodes are not included in the response.

### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

    If a query parameter is not provided, all results will be returned.

### Query Operators

See [the Operators page.](./operators.html)

### Query Fields

* `name` (string): the name of the fact
* `value` (string, coercible to number): the value of the fact
* `certname` (string): the node associated with the fact
* `environment` (string): the environment associated with the fact

### Response Format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON strings.

The result will be a JSON array, with one entry per fact. Each entry is of the form:

    {
      "certname": <node name>,
      "name": <fact name>,
      "value": <fact value>,
      "environment": <facts environment>
    }

The array is unsorted. Fact values can be strings, floats, integers, booleans,
arrays, or maps. Map and array values can be any of the same types.

If no facts match the query, an empty JSON array will be returned. Querying
against `value` will return matches only at the top-level, hashes and arrays cannot
be matched.

### Examples

[Using `curl` from localhost][curl]:

Get the operatingsystem fact for all nodes:

    curl -X GET http://puppetdb:8080/v4/facts --data-urlencode 'query=["=", "name", "operatingsystem"]'

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "RedHat"},
     {"certname": "c.example.com", "name": "operatingsystem", "value": "Darwin"}]

Get all facts for a single node:

    curl -X GET http://puppetdb:8080/v4/facts --data-urlencode 'query=["=", "certname", "a.example.com"]'

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "a.example.com", "name": "ipaddress", "value": "192.168.1.105"},
     {"certname": "a.example.com", "name": "uptime_days", "value": "26 days"}]

Subquery against `/fact-contents` to get all remotely-authenticated trusted facts:

    curl -X GET http://localhost:8080/v4/facts --data-urlencode 'query=["in", ["name","certname"],
      ["extract",["name","certname"],
        ["select_fact_contents", ["~>", "path", [".*", "authenticated"]]]]]'

    [ {
        "value" : {
            "certname" : "desktop.localdomain",
            "authenticated" : "remote"
        },
        "name" : "trusted",
        "environment" : "production",
        "certname" : "desktop.localdomain"
    } ]

## `GET /v4/facts/<FACT NAME>`

This will return all facts with the given fact name, for all nodes. It behaves exactly like a call to `/v4/facts` with a query string of `["=", "name", "<FACT NAME>"]`.

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the plain `facts` endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Examples

Get the operating system fact for all nodes:

    curl -X GET http://puppetdb:8080/v4/facts/operatingsystem

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "Redhat"},
     {"certname": "c.example.com", "name": "operatingsystem", "value": "Ubuntu"}]

Get the structured partitions fact for a single node:

    curl -X GET http://puppetdb:8080/v4/facts/partitions --data-urlencode 'query=["=", "certname", "a.example.com"]'

    [ {
      "value" : {
        "sda3" : {
          "size" : "174389248",
          "mount" : "/home",
          "uuid" : "26d79d9a-96b3-4cc7-960d-0d6558d7dc54"
        },
        "sda1" : {
          "size" : "1048576",
          "mount" : "/boot"
        },
        "sda2" : {
          "mount" : "/",
          "size" : "74629120",
          "uuid" : "30d0108f-ec67-4557-8331-09ebc8b937f9"
        }
      },
      "name" : "partitions",
      "environment" : "production",
      "certname" : "a.example.com"
    } ]

## `GET /v4/facts/<FACT NAME>/<VALUE>`

This will return all facts with the given fact name and
value, for all nodes. (That is, only the `certname` field will differ in each result.) It behaves exactly like a call to `/v4/facts` with a query string of:

    ["and",
        ["=", "name", "<FACT NAME>"],
        ["=", "value", "<VALUE>"]]

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the plain `facts` endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Examples

    curl -X GET http://puppetdb:8080/v4/facts/operatingsystem/Debian

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "Debian}]

## Paging

The v4 /facts endpoint does not allow ordering by fact value, but otherwise
supports the common PuppetDB paging URL parameters. For more information,
please see the documentation on [paging][paging]. Ordering by value is
supported on the fact-contents endpoint.

