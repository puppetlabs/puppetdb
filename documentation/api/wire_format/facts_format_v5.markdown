---
title: "PuppetDB 3.2: Facts wire format, version 5"
layout: default
canonical: "/puppetdb/latest/api/wire_format/facts_format_v5.html"
---

## Facts wire format: Version 5

PuppetDB receives facts from Puppet masters in the following wire format.

    {"certname": <string>,
     "environment": <string>,
     "producer_timestamp": <datetime>,
     "values": {
         <string>: <any>,
         ...
         }
    }


#### `certname`

String. The certname the facts are associated with.

#### `environment`

String. The environment associated to the node when the facts were collected.

#### `values`

_JSON Object_. Represents the set of facts. Each key is the fact name, and the
value is the fact value.

Fact names and values **must** be strings.

#### `producer_timestamp`

DateTime. A timestamp reflecting the time of fact set submission from the master
to PuppetDB.

## Encoding

The entire fact set is expected to be valid JSON, which mandates UTF-8 encoding.
Unless otherwise noted, `null` is not allowed anywhere in the set of facts.
