---
title: "Entities"
layout: default
canonical: "/puppetdb/latest/api/query/v4/entities.html"
---

# Entities

[aggregate-event-counts]: aggregate-event-counts.markdown
[catalogs]: catalogs.markdown
[edges]: edges.markdown
[environments]: environments.markdown
[event-counts]: event-counts.markdown
[events]: events.markdown
[fact-names]: fact-names.markdown
[facts]: facts.markdown
[fact-contents]: fact-contents.markdown
[fact-paths]: fact-paths.markdown
[nodes]: ./nodes.markdown
[producers]: producers.markdown
[query]: query.markdown
[reports]: reports.markdown
[resources]: resources.markdown
[root]: index.markdown
[from]: ast.markdown#context-operators
[subquery]: ast.markdown#subquery-operators
[erd]: ../../../images/pdb_erd.png

The PuppetDB API provides access to a series of data entities that map to the Puppet ecosystem
and the data that PuppetDB stores.

![erd][erd]

## Entity types

The following table shows the list of available entities, with their respective REST endpoints for
direct querying.

The entity name is utilized within queries themselves, in particular within the [`from`][from]
and [`subquery`][subquery] operators.

Entity Name                                        | REST Endpoint
---------------------------------------------------|---------------------------------------------------------------
[`aggregate_event_counts`][aggregate-event-counts] | [/pdb/query/v4/aggregate-event-counts][aggregate-event-counts]
[`catalogs`][catalogs]                             | [/pdb/query/v4/catalogs][catalogs]
[`edges`][edges]                                   | [/pdb/query/v4/edges][edges]
[`environments`][environments]                     | [/pdb/query/v4/environments][environments]
[`event_counts`][event-counts]                     | [/pdb/query/v4/event-counts][event-counts]
[`events`][events]                                 | [/pdb/query/v4/events][events]
[`facts`][facts]                                   | [/pdb/query/v4/facts][facts]
[`fact_contents`][fact-contents]                   | [/pdb/query/v4/fact-contents][fact-contents]
[`fact_names`][fact-names]                         | [/pdb/query/v4/fact-names][fact-names]
[`fact_paths`][fact-paths]                         | [/pdb/query/v4/fact-paths][fact-paths]
[`nodes`][nodes]                                   | [/pdb/query/v4/nodes][nodes]
[`producers`][producers]                           | [/pdb/query/v4/producers][producers]
[`reports`][reports]                               | [/pdb/query/v4/reports][reports]
[`resources`][resources]                           | [/pdb/query/v4/resources][resources]

You can also query a particular entity using the [root endpoint][root] by using a top-level [`from`][from]
operator.
