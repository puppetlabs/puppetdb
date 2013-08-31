---
title: "PuppetDB 1.4 » API » v2 » Querying Facts"
layout: default
canonical: "/puppetdb/latest/api/query/v2/facts.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

Querying facts occurs via an HTTP request to the
`/facts` REST endpoint.


## Routes

### `GET /v2/facts`

This will return all facts matching the given query. Facts for
deactivated nodes are not included in the response. There must be an
`Accept` header matching `application/json`.

#### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation. If
  not provided, all results will be returned.

#### Available Fields

* `"name"`: matches facts of the given name
* `"value"`: matches facts with the given value
* `"certname"`: matches facts for the given node

#### Operators

See [the Operators page](./operators.html)

#### Examples

[Using `curl` from localhost][curl]:

Get the operatingsystem fact for all nodes:

    curl -X GET http://puppetdb:8080/v2/facts --data-urlencode 'query=["=", "name", "operatingsystem"]'

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "RedHat"},
     {"certname": "c.example.com", "name": "operatingsystem", "value": "Darwin"},

Get all facts for a single node:

    curl -X GET http://puppetdb:8080/v2/facts --data-urlencode 'query=["=", "certname", "a.example.com"]'

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "a.example.com", "name": "ipaddress", "value": "192.168.1.105"},
     {"certname": "a.example.com", "name": "uptime_days", "value": "26 days"}]

### `GET /v2/facts/<NAME>`

This will return all facts for all nodes with the indicated
name. There must be an `Accept` header matching `application/json`.

#### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/facts` route, mentioned above. When supplied,
  the query is assumed to supply _additional_ criteria that can be
  used to return a _subset_ of the information normally returned by
  this route.

#### Examples

    curl -X GET http://puppetdb:8080/v2/facts/operatingsystem

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "Redhat"},
     {"certname": "c.example.com", "name": "operatingsystem", "value": "Ubuntu"}]

### `GET /v2/facts/<NAME>/<VALUE>`

This will return all facts for all nodes with the indicated name and
value. There must be an `Accept` header matching `application/json`.

#### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix
  notation. The syntax and semantics are identical to the `query`
  parameter for the `/facts` route, mentioned above. When supplied,
  the query is assumed to supply _additional_ criteria that can be
  used to return a _subset_ of the information normally returned by
  this route.

#### Examples

    curl -X GET http://puppetdb:8080/v2/facts/operatingsystem/Debian

    [{"certname": "a.example.com", "name": "operatingsystem", "value": "Debian"},
     {"certname": "b.example.com", "name": "operatingsystem", "value": "Debian}]

## Response Format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON strings.

The result will be a JSON array, with one entry per fact. Each entry is of the form:

    {
      "certname": <node name>,
      "name": <fact name>,
      "value": <fact value>
    }

If no facts are known for the supplied node, an HTTP 404 is returned.
