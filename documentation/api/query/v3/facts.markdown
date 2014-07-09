---
title: "PuppetDB 2.1 » API » v3 » Querying Facts"
layout: default
canonical: "/puppetdb/latest/api/query/v3/facts.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html

You can query facts by making an HTTP request to the `/facts` endpoint.



### `GET /v3/facts`

This will return all facts matching the given query. Facts for
deactivated nodes are not included in the response.

### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

    If a query parameter is not provided, all results will be returned.

### Query Operators

See [the Operators page.](./operators.html)

### Query Fields

* `"name"`: matches facts of the given name
* `"value"`: matches facts with the given value
* `"certname"`: matches facts for the given node

### Response Format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON strings.

The result will be a JSON array, with one entry per fact. Each entry is of the form:

    {
      "certname": <node name>,
      "name": <fact name>,
      "value": <fact value>
    }

If no facts match the query, an empty JSON array will be returned.

### Examples

[Using `curl` from localhost][curl]:

Get the operatingsystem fact for all nodes:

    curl -X GET http://puppetdb:8080/v3/facts --data-urlencode 'query=["=", "name", "operatingsystem"]'

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "RedHat"},
     {"certname": "c.example.com", "name": "operatingsystem", "value": "Darwin"},

Get all facts for a single node:

    curl -X GET http://puppetdb:8080/v3/facts --data-urlencode 'query=["=", "certname", "a.example.com"]'

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "a.example.com", "name": "ipaddress", "value": "192.168.1.105"},
     {"certname": "a.example.com", "name": "uptime_days", "value": "26 days"}]

## `GET /v3/facts/<FACT NAME>`

This will return all facts with the given fact name, for all nodes. It behaves exactly like a call to `/v3/facts` with a query string of `["=", "name", "<FACT NAME>"]`.

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the plain `facts` endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Examples

    curl -X GET http://puppetdb:8080/v3/facts/operatingsystem

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "Redhat"},
     {"certname": "c.example.com", "name": "operatingsystem", "value": "Ubuntu"}]

## `GET /v3/facts/<FACT NAME>/<VALUE>`

This will return all facts with the given fact name and
value, for all nodes. (That is, only the `certname` field will differ in each result.) It behaves exactly like a call to `/v3/facts` with a query string of:

    ["and",
        ["=", "name", "<FACT NAME>"],
        ["=", "value", "<VALUE>"]]

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the plain `facts` endpoint. It uses the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

### Examples

    curl -X GET http://puppetdb:8080/v3/facts/operatingsystem/Debian

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "Debian}]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters.  For more information, please see the documentation
on [paging][paging].

