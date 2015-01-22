---
title: "PuppetDB 2.2 » API » Overview"
layout: default
canonical: "/puppetdb/latest/api/index.html"
---

[commands]: ./commands.html
[termini]: ../connect_puppet_master.html

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

* [Specification of the General Query Structure](./query/v4/query.html)
* [Available Operators](./query/v4/operators.html)
* [Query Tutorial](./query/tutorial.html)
* [Curl Tips](./query/curl.html)

The available query endpoints are documented in the pages linked below.

### Query Endpoints

#### Version 4

This is the current stable API.

* [Nodes Endpoint](./query/v4/nodes.html)
* [Environments Endpoint](./query/v4/environments.html)
* [Factsets Endpoint](./query/v4/factsets.html)
* [Facts Endpoint](./query/v4/facts.html)
* [Fact-Names Endpoint](./query/v4/fact-names.html)
* [Fact-Paths Endpoint](./query/v4/fact-paths.html)
* [Fact-Contents Endpoint](./query/v4/fact-contents.html)
* [Catalogs Endpoint](./query/v4/catalogs.html)
* [Resources Endpoint](./query/v4/resources.html)
* [Reports Endpoint](./query/v4/reports.html)
* [Events Endpoint](./query/v4/events.html)
* [Event Counts Endpoint](./query/v4/event-counts.html)
* [Aggregate Event Counts Endpoint](./query/v4/aggregate-event-counts.html)
* [Metrics Endpoint](./query/v4/metrics.html)
* [Server Time Endpoint](./query/v4/server-time.html)
* [Version Endpoint](./query/v4/version.html)

#### Version 3 (Retired)

Version 3 of the query API has been retired.  Please use v4.

#### Version 2 (Retired)

Version 2 of the query API has been retired.  Please use v4.

Commands
-----

Commands are sent via HTTP but do not use a REST-style interface.

PuppetDB supports a relatively small number of commands. The command submission interface and the available commands are all described at the commands page:

* [Commands (all commands, all API versions)][commands]

Unlike the query API, these commands are generally only useful to Puppet itself, and all format conversion and command submission is handled by the [PuppetDB termini][termini] on your puppet master.

The "replace" commands all require data in one of the wire formats described below.

Wire Formats
-----

All of PuppetDB's "replace" commands contain payload data, which must be in one of the following formats. These formats are also linked from the [commands](#commands) that use them.

* [Facts wire format version 3](./wire_format/facts_format_v3.html)
* [Catalog wire format version 5](./wire_format/catalog_format_v5.html)
* [Report wire format version 3](./wire_format/report_format_v3.html)
