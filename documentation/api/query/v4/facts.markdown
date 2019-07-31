---
title: "Facts endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/facts.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[subqueries]: ./ast.html#subquery-operators
[ast]: ./ast.html
[environments]: ./environments.html
[factsets]: ./factsets.html
[fact-contents]: ./fact-contents.html
[nodes]: ./nodes.html

You can query facts by making an HTTP request to the `/facts` endpoint.

In Puppet's world, you only interact with facts from one node at a time, so any given fact consists of only a **fact name** and a **value.** But because PuppetDB interacts with a whole population of nodes, each PuppetDB fact also includes a **certname** and an **environment.**

## `/pdb/query/v4/facts`

This will return all facts matching the given query. Facts for
deactivated nodes are not included in the response.

### URL parameters

* `query`: optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [our guide to query structure.][query]

If a query parameter is not provided, all results will be returned.

### Query operators

See [the AST query language page][ast].

### Query fields

* `name` (string): the name of the fact.
* `value` (string, numeric, Boolean): the value of the fact.
* `certname` (string): the node associated with the fact.
* `environment` (string): the environment associated with the fact.

### Subquery relationships

The following list contains related entities that can be used to constrain the result set using implicit subqueries. For more information, consult the documentation for [subqueries][subqueries].

* [`environments`][environments]: the environment where a fact occurs.
* [`fact_contents`][fact-contents]: expanded fact paths and values for a fact.

### Response format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON strings.

The result will be a JSON array, with one entry per fact. Each entry is of the form:

    {
      "certname": <node name>,
      "name": <fact name>,
      "value": <fact value>,
      "environment": <facts environment>
    }

The array is unsorted. Fact values can be strings, floats, integers, Booleans,
arrays, or maps. Map and array values can be any of the same types.

If no facts match the query, an empty JSON array will be returned. Querying
against `value` will return matches only at the top level. Hashes and arrays cannot
be matched.

### Examples

[Using `curl` from localhost][curl]:

Get the operatingsystem fact for all nodes:

    curl -X GET http://localhost:8080/pdb/query/v4/facts \
      --data-urlencode 'query=["=", "name", "operatingsystem"]'

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "RedHat"},
     {"certname": "c.example.com", "name": "operatingsystem", "value": "Darwin"}]

Get all facts for a single node:

    curl -X GET http://localhost:8080/pdb/query/v4/facts \
      --data-urlencode 'query=["=", "certname", "a.example.com"]'

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "a.example.com", "name": "ipaddress", "value": "192.168.1.105"},
     {"certname": "a.example.com", "name": "uptime_days", "value": "26 days"}]

Subquery against `/fact-contents` to get all remotely authenticated trusted facts:

    curl -X GET http://localhost:8080/pdb/query/v4/facts --data-urlencode \
      'query=["in", ["name","certname"],
               ["extract", ["name","certname"],
                 ["select_fact_contents",
                   ["and", ["=", "path", ["trusted", "authenticated"]],
                           ["=","value","remote"]]]]]]]'

    [ {
        "value" : {
            "certname" : "desktop.localdomain",
            "authenticated" : "remote"
        },
        "name" : "trusted",
        "environment" : "production",
        "certname" : "desktop.localdomain"
    } ]

Use `count` and `group_by` to get tallies of each operating system in your
infrastructure:

    curl -X GET http://localhost:8080/pdb/query/v4/facts \
      --data-urlencode 'query=["extract", [["function","count"],"value"],
                                ["=","name","operatingsystem"],
                                ["group_by", "value"]]'

    [ {
        "value" : "Debian",
        "count" : 67
    }, {
        "value" : "CentOS",
        "count" : 33
    } ]

Use the `avg` function and the `group_by` operator to get the average uptime
for your nodes by environment:

    curl -X GET http://localhost:8080/pdb/query/v4/facts \
      --data-urlencode 'query=["extract", ["environment",["function","avg","value"]],
                                ["=","name","uptime_hours"],
                                ["group_by", "environment"]]'

    [ {
        "environment" : "production",
        "avg" : 116.92774910678841
    }, {
        "environment" : "dev",
        "avg" : 271.18281821459045
    } ]

## `/pdb/query/v4/facts/<FACT NAME>`

This will return all facts with the given fact name, for all nodes. It
behaves exactly like a call to `/pdb/query/v4/facts` with a query string of
`["=", "name", "<FACT NAME>"]`.

### URL parameters / query operators / query fields / response format

This route is an extension of the plain `facts` endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by this route.

### Examples

Get the operating system fact for all nodes:

    curl -X GET http://localhost:8080/pdb/query/v4/facts/operatingsystem

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "Redhat"},
     {"certname": "c.example.com", "name": "operatingsystem", "value": "Ubuntu"}]

Get the partitions fact for a single node:

    curl -X GET http://localhost:8080/pdb/query/v4/facts/partitions \
      --data-urlencode 'query=["=", "certname", "a.example.com"]'

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

## `/pdb/query/v4/facts/<FACT NAME>/<VALUE>`

This will return all facts with the given fact name and value, for all
nodes. (That is, only the `certname` field will differ in each
result.) It behaves exactly like a call to `/pdb/query/v4/facts` with a query
string of:

    ["and",
        ["=", "name", "<FACT NAME>"],
        ["=", "value", "<VALUE>"]]

### URL parameters / query operators / query fields / response format

This route is an extension of the plain `facts` endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Examples

    curl -X GET http://localhost:8080/pdb/query/v4/facts/operatingsystem/Debian

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "Debian}]

## Paging

The v4 `/facts` endpoint supports all the common PuppetDB paging URL
parameters. For more information, please see the documentation on
[paging][paging].
