---
title: "PuppetDB 4.1: Facts wire format, version 5"
layout: default
canonical: "/puppetdb/latest/api/wire_format/facts_format_v5.html"
---

## Facts wire format: Version 5

Facts are represented as JSON. Unless otherwise noted, `null` is not
allowed anywhere in the set of facts.

    {"certname": <string>,
     "environment": <string>,
     "producer_timestamp": <datetime>,
     "producer": <string>,
     "values": {
         <string>: <any>,
         ...
         }
    }

The `"certname"` key is the certname the facts are associated with.

The `"environment"` key is the environment associated to the node when the facts were collected.

The `"values"` key points to a _JSON Object_ that represents the set
of facts. Each key is the fact name, and the value is the fact value.

The `"producer_timestamp"` key points to a timestamp reflecting
the time of fact set submission from the master to PuppetDB.

The `"producer"` key is the certname of the Puppet master that sends the fact set to PuppetDB.

Fact names and values **must** be strings.

## Encoding

The entire fact set is expected to be valid JSON, which mandates UTF-8
encoding.
