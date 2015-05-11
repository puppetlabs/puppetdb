---
title: "PuppetDB 3.0 » API » v4 » Upgrading to PuppetDB 3.0"
layout: default
canonical: "/puppetdb/latest/api/query/v4/upgrading-from-v3.html"
---

This document describes changes that users will need to be aware of
to make their code compliant with the changes in PuppetDB 3.0. Most of these
changes are observable in released versions of the v4 query API, which has been marked 'experimental' since
2.0.0 but will be the only API available in 3.0. Note that this document
focuses on API changes only. For a more complete description of the changes see
the [release notes](https://docs.puppetlabs.com/puppetdb/latest/release_notes.html).

Each change below is marked with the corresponding release version. Changes marked (3.0) are only visible in our [nightly snapshots](http://nightlies.puppetlabs.com/puppetdb/) (not currently fit for production).

### Backwards-incompatible changes

#### Changes affecting all endpoints

* (3.0) All previously dash-separated field names (e.g receive-time), subquery
  operators (e.g select-facts), and query parameters (e.g order-by) are now
  underscore-separated. This change does not apply to dash-separated endpoint
  names such as `aggregate-event-counts`.

#### /v4/catalogs

* (3.0) The v4 catalogs endpoint has changed the response of the `edges` and `resources` fields
  to be expanded. For more information see [/v4/catalogs documentation](./catalogs.html).

* (3.0) We have renamed the "name" key of the catalogs endpoint to "certname", for
  consistency with other endpoints.

* (3.0) The top-level v4 catalogs endpoint will now only return data about nodes
  that are active. This provides the same consistency as other non-historical
  data returns. Endpoints which specify the node explicitly will return results
  even for deactivated or expired nodes.

* (2.0.0) The v4 catalogs endpoint does not contain a `metadata` field
  or an `api_version` field. The contents of the v3 `data` field compose the v4
  response body, along with the new fields `producer_timestamp`, `hash`, and
  `environment`. For more information see the [/v4/catalogs documentation](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/catalogs.html).

#### /v4/facts

* (2.2.0) The v4 facts endpoint returns proper JSON rather than stringified JSON
  under the `value` field in the case of a structured fact.

* (2.2.0) Queries against fact values must use the appropriate type. Possible types are integer, float, boolean, string, json, and null. Where the v3 API would return the same results for [">","value","10"] and [">","value",10], only the second form will work on v4. The same applies for equality queries on boolean values.

#### /v4/factsets

* (2.2) The v4 factsets endpoint was added to facilitate the grouping of facts per node. For more information see the [/v4/factsets documentation](./api/query/v4/factsets.html).

* (3.0) We added a `hash` field to the endpoint fields to support a unique identifer for factsets.

* (3.0) The `facts` field is now expanded as per our new expansion convention, so the data format has changed. For more information see the [/v4/factsets documentation](./api/query/v4/factsets.html).

* (3.0) The `/v4/factsets/<node>/facts` endpoints will now return results even for
  deactivated or expired nodes.

#### /metrics/v1 (formerly /v3/metrics)

* (3.0) The former metrics endpoint has been split off into a separate service, and
  reversioned at v1. This means if you are currently accessing mbeans at
  http://localhost:8080/v3/metrics/mbeans, you will now access them at
  http://localhost:8080/metrics/v1/mbeans and so on according to the [metrics api documentation](https://docs.puppetlabs.com/puppetdb/master/api/metrics/v1/index.html).

* (3.0) PuppetDB's mbeans (listed at /metrics/v1/mbeans) are no longer prefixed with
  "com."

#### /v4/commands

  * (3.0) For users posting commands directly to the /v4/commands endpoint, the
  only valid command submission versions will be [replace catalogs v6](https://docs.puppetlabs.com/puppetdb/master/api/wire_format/catalog_format_v6.html), [store report v5](https://docs.puppetlabs.com/puppetdb/master/api/wire_format/report_format_v5.html), and [replace facts v4](https://docs.puppetlabs.com/puppetdb/master/api/wire_format/facts_format_v4.html).

### New API features

#### New endpoints

* (2.2.0) `/v4/factsets` This endpoint returns a key-value hash for each certname.
  For more information see the [/v4/factsets documentation](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/factsets.html#response-format).

* (2.2.0) `/v4/fact-paths` This endpoint is similar to the existing fact-names endpoint
  in that one expected use is GUI autocompletion. For more information see the [/v4/fact-paths documentation](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/fact-paths.html).

* (2.2.0) `/v4/fact-contents` This endpoint allows fine-grained querying of
  structured facts. For more information see the [/v4/fact-contents documentation](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/fact-contents.html).

* (3.0) `/v4/edges` This endpoint allows querying edges inside a catalog. For more information see the [/v4/edges documentation](./edges.html)

* (3.0) `/v4/reports/<hash>/events` This convenience endpoint allows you to show events for a particular report by its hash. See the [/v4/reports documentation](./reports.html)

* (3.0) `/v4/reports/<hash>/metrics` This endpoint allows you to show metrics for a particular report by its hash. See the [/v4/reports documentation](./reports.html)

* (3.0) `/v4/reports/<hash>/logs` This endpoint allows you to show logs for a particular report by its hash. See the [/v4/reports documentation](./reports.html)

* (3.0) `/v4/catalogs/<node>/[resources|edges]` Both of these endpoints provide convenience for drilling into resources & edges data specific to a particular catalog. See [/v4/catalogs documentation](./catalogs)

#### Features affecting all endpoints

* (3.0) Extract is available as a top-level query operator, useful for selecting only
  certain fields from a response. See the [documentation on the extract operator](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/operators.html#extract) for more information.

* (2.2.0) The `in` and `extract` operators have been changed to accept multiple fields,
  allowing more concise subquerying as explained here:
  <https://github.com/puppetlabs/puppetdb/pull/1053>

#### /v4/events

* (3.0) The v4 events endpoint does not require a query parameter, so /v4/events is
  now a valid query. See the [events endpoint documentation](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/events.html#get-v4events) for more information.

#### /v4/reports

* (3.0) The response of the reports endpoint includes the new fields `noop`,
  `environment`, `status`, `resource_events`, `logs`, and `metrics`.  For more information see the [documentation on the reports endpoint](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/reports.html). For comparison, see [an example of the new format](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/reports.html#examples) and [an example of the old format](https://docs.puppetlabs.com/puppetdb/latest/api/query/v3/reports.html#response-format).

* (3.0) The reports endpoint takes a `latest_report?` query to return only reports
  associated with the most recent puppet run for their nodes. Similar to the
  corresponding events query, there is no corresponding field in the response.
  For more information see the [documentation on the report query fields](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/reports.html#query-fields).

#### /v4/catalogs

* (3.0) The v4 catalogs endpoint is queryable like the other endpoints, whereas
  before it could only return a catalog for a single host. The old query format
  (/v4/catalogs/myhost) still works as before, but /v4/catalogs returns results
  too. For more information, see the [catalog query examples](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/catalogs.html#examples).


#### Operators

* (2.2.0) The new `select_fact_contents` subquery operator allows for filtering the
  results of other endpoints based on detailed queries about structured fact
  values. This is exhibited on the bottom of the [subquery examples documentation](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/operators.html#subquery-examples).

* (2.2.0) We have added the regexp array match operator `~>` for querying fact paths on
  the `fact-contents` or `fact-paths endpoints`. This is documented with the other [operators](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/operators.html#regexp-array-match).
  An example of usage is given at the bottom of the subquery-examples page
  linked just above.

* (3.0) We have added the `group_by` and `function` operators, as well as
  support for the `count` function. For more information, see the [operators documentation](https://docs.puppetlabs.com/puppetdb/master/api/query/v4/operators.html#function).
