---
title: "PuppetDB 1.4 » API » Experimental » Querying Reports"
layout: default
canonical: "/puppetdb/latest/api/query/experimental/report.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[operator]: ../v2/operators.html
[event]: ./event.html
[paging]: ./paging.html

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

`FIELD` may be any of the following.  All fields support only the equality operator.

`certname`
: the name of the node that the report was received from.

`hash`
: the id of the report; these ids can be acquired
  via event queries (see the [`/events`][event] query endpoint).

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
        "report-format": 4,
        "transaction-uuid": "030c1717-f175-4644-b048-ac9ea328f221"
        },
      {
        "end-time": "2012-10-26T22:39:32.000Z",
        "puppet-version": "3.0.1",
        "receive-time": "2012-10-26T22:39:35.305Z",
        "configuration-version": "1351291174",
        "start-time": "2012-10-26T22:39:31.000Z",
        "hash": "cd4e5fd8846bac26d15d151664a40e0f2fa600b0",
        "certname": "foo.local",
        "report-format": 4,
        "transaction-uuid": null
        }
    ]


#### Paging

This query endpoint supports paged results via the common PuppetDB paging
query parameters.  For more information, please see the documentation
on [paging][paging].

#### Example

[You can use `curl`][curl] to query information about reports like so:

    curl -G 'http://localhost:8080/experimental/reports' --data-urlencode 'query=["=", "certname", "example.local"]'
