---
title: "PuppetDB 2.1 » API » Overview"
layout: default
canonical: "/puppetdb/latest/api/index.html"
---

[commands]: ./commands.html
[terminus]: ../connect_puppet_master.html

Since PuppetDB collects lots of data from Puppet, it's an ideal platform for new tools and applications that use that data. You can use the HTTP API described in these pages to interact with PuppetDB's data.

Summary
-----

PuppetDB's API uses a Command/Query Responsibility Separation (CQRS) pattern. This means:

* Data can be **queried** using a standard REST-style API. Queries are processed immediately.
* When **making changes** to data (facts, catalogs, etc), you must send an explicit **command** (as opposed to submitting data without comment and letting the receiver determine intent). Commands are processed asynchronously in FIFO order.

The PuppetDB API consists of the following parts:

* [The REST interface for queries](#queries)
* [The HTTP command submission interface](#commands)
* [The wire formats that PuppetDB requires for incoming data](#wire-formats)

Queries
-----

PuppetDB 1.3 (and later) supports versions 1 and 2 of the query API. Version 1 is backwards-compatible with PuppetDB 1.0.x, but version 2 has significant new capabilities, including subqueries.

PuppetDB's data can be queried with a REST API.

* [Specification of the General Query Structure](./query/v3/query.html)
* [Available Operators](./query/v3/operators.html)
* [Query Tutorial](./query/tutorial.html)
* [Curl Tips](./query/curl.html)

The available query endpoints are documented in the pages linked below.

### Query Endpoints

#### Version 3

Version 3 of the query API added new endpoints, and introduces paging and sorting operations. This is the current stable API.

* [Facts Endpoint](./query/v3/facts.html)
* [Resources Endpoint](./query/v3/resources.html)
* [Nodes Endpoint](./query/v3/nodes.html)
* [Fact-Names Endpoint](./query/v3/fact-names.html)
* [Metrics Endpoint](./query/v3/metrics.html)
* [Reports Endpoint](./query/v3/reports.html)
* [Events Endpoint](./query/v3/events.html)
* [Event Counts Endpoint](./query/v3/event-counts.html)
* [Aggregate Event Counts Endpoint](./query/v3/aggregate-event-counts.html)
* [Server Time Endpoint](./query/v3/server-time.html)

#### Version 4 (Experimental)

Version 4 of the query API is currently experimental and may change without notice. For stability it is recommended to use the v3 query API.

* [Facts Endpoint](./query/v4/facts.html)
* [Resources Endpoint](./query/v4/resources.html)
* [Nodes Endpoint](./query/v4/nodes.html)
* [Fact-Names Endpoint](./query/v4/fact-names.html)
* [Fact-Nodes Endpoint](./query/v4/fact-nodes.html)
* [Factsets Endpoint](./query/v4/factsets.html)
* [Fact-Paths Endpoint](./query/v4/fact-paths.html)
* [Metrics Endpoint](./query/v4/metrics.html)
* [Reports Endpoint](./query/v4/reports.html)
* [Events Endpoint](./query/v4/events.html)
* [Event Counts Endpoint](./query/v4/event-counts.html)
* [Aggregate Event Counts Endpoint](./query/v4/aggregate-event-counts.html)
* [Server Time Endpoint](./query/v4/server-time.html)

#### Version 2 (Deprecated)

Version 2 of the query API is deprecated and will be retired soon. For stability it is recommended to use the v3 query API instead.

* [Facts Endpoint](./query/v2/facts.html)
* [Resources Endpoint](./query/v2/resources.html)
* [Nodes Endpoint](./query/v2/nodes.html)
* [Fact-Names Endpoint](./query/v2/fact-names.html)
* [Metrics Endpoint](./query/v2/metrics.html)

Commands
-----

Commands are sent via HTTP but do not use a REST-style interface.

PuppetDB supports a relatively small number of commands. The command submission interface and the available commands are all described at the commands page:

* [Commands (all commands, all API versions)][commands]

Unlike the query API, these commands are generally only useful to Puppet itself, and all format conversion and command submission is handled by the [PuppetDB terminus plugins][terminus] on your puppet master.

The "replace" commands all require data in one of the wire formats described below.

Wire Formats
-----

All of PuppetDB's "replace" commands contain payload data, which must be in one of the following formats. These formats are also linked from the [commands](#commands) that use them.

* [Facts wire format version 3](./wire_format/facts_format_v3.html)
* [Catalog wire format version 5](./wire_format/catalog_format_v5.html)
* [Report wire format version 3](./wire_format/report_format_v3.html)
* Deprecated - [Facts wire format version 1](./wire_format/facts_format_v1.html)
* Deprecated - [Facts wire format version 2](./wire_format/facts_format_v2.html)
* Deprecated - [Catalog wire format version 1](./wire_format/catalog_format_v1.html)
* Deprecated - [Catalog wire format version 4](./wire_format/catalog_format_v4.html)
* Deprecated - [Report wire format version 1](./wire_format/report_format_v1.html)
