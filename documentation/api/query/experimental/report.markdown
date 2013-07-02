---
title: "PuppetDB 1.3 » API » Experimental » Querying Reports"
layout: default
canonical: "/puppetdb/latest/api/query/experimental/report.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[operator]: ../v2/operators.html

Querying reports is accomplished by making an HTTP request to the `/reports` REST
endpoint.

## Routes

### `GET /experimental/reports`

#### Parameters

* `query`: Required. A JSON array of query predicates, in prefix form. (The standard `["<OPERATOR>", "<FIELD>", "<VALUE>"]` format.)

For example, for all reports run on the node with certname 'example.local', the
JSON query structure would be:

    ["=", "certname", "example.local"]

##### Operators

The only available [OPERATOR][] is `=`.

##### Fields

The only available FIELD is `certname`, which is the name of the node associated with the report.

#### Response format

The response is a JSON array of report summaries for all reports
that matched the input parameters.  The summaries are sorted by
the completion time of the report, in descending order:

    [
      {
        "end-time": "2012-10-29T18:38:01.000Z",
        "puppet-version": "3.0.1",
        "receive-time": "2012-10-29T18:38:04.238Z",
        "configuration-version": "1351535883",
        "start-time": "2012-10-29T18:38:00.000Z",
        "hash": "bd899b1ee825ec1d2c671fe5541b5c8f4a783472",
        "certname": "foo.local",
        "report-format": 3
        },
      {
        "end-time": "2012-10-26T22:39:32.000Z",
        "puppet-version": "3.0.1",
        "receive-time": "2012-10-26T22:39:35.305Z",
        "configuration-version": "1351291174",
        "start-time": "2012-10-26T22:39:31.000Z",
        "hash": "cd4e5fd8846bac26d15d151664a40e0f2fa600b0",
        "certname": "foo.local",
        "report-format": 3
        }
    ]

#### Example

[You can use `curl`][curl] to query information about reports like so:

    curl -G -H "Accept: application/json" 'http://localhost:8080/experimental/reports' --data-urlencode 'query=["=", "certname", "example.local"]'
