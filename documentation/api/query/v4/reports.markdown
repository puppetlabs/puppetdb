---
title: "PuppetDB 2.1 » API » v4 » Querying Reports"
layout: default
canonical: "/puppetdb/latest/api/query/v4/reports.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[operator]: ./operators.html
[event]: ./events.html
[paging]: ./paging.html
[statuses]: /puppet/latest/reference/format_report.html#puppettransactionreport
[query]: ./query.html
[8601]: http://en.wikipedia.org/wiki/ISO_8601

Puppet agent nodes submit reports after their runs, and the puppet master forwards these to PuppetDB. Each report includes:

* Some data about the entire run
* Some metadata about the report
* Many _events,_ describing what happened during the run

Once this information is stored in PuppetDB, it can be queried in various ways.

* You can query **data about the run** and **report metadata** by making an HTTP request to the `/reports` endpoint.
* You can query **data about individual events** by making an HTTP request to the [`/events`][event] endpoint.
* You can query **summaries of event data** by making an HTTP request to the [`/event-counts`](./event-counts.html) or [`aggregate-event-counts`](./aggregate-event-counts.html) endpoints.

> **Note:** The v4 API is experimental and may change without notice. For stability, we recommend that you use the v3 API instead.

## `GET /v4/reports`

### URL Parameters

* `query`: Optional. A JSON array of query predicates, in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

    If the `query` parameter is absent, PuppetDB will return all reports.

### Query Operators

See [the Operators page](./operators.html)

### Query Fields

The below fields are allowed as filter criteria and are returned in all responses.

* `certname` (string): the name of the node that the report was received from.

* `hash` (string): the id of the report; these ids can be acquired via event queries (see the [`/events`][event] endpoint).

* `environment` (string): the environment assigned to the node that submitted the report.

* `status` (string): the status associated to report's node. Possible values for this field come from Puppet's report status, which can be found [here][statuses].

* `puppet-version` (string): the version of Puppet that generated the report.

* `report-format` (number): the version number of the report format that Puppet used to generate the original report data.

* `configuration-version` (string): an identifier string that Puppet uses to match a specific catalog for a node to a specific Puppet run.

* `start-time` (timestamp): is the time at which the Puppet run began. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `end-time` (timestamp): is the time at which the Puppet run ended. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `receive-time` (timestamp): is the time at which PuppetDB recieved the report. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `transaction-uuid` (string): string used to identify a Puppet run.

### Response format

The response is a JSON array of report summaries for all reports
that matched the input parameters.  The array is unsorted.

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
        "environment": "dev",
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
        "environment": "dev",
        "status": "unchanged"
        }
    ]

### Examples

[You can use `curl`][curl] to query information about reports like so:

    curl -G 'http://localhost:8080/v4/reports' --data-urlencode 'query=["=", "certname", "example.local"]'

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters.  For more information, please see the documentation
on [paging][paging].

