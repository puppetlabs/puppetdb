---
title: "PuppetDB 1.3 » API » Overview"
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

PuppetDB 1.3 supports versions 1 and 2 of the query API. Version 1 is backwards-compatible with PuppetDB 1.0.x, but version 2 has significant new capabilities, including subqueries.

PuppetDB's data can be queried with a REST API.

* [Specification of the General Query Structure](./query/v2/query.html)
* [Available Operators](./query/v2/operators.html)
* [Query Tutorial](./query/tutorial.html)
* [Curl Tips](./query/curl.html)

The available query endpoints are documented in the pages linked below.

### Query Endpoints

#### Version 2

Version 2 of the query API adds new endpoints, and introduces subqueries and regular expression operators for more efficient requests and better insight into your data. The following endpoints will continue to work for the foreseeable future. 

* [Facts Endpoint](./query/v2/facts.html)
* [Resources Endpoint](./query/v2/resources.html)
* [Nodes Endpoint](./query/v2/nodes.html)
* [Fact-Names Endpoint](./query/v2/fact-names.html)
* [Metrics Endpoint](./query/v2/metrics.html)

#### Version 1

Version 1 of the query API works with PuppetDB 1.1 and 1.0. It isn't deprecated, but we encourage you to use version 2 if you can.

In PuppetDB 1.0, you could access the version 1 endpoints without the `/v1/` prefix. This still works but **is now deprecated,** and we currently plan to remove support in PuppetDB 2.0. Please change your version 1 applications to use the `/v1/` prefix.

* [Facts Endpoint](./query/v1/facts.html)
* [Resources Endpoint](./query/v1/resources.html)
* [Nodes Endpoint](./query/v1/nodes.html)
* [Status Endpoint](./query/v1/status.html)
* [Metrics Endpoint](./query/v1/metrics.html)

#### Experimental

These endpoints are not yet set in stone, and their behavior may change at any time without regard for normal versioning rules. We invite you to play with them, but you should be ready to adjust your application on your next upgrade. 

* [Report Endpoint](./query/experimental/report.html)
* [Event Endpoint](./query/experimental/event.html)

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

* [Facts wire format](./wire_format/facts_format.html)
* [Catalog wire format](./wire_format/catalog_format.html)
* [Report wire format (experimental)](./wire_format/report_format.html)
