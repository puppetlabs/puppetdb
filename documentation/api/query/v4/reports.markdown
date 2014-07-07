---
title: "PuppetDB 2.1 » API » v4 » Querying Reports"
layout: default
canonical: "/puppetdb/latest/api/query/v4/reports.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[operator]: ../v4/operators.html
[event]: ./events.html
[paging]: ./paging.html
[statuses]: ./puppet/3/reference/format_report.html#puppettransactionreport

Querying reports is accomplished by making an HTTP request to the `/reports` REST
endpoint.

> **Note:** The v4 API is experimental and may change without notice. For stability, we recommend that you use the v3 API instead.

## Routes

### `GET /v4/reports`

#### Parameters

* `query`: Required. A JSON array of query predicates, in prefix form. (The standard `["<OPERATOR>", "<FIELD>", "<VALUE>"]` format.)

For example, for all reports run on the node with certname 'example.local', the
JSON query structure would be:

    ["=", "certname", "example.local"]

##### Operators

See [the Operators page](./operators.html)

##### Fields

`FIELD` may be any of the following:

`certname`
: the name of the node that the report was received from.

`hash`
: the id of the report; these ids can be acquired
  via event queries (see the [`/events`][event] query endpoint).

`environment`
: the environment associated to report's node

`status`
: the status associated to report's node, possible values for this field come from Puppet's report status which can be found [here][statuses]

`puppet-version`
: the version of puppet that generated the report

`report-format`
: the version number of the report format that puppet used to generate the original report data

`configuration-version`
: an identifier string that puppet uses to match a specific catalog for a node to a specific puppet run

`start-time`
: is the time at which the puppet run began

`end-time`
: is the time at which the puppet run ended

`receive-time`
: is the time at which puppetdb recieved the report

`transaction-uuid`
: string used to identify a puppet run

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
        "transaction-uuid": "030c1717-f175-4644-b048-ac9ea328f221",
        "environment": "DEV",
        "status": "unchanged"
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
        "environment": "DEV",
        "status": "unchanged"
        }
    ]


#### Paging

This query endpoint supports paged results via the common PuppetDB paging
query parameters.  For more information, please see the documentation
on [paging][paging].

#### Example

[You can use `curl`][curl] to query information about reports like so:

    curl -G 'http://localhost:8080/v4/reports' --data-urlencode 'query=["=", "certname", "example.local"]'
