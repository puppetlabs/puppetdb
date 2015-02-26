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

* `noop` (boolean): a flag indicating whether the report was produced by a noop run.

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
      "noop": <boolean flag indicating noop run>,
      "environment": <report environment>,
      "configuration_version": <catalog identifier>,
      "certname": <node name>,
      "resource_events": [<resource event>],
      "metrics" : [<metric>]
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

The metrics field may be either null, in the case of reports submitted without metrics,
or a JSON array of objects like so:


    {
      "category" : <category of metric ("resources", "time", "changes", or "events")>,
      "name" : <name of the metric>,
      "value" : <value of the metric (double precision)>
    }


**Note on fields that allow `NULL` values**

In the resource_event schema above, `containment_path`, `new_value`, `old_value`, `property`, `file`, `line`, `status`, and `message` may all be null.

**Note on querying resource events and metrics**
The `reports` endpoint does not support querying on the value of `resource_events`
or `metrics`. In the case of `resource_events` the same information can be accessed by querying the `events` endpoint for events with field `report` equal to a given report's `hash`.
Making metrics queryable may be the target of future work.



### Examples

[You can use `curl`][curl] to query information about reports like so:

Query for all reports:

    curl -G 'http://localhost:8080/v4/reports'

    [ {
      "receive_time" : "2015-02-19T16:23:11.034Z",
      "hash" : "32c821673e647b0650717db467abc51d9949fd9a",
      "transaction_uuid" : "9a7070e9-840f-446d-b756-6f19bf2e2efc",
      "puppet_version" : "3.7.4",
      "noop" : false,
      "report_format" : 4,
      "start_time" : "2015-02-19T16:23:09.810Z",
      "end_time" : "2015-02-19T16:23:10.287Z",
      "resource_events" : [ {
        "new_value" : "hi world",
        "property" : "message",
        "file" : "/home/wyatt/.puppet/manifests/site.pp",
        "old_value" : "absent",
        "line" : 7,
        "resource_type" : "Notify",
        "status" : "success",
        "resource_title" : "hiloo",
        "timestamp" : "2015-02-19T16:23:10.768Z",
        "containment_path" : [ "Stage[main]", "Main", "Notify[hiloo]" ],
        "message" : "defined 'message' as 'hi world'"
      }, {
        "new_value" : "hi world",
        "property" : "message",
        "file" : "/home/wyatt/.puppet/manifests/site.pp",
        "old_value" : "absent",
        "line" : 3,
        "resource_type" : "Notify",
        "status" : "success",
        "resource_title" : "hi",
        "timestamp" : "2015-02-19T16:23:10.767Z",
        "containment_path" : [ "Stage[main]", "Main", "Notify[hi]" ],
        "message" : "defined 'message' as 'hi world'"
      } ],
      "status" : "changed",
      "configuration_version" : "1424362990",
      "environment" : "production",
      "certname" : "desktop.localdomain",
      "metrics" : [ {
        "category" : "resources",
        "name" : "changed",
        "value" : 2
      }, {
        "category" : "resources",
        "name" : "failed",
        "value" : 0
      }, {
        "category" : "resources",
        "name" : "failed_to_restart",
        "value" : 0
      }, {
        "category" : "resources",
        "name" : "out_of_sync",
        "value" : 2
      }, {
        "category" : "resources",
        "name" : "restarted",
        "value" : 0
      }, {
        "category" : "resources",
        "name" : "scheduled",
        "value" : 0
      }, {
        "category" : "resources",
        "name" : "skipped",
        "value" : 0
      }, {
        "category" : "resources",
        "name" : "total",
        "value" : 9
      }, {
        "category" : "time",
        "name" : "config_retrieval",
        "value" : 0.476064209
      }, {
        "category" : "time",
        "name" : "filebucket",
        "value" : 3.8841E-5
      }, {
        "category" : "time",
        "name" : "notify",
        "value" : 7.54224E-4
      }, {
        "category" : "time",
        "name" : "schedule",
        "value" : 2.0780000000000004E-4
      }, {
        "category" : "time",
        "name" : "total",
        "value" : 0.47706507400000003
      }, {
        "category" : "changes",
        "name" : "total",
        "value" : 2
      }, {
        "category" : "events",
        "name" : "failure",
        "value" : 0
      }, {
        "category" : "events",
        "name" : "success",
        "value" : 2
      }, {
        "category" : "events",
        "name" : "total",
        "value" : 2
      } ]
    } ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters.  For more information, please see the documentation
on [paging][paging].
