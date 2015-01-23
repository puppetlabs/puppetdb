---
title: "PuppetDB 2.2 » API » v4 » Querying Reports"
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

* `puppet_version` (string): the version of Puppet that generated the report.

* `report_format` (number): the version number of the report format that Puppet used to generate the original report data.

* `configuration_version` (string): an identifier string that Puppet uses to match a specific catalog for a node to a specific Puppet run.

* `start_time` (timestamp): is the time at which the Puppet run began. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `end_time` (timestamp): is the time at which the Puppet run ended. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `receive_time` (timestamp): is the time at which PuppetDB received the report. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `transaction_uuid` (string): string used to identify a Puppet run.

### Response format

The response is a JSON array of report summaries for all event reports
that matched the input parameters.  The array is unsorted. The top-level response
is of the form:

    {
      "hash": <sha1 of stored report command payload>,
      "puppet_version": <report puppet version>,
      "receive_time": <time of report reception by PDB>,
      "report_format": <report wireformat version>,
      "start_time": <start of run timestamp>,
      "end_time": <end of run timestamp>,
      "transaction_uuid": <string to identify puppet run>,
      "status": <status of node after report's associated puppet run>,
      "environment": <report environment>,
      "configuration_version": <catalog identifier>,
      "certname": <node name>,
      "resource_events": [<resource event>]
    }

Resource event objects are of the following form:

    {
      "status": <status of event (`success`, `failure`, `noop`, or `skipped`)>,
      "timestamp": <timestamp (from agent) at which event occurred>,
      "resource_type": <type of resource event occurred on>,
      "resource_title": <title of resource event occurred on>,
      "property": <property/parameter of resource on which event occurred>,
      "new_value": <new value for resource property>,
      "old_value": <old value of resource property>,
      "message": <description of what happened during event>,
      "file": <manifest file containing resource definition>,
      "line": <line in manifest file on which resource is defined>
      "containment_path": <containment heirarchy of resource within catalog>
    }

**Note on fields that allow `NULL` values**

In the resource_event schema above, `containment_path`, `new_value`, `old_value`, `property`, `file`, `line`, `status`, and `message` may all be null.

**Note on querying resource events**
The `reports` endpoint does not support querying on the value of `resource_events`, but the same information can be be accessed by querying the `events` endpoint for events with field `report` equal to a given report's `hash`.


### Examples

[You can use `curl`][curl] to query information about reports like so:

Query for all reports:

    curl -G 'http://localhost:8080/v4/reports'

    [ {
      "hash" : "89944d0dcac56d3ee641ca9b69c54b1c15ef01fe",
      "puppet_version" : "3.7.3",
      "receive_time" : "2014-12-24T00:00:50.716Z",
      "report_format" : 4,
      "start_time" : "2014-12-24T00:00:49.211Z",
      "end_time" : "2014-12-24T00:00:49.705Z",
      "transaction_uuid" : "af4fb9ad-b267-4e0b-a295-53eba6b139b7",
      "status" : "changed",
      "environment" : "production",
      "configuration_version" : "1419379250",
      "certname" : "foo.com",
      "resource_events" : [ {
        "containment_path" : [ "Stage[main]", "Main", "Notify[hi]" ],
        "new_value" : "\"Hi world\"",
        "resource_title" : "hi",
        "property" : "message",
        "file" : "/home/wyatt/.puppet/manifests/site.pp",
        "old_value" : "\"absent\"",
        "line" : 3,
        "status" : "changed",
        "resource_type" : "Notify",
        "timestamp" : "2014-12-24T00:00:50.522Z",
        "message" : "defined 'message' as 'Hi world'"
      }, {
        "containment_path" : [ "Stage[main]", "Main", "File[/home/wyatt/Desktop/foo]" ],
        "new_value" : "\"file\"",
        "resource_title" : "/home/wyatt/Desktop/foo",
        "property" : "ensure",
        "file" : "/home/wyatt/.puppet/manifests/site.pp",
        "old_value" : "\"absent\"",
        "line" : 7,
        "status" : "changed",
        "resource_type" : "File",
        "timestamp" : "2014-12-24T00:00:50.514Z",
        "message" : "defined content as '{md5}207995b58ba1956b97028ebb2f8caeba'"
      } ]
    } ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters.  For more information, please see the documentation
on [paging][paging].
