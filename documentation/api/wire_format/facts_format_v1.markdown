---
title: "PuppetDB 2.1 » API » Facts Wire Format v1"
layout: default
canonical: "/puppetdb/latest/api/wire_format/facts_format_v1.html"
---

[facts_v2]: facts_format_v2.html

### Version

This is **version 1** of the facts interchange format and has been deprecated. See [version 2][facts_v2] for the currently supported version of this wire format.

## Format

Facts are represented as JSON. Unless otherwise noted, `null` is not
allowed anywhere in the set of facts.

    {"name": <string>,
     "values": {
         <string>: <string>,
         ...
         }
    }

The `"name"` key is the certname the facts are associated with.

The `"values"` key points to a JSON _Object_ that represents the set
of facts. Each key is the fact name, and the value is the fact value.

Fact names and values MUST be strings.

## Encoding

The entire fact set is expected to be valid JSON, which mandates UTF-8
encoding.
