---
title: "PuppetDB 1.6 » API » Facts Wire Format Version 2"
layout: default
canonical: "/puppetdb/latest/api/wire_format/facts_format_v2.html"
---


## Facts Wire Format - Version 2

Facts are represented as JSON. Unless otherwise noted, `null` is not
allowed anywhere in the set of facts.

    {"name": <string>,
     "environment": <string>,
     "values": {
         <string>: <string>,
         ...
         }
    }

The `"name"` key is the certname the facts are associated with.

The `"environment"` key is the environment associated to the node when the facts were collected.

The `"values"` key points to a JSON _Object_ that represents the set
of facts. Each key is the fact name, and the value is the fact value.

Fact names and values MUST be strings.

## Encoding

The entire fact set is expected to be valid JSON, which mandates UTF-8
encoding.


Differences with the fact wire format version 1
-----

1. Added an "environment" key to the top-level facts object

