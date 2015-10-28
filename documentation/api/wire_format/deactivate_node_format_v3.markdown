---
title: "PuppetDB 3.2 » API » Deactivate Node Wire Format, Version 3"
layout: default
canonical: "/puppetdb/latest/api/wire_format/deactivate_node_format_v3.html"
---

PuppetDB receives deactivate node commands from Puppet masters in the following wire format.

Deactivate Node Command Format
-----

### Version

This is **version 3** of the deactivate node command.

### Encoding

The command is serialized as JSON, which requires strict UTF-8 encoding. 

### Upgrade notes

Previous versions of this command required only the certname, as a raw JSON
string. It is now formatted as a JSON map, and the `producer_timestamp` property
has been added.

### Main Data Type: Deactivate Node

     {
      "certname": <string>,
      "producer_timestamp": <datetime>
     }

#### `certname`

String. The name of the node for which the catalog was compiled.

#### `producer_timestamp`

Datetime.  The time of command submission from the master to PuppetDB,
according to the clock on the master.

`producer_timestamp` is optional but *highly* recommended. When provided, it is
used to determine the precedence between this command and other commands which
modify the same node. This field is provided by, and should thus reflect the
clock of, the Puppet master.

When `producer_timestamp` is not provided, the PuppetDB server's local time is
used.  If another command is received for a node while a non-timestamped
"deactivate node" command is pending processing, the results are *undefined*.

### Data Type: `<string>`

A JSON string. Because the command is UTF-8, these must also be UTF-8.

### Data Type: `<datetime>`

A JSON string representing a date and time (with time zone), formatted based on
the recommendations in ISO8601; so, e.g., for a UTC time, the String would be
formatted as `YYYY-MM-DDThh:mm:ss.sssZ`.  For non-UTC, the `Z` may be replaced
with `±hh:mm` to represent the specific timezone.
