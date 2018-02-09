---
title: "PuppetDB 5.2: Reports endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/reports.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[ast]: ./ast.html
[events]: ./events.html
[paging]: ./paging.html
[statuses]: {{puppet}}/format_report.html#puppettransactionreport
[query]: ./query.html
[8601]: http://en.wikipedia.org/wiki/ISO_8601
[subqueries]: ./ast.html#subquery-operators
[environments]: ./environments.html
[producers]: ./producers.html
[events]: ./events.html
[nodes]: ./nodes.html

Puppet agent nodes submit reports after their runs, and the Puppet master
forwards these to PuppetDB. Each report includes:

* Data about the entire run
* Metadata about the report
* Many _events,_ describing what happened during the run

Once this information is stored in PuppetDB, it can be queried in various ways.

* You can query **data about the run** and **report metadata** by making an HTTP
  request to the `/reports` endpoint.

* You can query **data about individual events** by making an HTTP request to
  the [`/events`][events] endpoint.

* You can query **summaries of event data** by making an HTTP request to the
  [`/event-counts`](./event-counts.html) or
  [`aggregate-event-counts`](./aggregate-event-counts.html) endpoints.

## `/pdb/query/v4/reports`

### URL parameters

* `query`: optional. A JSON array of query predicates, in prefix notation
  (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the
  supported operators and fields. For general info about queries, see [our guide
  to query structure.][query]

If the `query` parameter is absent, PuppetDB will return all reports.

### Query operators

See [the AST query language page][ast].

### Query fields

The following fields are allowed as filter criteria and are returned in all
responses.

* `certname` (string): the name of the node that the report was received from.

* `hash` (string): the ID of the report; these IDs can be acquired via event
  queries (see the [`/events`][events] endpoint).

* `environment` (string): the environment assigned to the node that submitted
  the report.

* `status` (string): the status associated to report's node. Possible values for
  this field come from Puppet's report status, which can be found
  [here][statuses].
  
*  `job_id` (string): the job id associated with the report (not present if the run wasn't part of a job)

* `noop` (Boolean): a flag indicating whether the report was produced by a noop
  run.

* `noop_pending` (Boolean): a flag indicating whether the report contains noop
  events (these can arise from use of `--noop` or from resources with the
  `noop` parameter set to true). This field will only be present when the agent originating the report is version 4.6.0 or higher.

* `puppet_version` (string): the version of Puppet that generated the report.

* `report_format` (number): the version number of the report format that Puppet
  used to generate the original report data.

* `configuration_version` (string): an identifier string that Puppet uses to
  match a specific catalog for a node to a specific Puppet run.

* `start_time` (timestamp): the time on the agent at which the Puppet run began.
  Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `end_time` (timestamp): the time on the agent at which the Puppet run ended.
  Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `producer_timestamp` (timestamp): the time of catalog submission from the
  Puppet master to PuppetDB, according to the clock on the Puppet master.
  Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `receive_time` (timestamp): the time at which PuppetDB received the report.
  Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `producer` (string): the certname of the Puppet master that sent the report
  to PuppetDB.

* `transaction_uuid` (string): a string used to identify a Puppet run.

* `catalog_uuid` (string): a string used to tie a catalog to a report to the
  catalog used from that Puppet run.

* `code_id` (string): a string used to tie a catalog to the Puppet code which
  generated the catalog.

* `cached_catalog_status` (string): a string used to identify whether the Puppet
run used a cached catalogs and whether or not the cached catalog was used due to
an error or not. Possible values include `explicitly_requested`, `on_failure`,
`not_used` or `null`.

* `corrective_change`: (Boolean): a flag indicating whether any of the report's
  events remediated configuration drift. This field is only populated in PE.

* `latest_report?` (Boolean): return only reports associated with the most
  recent Puppet run for each node. **Note:** this field does not appear in the
  response.

### Subquery relationships

The following list contains related entities that can be used to constrain the
result set using implicit subqueries. For more information, consult the
documentation for [subqueries][subqueries].

* [`environments`][environments]: environment from where a report was received.
* [`events`][events]: events received in a report.
* [`producers`][producers]: the master that sent the report to PuppetDB.

### Response format

The response is a JSON array of report summaries for all event reports
that matched the input parameters. The array is unsorted. The top-level response
is of the form:

    {
      "hash": <sha1 of stored report command payload>,
      "puppet_version": <report puppet version>,
      "receive_time": <time of report reception by PDB>,
      "report_format": <report wireformat version>,
      "start_time": <start of run timestamp>,
      "end_time": <end of run timestamp>,
      "producer_timestamp": <time of transmission by master>,
      "producer": <master certname>,
      "transaction_uuid": <string to identify puppet run>,
      "status": <status of node after report's associated puppet run>,
      "noop": <boolean flag indicating noop run>,
      "noop_pending": <boolean flag indicating presence of noop events>
      "environment": <report environment>,
      "configuration_version": <catalog identifier>,
      "certname": <node name>,
      "code_id": <sha1 of Puppet code>,
      "catalog_uuid": <string to identify the corresponding catalog>,
      "cached_catalog_status": <reason a cached catalog was used>,
      "resource_events": <expanded resource events>,
      "resources" : <expanded resources (PE only)>
      "metrics" : <expanded metrics>,
      "logs" : <expanded logs>
    }

> **Note: The `resources` field is only offered in Puppet Enterprise (PE)**
>
> The response format in PE contains an additional field, `resources`, which
> contains all the resource statuses for the Puppet run corresponding to the
> `report`, both changed and unchanged resources.

The `<expanded resource events>` object is of the following form:

    {
      "href": <url>,
      "data": [ {
        "status": <status of event (`success`, `failure`, `noop`, or `skipped`)>,
        "timestamp": <timestamp (from agent) at which event occurred>,
        "resource_type": <type of resource event occurred on>,
        "resource_title": <title of resource event occurred on>,
        "property": <property/parameter of resource on which event occurred>,
        "new_value": <new value for resource property>,
        "old_value": <old value of resource property>,
        "message": <description of what happened during event>,
        "file": <manifest file containing resource definition>,
        "line": <line in manifest file on which resource is defined>,
        "containment_path": <containment heirarchy of resource within catalog>
      } ... ]
    }

The `<expanded resources>` object is of the following form:

    {
      "href": <url>,
      "data": [ {
        "timestamp": <timestamp (from agent) at which event occurred>,
        "resource_type": <type of resource event occurred on>,
        "resource_title": <title of resource event occurred on>,
        "containment_path": <containment heirarchy of resource within catalog>
        "skipped" : <boolean for whether or not the resource was skipped>,
        "events" : [<event> ...]
      } ... ]
    }

Where an `<event>` object is of the form:

    {
            "timestamp": <timestamp (from agent) at which event occurred>,
            "property": <property/parameter of resource on which event occurred>,
            "new_value": <new value for resource property>,
            "old_value": <old value of resource property>,
            "status": <status of event (`success`, `failure`, or `noop`)>,
            "message": <description of what happened during event>
    }

> **Note: On `resources` versus `resource_events`**
>
> Unchanged resources are accessed through the `resources` field. The
> `resource_events` field does not contain this information.

The `<expanded metrics>` object is as follows:

    {
      "href": <url>,
      "data": [ {
        "category" : <category of metric ("resources", "time", "changes", or "events")>,
        "name" : <name of the metric>,
        "value" : <value of the metric (double precision)>
      } ... ]
    }

The `<expanded logs>` object returns a single log line per data entry as follows:

    {
      "href": <url>,
      "data": [ {
        "file" : <file of resource declaration>,
        "line" : <line of resource declaration>,
        "level" : <log level>,
        "message" : <log message>,
        "source" : <log source>,
        "tags" : [<resource tag>],
        "time" : <log line timestamp>
     } ... ]
   }

File and line may each be null if the log does not concern a resource.

>**Note: Fields that allow `NULL` values**
>
>In the resource_event schema above, `containment_path`, `new_value`, `old_value`, `property`, `file`, `line`, `status`, and `message` may all be null.

>**Note: On querying resource events, metrics, and logs**
>
>The `reports` endpoint does not support querying on the value of `resource_events`, `logs`, or `metrics`. For `resource_events`, the same information can be accessed by querying the `events` endpoint for events with field `report` equal to a given report's `hash`. Making metrics and logs queryable may be the target of future work.

### Examples

[You can use `curl`][curl] to query information about reports:

Query for all reports:

    curl -G http://localhost:8080/pdb/query/v4/reports

    [ {
      "receive_time" : "2015-02-19T16:23:11.034Z",
      "hash" : "32c821673e647b0650717db467abc51d9949fd9a",
      "transaction_uuid" : "9a7070e9-840f-446d-b756-6f19bf2e2efc",
      "puppet_version" : "3.7.4",
      "noop" : false,
      "noop_pending": true,
      "report_format" : 4,
      "start_time" : "2015-02-19T16:23:09.810Z",
      "end_time" : "2015-02-19T16:23:10.287Z",
      "producer_timestamp" : "2015-02-19T16:23:11.000Z",
      "producer" : "master.localdomain",
      "resource_events" : {
        "href": "/pdb/query/v4/reports/32c821673e647b0650717db467abc51d9949fd9a/events",
        "data": [ {
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
        } ]
      },
      "status" : "changed",
      "configuration_version" : "1424362990",
      "environment" : "production",
      "certname" : "desktop.localdomain",
      "metrics" : {
        "href": "/pdb/query/v4/reports/32c821673e647b0650717db467abc51d9949fd9a/metrics",
        "data": [ {
          "category" : "resources",
          "name" : "changed",
          "value" : 2
        }, {
          "category" : "resources",
          "name" : "failed",
          "value" : 0
        },
        ...
        {
          "category" : "events",
          "name" : "success",
          "value" : 2
        }, {
          "category" : "events",
          "name" : "total",
          "value" : 2
        } ]
      },
      "logs" : {
        "href": "/pdb/query/v4/reports/32c821673e647b0650717db467abc51d9949fd9a/logs",
        "data": [ {
          "file" : null,
          "line" : null,
          "level" : "info",
          "message" : "Caching catalog for mbp.local",
          "source" : "//mbp.local/Puppet",
          "tags" : [ "info" ],
          "time" : "2015-02-26T16:27:48.416642000-08:00"
        },
        ...
        {
          "file" : null,
          "line" : null,
          "level" : "notice",
          "message" : "Finished catalog run in 0.01 seconds",
          "source" : "//mbp.local/Puppet",
          "tags" : [ "notice" ],
          "time" : "2015-02-26T16:27:48.483317000-08:00"
        } ]
      }
    } ]

You can retrieve the `resources` field as part of the data from the `reports`
endpoint or using the child data endpoint.

For example, the following query finds for all resources (changed and unchanged)
for a report with hash `32c821673e647b0650717db467abc51d9949fd9a`:

**Note: The following is for PE only**
    curl -G http://localhost:8080/pdb/query/v4/reports/32c821673e647b0650717db467abc51d9949fd9a/resources

    [
       {
          "file":"/home/wyatt/.puppet/manifests/site.pp",
          "line":7,
          "resource_title":"hiloo",
          "timestamp":"2015-02-19T16:23:10.768Z",
          "containment_path":[
             "Stage[main]",
             "Main",
             "Notify[hiloo]"
          ],
          "resource_type":"Notify",
          "message":"defined 'message' as 'hi world'",
          "skipped":false,
          "events":[
             {
                "property":"message",
                "old_value":"absent",
                "new_value":"hi world",
                "status":"success",
                "timestamp":"2015-02-19T16:23:10.768Z"
             }
          ]
       }
    ]

Get counts of reports grouped by status:

    curl -X GET http://localhost:8080/pdb/query/v4/reports \
      -d 'query=["extract",[["function","count"], "status"],
                            ["~","certname",""],
                            ["group_by", "status"]]'

    [ {
      "status" : "failed",
      "count" : 10
    }, {
      "status" : "changed",
      "count" : 72
    }, {
      "status" : "unchanged",
      "count" : 20
    } ]

Get counts of reports grouped by status and day of the week received:

    curl -X GET http://localhost:8080/pdb/query/v4/reports -d
    'query=["extract",["status", ["function", "count"], ["function", "to_string","receive_time", "FMDay"]],["group_by","status",["function","to_string","receive_time", "FMDay"]]]'

    [ {
      "status" : "failed",
      "count" : 10,
      "to_string" : "Thursday"
    }, {
      "status" : "changed",
       "count" : 60,
       "to_string" : "Thursday"
    }, {
      "status" : "unchanged",
       "count" : 30,
       "to_string" : "Thursday"
    } ]

## `/pdb/query/v4/reports/<HASH>/events`

Returns all events for a particular report, designated by its unique hash.

This is a shortcut to the [`/events`][events] endpoint. It behaves the same as a
call to [`/events`][events] with a query string of `["=", "report", "<HASH>"]`.

## `/pdb/query/v4/reports/<HASH>/metrics`

Returns all metrics for a particular report, designated by its unique hash. This
endpoint does not currently support querying or paging.

## `/pdb/query/v4/reports/<HASH>/logs`

Returns all logs for a particular report, designated by its unique hash. This
endpoint does not currently support querying or paging.

### URL parameters / query operators / query fields / response format

This route is an extension of the [`events`][events] endpoint. It uses the exact
same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which
will be used to return a subset of the information normally returned by this
route.

## Paging

This query endpoint supports paged results via the common PuppetDB paging URL
parameters. For more information, please see the documentation on
[paging][paging].
