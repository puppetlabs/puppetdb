---
title: "Replace catalog inputs wire format, version 1 (experimental)"
layout: default
canonical: "/puppetdb/latest/api/wire_format/catalog_inputs_format_v1.html"
---

# Replace catalog inputs wire format - v1 (experimental)

The v1 `replace catalog inputs` command tells PuppetDB to replace the current
set of catalog inputs with the new.

Changing the value of this setting for a certname has the same effect
as receipt of a new catalog with respect to extending the lifetime of
the node.

## Replace catalog inputs command format

### Version

This is **version 1** of the replace catalog inputs command.

### Encoding

The command is serialized as JSON, which requires strict UTF-8 encoding.

### Main data type: Replace catalog inputs for node

     {
      "certname": <string>,
      "producer_timestamp": <datetime>,
      "catalog_uuid": <string>,
      "inputs": [[<string>, <string>], ...],
     }

#### `certname`

String. The name of the node the catalog was compiled for.

#### `producer_timestamp`

DateTime. The time of command submission from the Puppet Server to PuppetDB,
according to the clock on the Puppet Server.

`producer_timestamp` is optional but *highly* recommended. When provided, it is
used to determine the precedence between this command and other commands that
modify the same node. This field is provided by, and should thus reflect the
clock of, the Puppet Server.

When `producer_timestamp` is not provided, the PuppetDB server's local time is
used. If another command is received for a node while a non-timestamped
"deactivate node" command is pending processing, the results are *undefined*.

#### `catalog_uuid`

The uuid of the catalog this input was used for. Since not every catalog will
provide a record of its inputs, and the catalog is replaced via a different
command, this may not be the same as the current catalog stored in PuppetDB.

#### `inputs`

The `inputs` to a catalog are a list of tuples, where the first element is its
`type` (ie. `"hiera"`) and the second is a unique name for the input (for a hiera
key it would just be its full key, `"puppetdb::globals::version"`.

### Data type: `<string>`

A JSON string. Because the command is UTF-8, these must also be UTF-8.

### Data type: `<datetime>`

A JSON string representing a date and time (with time zone), formatted based on
the recommendations in ISO 8601. For example, for a UTC time, the string would be
formatted as `YYYY-MM-DDThh:mm:ss.sssZ`. For non-UTC time, the `Z` may be replaced
with `±hh:mm` to represent the specific timezone.
