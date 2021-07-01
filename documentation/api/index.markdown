---
title: "API overview"
layout: default
canonical: "/puppetdb/latest/api/index.html"
---

# API overview

[commands]: ./command/v1/commands.markdown
[termini]: ../connect_puppet_server.markdown
[ast]: ./query/v4/ast.markdown
[pql]: ./query/v4/pql.markdown

Because PuppetDB collects lots of data from Puppet, it's an ideal platform for new tools and applications that use that data. You can use the HTTP API described in these pages to interact with PuppetDB's data.

## Summary

PuppetDB's API uses a Command/Query Responsibility Separation (CQRS) pattern. This means:

* Data can be **queried** using a standard REST-style API. Queries are processed immediately.
* When **making changes** to data (facts, catalogs, etc.), you must send an explicit **command** (as opposed to submitting data without comment and letting the receiver determine intent). Commands are processed asynchronously in FIFO order.

The PuppetDB API consists of the following parts:

* [The REST interface for queries](#queries)
* [The HTTP command submission interface](#commands)
* [The wire formats that PuppetDB requires for incoming data](#wire-formats)

## Queries

PuppetDB's data can be queried with a REST API.

* [Specification of the general query structure](./query/v4/query.markdown)
* [AST query language][ast]
* [Puppet query language][pql]
* [Query tutorial](./query/tutorial.markdown)
* [Curl tips](./query/curl.markdown)

The available query endpoints are documented in the pages linked below.

### Query endpoints

#### Version 4

This is the current stable API.

* [Root Endpoint](./query/v4/index.markdown)
* [Entity Endpoints](./query/v4/entities.markdown)
* [Metrics Endpoint](./metrics/v1/mbeans.markdown)
* [Server Time Endpoint](./meta/v1/server-time.markdown)
* [Version Endpoint](./meta/v1/version.markdown)

#### Version 3 (Retired)

Version 3 of the query API has been retired. Please use v4.

#### Version 2 (Retired)

Version 2 of the query API has been retired. Please use v4.

## Commands

Commands are sent via HTTP but do not use a REST-style interface.

PuppetDB supports a relatively small number of commands. The command submission interface and all available commands are described at the [commands page][commands].

Unlike the query API, these commands are generally only useful to Puppet itself, and all format conversion and command submission is handled by the [PuppetDB-termini][termini] on your Puppet Server.

The "replace" commands all require data in one of the wire formats described below.

## Wire formats

All of PuppetDB's "replace" commands contain payload data, which must be in one of the following formats. These formats are also linked from the [commands](#commands) that use them.

* [Facts wire format version 4](./wire_format/facts_format_v4.markdown)
* [Catalog wire format version 6](./wire_format/catalog_format_v6.markdown)
* [Report wire format version 5](./wire_format/report_format_v5.markdown)
