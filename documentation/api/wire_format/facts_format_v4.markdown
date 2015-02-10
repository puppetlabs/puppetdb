---
title: "PuppetDB 2.2 » API » Facts Wire Format Version 4"
layout: default
canonical: "/puppetdb/latest/api/wire_format/facts_format_v4.html"
---


## Facts Wire Format - Version 4

Facts are represented as JSON. Unless otherwise noted, `null` is not
allowed anywhere in the set of facts.

    {"name": <string>,
     "environment": <string>,
     "producer_timestamp": <datetime>,
     "values": {
         <string>: <any>,
         ...
         }
    }

The `"name"` key is the certname the facts are associated with.

The `"environment"` key is the environment associated to the node when the facts were collected.

The `"values"` key points to a JSON _Object_ that represents the set
of facts. Each key is the fact name, and the value is the fact value.

The `"producer_timestamp"` key points to a timestamp reflecting
the time of fact set submission from the master to PuppetDB.

Fact names and values MUST be strings.

## Encoding

The entire fact set is expected to be valid JSON, which mandates UTF-8
encoding.
