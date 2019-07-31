---
title: "Configure expiration wire format, version 1 (experimental)"
layout: default
canonical: "/puppetdb/latest/api/wire_format/configure_expiration_v1.html"
---

The v1 `configure expiration` command tells PuppetDB whether or not
factsets for a given certname should ever be expired (and by
extension, the node itself).  By default, unless expiration has been
explicitly set to false by this command, factsets are deleted when the
node itself is purged.

Changing the value of this setting for a certname has the same effect
as receipt of a new factset with respect to extending the lifetime of
the node.

> *Note*: this is an experimental command, which might be altered or
> removed in a future release, and for the time being, PuppetDB
> exports will not include this information.

Configure expiration command format
-----

### Version

This is **version 1** of the configure expiration command.

### Encoding

The command is serialized as JSON, which requires strict UTF-8 encoding.

### Main data type: Configure expiration for node

     {
      "certname": <string>,
      "expire": {"facts": <boolean>},
      "producer_timestamp": <datetime>
     }

#### `certname`

String. The name of the node for which the expiration behavior should
be configured.

#### `expire`

JSON.  A map containing a single boolean `facts` value indicating
whether or not factsets should ever expire for the certname.

#### `producer_timestamp`

DateTime. The time of command submission from the Puppet master to PuppetDB,
according to the clock on the Puppet master.

`producer_timestamp` is optional but *highly* recommended. When provided, it is
used to determine the precedence between this command and other commands that
modify the same node. This field is provided by, and should thus reflect the
clock of, the Puppet master.

When `producer_timestamp` is not provided, the PuppetDB server's local time is
used. If another command is received for a node while a non-timestamped
"deactivate node" command is pending processing, the results are *undefined*.

### Data type: `<string>`

A JSON string. Because the command is UTF-8, these must also be UTF-8.

### Data type: `<boolean>`

A JSON Boolean.

### Data type: `<datetime>`

A JSON string representing a date and time (with time zone), formatted based on
the recommendations in ISO 8601. For example, for a UTC time, the string would be
formatted as `YYYY-MM-DDThh:mm:ss.sssZ`. For non-UTC time, the `Z` may be replaced
with `±hh:mm` to represent the specific timezone.
