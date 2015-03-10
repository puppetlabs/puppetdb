---
title: "PuppetDB 3.0 » API » v4 » Upgrading from v3"
layout: default
canonical: "/puppetdb/latest/api/query/v4/upgrading-from-v3.html"
---

This document describes new features of the PuppetDB v4 API relative to v3,
and the actions developers will need to take to make their code compliant. This
is not a changelog for PuppetDB 3.0, since some of the changes described are
present in 2.2.x.

The major changes since v3 (encompassing some that are already available on v4
in 2.2.2 and some that are not yet released) break down as follow:

### CHANGES THAT MAY BREAK YOUR CODE

* All previously dash-separated field names (e.g receive-time), subquery
  operators (e.g select-facts), and query parameters (e.g order-by) are now
  underscore-separated. This change does not apply to dash-separated endpoint
  names such as `aggregate-event-counts`.

* We have renamed the "name" key of the catalogs endpoint to "certname", for
  consistency with other endpoints.

* The metrics endpoint has been split off into a separate service, and
  reversioned at v1. This means if you are currently accessing metrics at
  http://localhost:8080/v3/metrics/mbeans, you will now access them at
  http://localhost:8080/metrics/v1/mbeans and so on according to the documentation
  here: https://docs.puppetlabs.com/puppetdb/master/api/metrics/v1/index.html

* The v4 facts endpoint will return proper JSON rather than stringified JSON
  under the `value` field in the case of a structured fact. This is observable
  in current stable (PDB 2.2.x or PE 3.7) with the v4 API.

* The v4 catalogs endpoint no longer produces a `metadata` field or the
  `api_version` key within, and the contents of the former `data` field now
  compose the response body. The body contains the new fields
  `producer_timestamp`, `hash`, and `environment`, and the endpoint is
  documented here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/catalogs.html

* The only valid command submission versions for catalogs, reports, and facts
  at release time will be 6, 5, and 4. These are respectively documented at
  https://docs.puppetlabs.com/puppetdb/master/api/wire_format/catalog_format_v6.html,
  https://docs.puppetlabs.com/puppetdb/master/api/wire_format/report_format_v5.html,
  and
  https://docs.puppetlabs.com/puppetdb/master/api/wire_format/facts_format_v4.html.

* PuppetDB's mbeans (listed at /metrics/v1/mbeans) are no longer prefixed with
  "com."

### NEW ENDPOINTS

* `/v4/factsets` The factsets endpoint did not exist in v3, but is present in
  PuppetDB 2.2.x on the v4 API. PDB 3.0 will add a hash in the metadata.
  Documentation is here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/factsets.html#response-format

* `/v4/fact-paths` This endpoint is similar to the existing fact-names endpoint
  in that one expected use is GUI autocompletion. Then endpoint is documented
  here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/fact-paths.html

* `/v4/fact-contents` This endpoint allows fine-grained querying of facts and
  structured facts. Documentation here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/fact-contents.html

### NEW API FEATURES

* The response of the reports endpoint includes the new fields `noop`,
  `environment`, `status`, `resource_events`, `logs`, and `metrics`.  The
  endpoint in its latest form is documented here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/reports.html and an
  example of the new format is here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/reports.html#examples.
  For comparison, the old response is here:
  https://docs.puppetlabs.com/puppetdb/latest/api/query/v3/reports.html#response-format

* The reports endpoint takes a latest_report? query to return only reports
  associated with the most recent puppet run for their nodes. Similar to the
  corresponding events query, there is no corresponding field in the response.
  The report query fields are documented here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/reports.html#query-fields

* The v4 catalogs endpoint is queryable like the other endpoints, whereas
  before it could only return a catalog for a single host. The old query format
  (/v4/catalogs/myhost) still works as before, but /v4/catalogs returns results
  too. Examples are here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/catalogs.html#examples

* Extract is available as a top-level query operator, useful for selecting only
  certain fields from a response. Documentation is here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/operators.html#extract

* The v4 events endpoint does not require a query parameter, so /v4/events is
  now a valid query. The endpoint is documented here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/events.html#get-v4events

* The new select_fact_contents subquery operator allows for filtering the
  results of other endpoints based on detailed queries about structured fact
  values. This is exhibited on the bottom of the page here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/operators.html#subquery-examples

* We have added the regexp array match operator `~>` for querying fact paths on
  the `fact-contents` or `fact-paths endpoints`. This is documented here:
  https://docs.puppetlabs.com/puppetdb/master/api/query/v4/operators.html#regexp-array-match.
  An example of usage is given at the bottom of the subquery-examples page
  linked just above.

* The `in` and `extract` operators have been changed to accept multiple fields,
  allowing more concise subquerying as explained here:
  https://github.com/puppetlabs/puppetdb/pull/1053

### CHANGES STILL TO COME

  * add endpoints for dedicated querying of report logs and metrics
  * add `state-overview` endpoint for aggregate count queries over last report
  statuses (Puppet Enterprise only.)
  * the shape of the `resources` and `edges` fields of the catalogs endpoint
  will change in the following way:
  https://gist.github.com/wkalt/92402cea364f89facace where "data" is the
  original field and "href" is a url for an alternative place to find the
  data. The `metrics`, `logs`, and `resource_events` fields of the reports
  endpoint will be changed in the same way. For hsqldb users, only the "href"
  key will be available.
