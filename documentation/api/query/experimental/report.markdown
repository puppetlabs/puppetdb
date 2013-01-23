---
title: "PuppetDB 1.1 » API » Experimental » Querying Reports"
layout: default
canonical: "/puppetdb/1.1/api/query/experimental/report.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

# Reports

Querying reports is accomplished by making an HTTP request to the `/reports` REST
endpoint.

# Query format

* The HTTP method must be `GET`.

* There must be an `Accept` header specifying `application/json`.

* The `query` parameter is a JSON array of query predicates, in prefix
  form, conforming to the format described below.

The `query` parameter is described by the following grammar:

    query: [ {match} {field} {value} ]
    field: string
    match: "="

`field` may be any of:

`certname`
: the name of the node associated with the report

For example, for all reports run on the node with certname 'example.local', the
JSON query structure would be:

    ["=", "certname", "example.local"]

# Response format

The response is a JSON array of report summaries for all reports
that matched the input parameters.  The summaries are sorted by
the completion time of the report, in descending order:

`[
  {
    "end-time": "2012-10-29T18:38:01.000Z",
    "puppet-version": "3.0.1",
    "receive-time": "2012-10-29T18:38:04.238Z",
    "configuration-version": "1351535883",
    "start-time": "2012-10-29T18:38:00.000Z",
    "id": "d4bcb35a-fb7b-45da-84e0-fceb7a1df713",
    "certname": "foo.local",
    "report-format": 3
    },
  {
    "end-time": "2012-10-26T22:39:32.000Z",
    "puppet-version": "3.0.1",
    "receive-time": "2012-10-26T22:39:35.305Z",
    "configuration-version": "1351291174",
    "start-time": "2012-10-26T22:39:31.000Z",
    "id": "5ec13ff5-c6fd-43fb-b5b1-59a00ec8e1f1",
    "certname": "foo.local",
    "report-format": 3
    }
]`

# Example

[You can use `curl`][curl] to query information about reports like so:

    curl -G -H "Accept: application/json" 'http://localhost:8080/experimental/reports' --data-urlencode 'query=["=", "certname", "example.local"]'
