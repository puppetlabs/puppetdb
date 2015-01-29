---
title: "PuppetDB 2.2 » API » Catalog Wire Format, Version 5"
layout: default
canonical: "/puppetdb/latest/api/wire_format/catalog_format_v5.html"
---

[containment]: /puppet/latest/reference/lang_containment.html
[relationship]: /puppet/latest/reference/lang_relationships.html
[chain]: /puppet/latest/reference/lang_relationships.html#chaining-arrows
[metaparameters]: /puppet/latest/reference/lang_relationships.html#relationship-metaparameters
[require]: /puppet/latest/reference/lang_relationships.html#the-require-function
[resource_ref]: /puppet/latest/reference/lang_datatypes.html#resource-references
[numbers]: /puppet/latest/reference/lang_datatypes.html#numbers
[undef]: /puppet/latest/reference/lang_datatypes.html#undef
[namevar]: /puppet/latest/reference/lang_resources.html#namenamevar
[resource]: /puppet/latest/reference/lang_resources.html
[title]: /puppet/latest/reference/lang_resources.html#title
[type]: /puppet/latest/reference/lang_resources.html#type
[attributes]: /puppet/latest/reference/lang_resources.html#attributes

PuppetDB receives catalogs from puppet masters in the following wire format. This format is subtly different from the internal format used by Puppet so catalogs are converted by the [PuppetDB catalog terminus](../../connect_puppet_master.html) before they are sent. [See below][below] for the justification for this separate format.

Catalog Interchange Format
-----

### Version

This is **version 6** of the catalog interchange format.


### Encoding

The entire catalog is serialized as JSON, which requires strict UTF-8 encoding. Unless otherwise noted, null is not allowed anywhere in the catalog.

### Main Data Type: Catalog

     {
      "name": <string>,
      "version": <string>,
      "environment": <string>,
      "transaction_uuid": <string>,
      "producer_timestamp": <datetime>,
      "edges":
          [<edge>, <edge>, ...],
      "resources":
          [<resource>, <resource>, ...]
     }

#### `name`

String. The name of the node for which the catalog was compiled.

#### `version`

String. An arbitrary string that uniquely identifies this specific catalog across time for a single node. This is controlled by Puppet's [`config_version` setting](/references/latest/configuration.html#configversion) and is usually the seconds elapsed since the epoch.

#### `environment`

String. The envrionment associated to the node when the catalog was compiled.

#### `edges`

List of [`<edge>` objects](#data-type-edge). **Every** [relationship][] between any two resources in the catalog, which may have been made with [chaining arrows][chain], [metaparameters][], or [the `require` function][require].

  > **Notes:**
  >
  > * "Autorequire" relationships are not currently encoded in the catalog.
  > * This key is significantly different from its equivalent in Puppet's internal catalog format, which only encodes containment edges.

#### `resources`

List of [`<resource>` objects](#data-type-resource). Contains **every** resource in the catalog.

#### `transaction_uuid`

String. A string used to match the catalog with the corresponding report that was issued during the same puppet run.
This field may be `null`.  (Note: support for this field was introduced in
[Version 3 of the "replace catalog" command][replace3].  Versions prior to version 3 will populate this field with
a `null` value.

#### `producer_timestamp`

Datetime.  The time of catalog submission from the master to PuppetDB.  This
field is currently populated by the master.  This field may be `null`.
Versions prior to version 5 will populate this with a `null` value.

### Data Type: `<string>`

A JSON string. Because the catalog is UTF-8, these must also be UTF-8.

### Data Type: `<integer>`

A JSON int.

### Data Type: `<boolean>`

A JSON boolean.

### Data Type: `<datetime>`
A JSON string representing a date and time (with time zone), formatted based on
the recommendations in ISO8601; so, e.g., for a UTC time, the String would be
formatted as `YYYY-MM-DDThh:mm:ss.sssZ`.  For non-UTC, the `Z` may be replaced
with `±hh:mm` to represent the specific timezone.

### Data Type: `<edge>`

A JSON object of the following form, which represents a [relationship][] between two resources:

    {"source": <resource-spec>,
     "target": <resource-spec>,
     "relationship": <relationship>}

All edges are normalized so that the "source" resource is managed **before** the "target" resource. To do this, the Puppet language's "require" and "subscribe" [relationship types][relationship] are munged into "required-by" and "subscription-of" when they are converted into edges.

The keys of an edge are `source`, `target`, and `relationship`, all of which are required.

#### `source`

A [`<resource-spec>`](#data-type-resource-spec). The resource which should be managed **first.**

#### `target`

A [`<resource-spec>`](#data-type-resource-spec). The resource which should be managed **second.**

#### `relationship`

A [`<relationship>`](#data-type-relationship). The way the two resources are related.

### Data Type: `<resource-spec>`

(Synonym: `<resource-hash>`.)

The JSON representation of a [resource reference][resource_ref] (single-resource kind). An object of the following form:

    {"type": <string>,
     "title": <string>}

The resource named by a resource-spec **must** exist in the catalog's `"resources"` list. Note also that the title must be the resource's actual [title][], rather than an alias or [name/namevar][namevar].

### Data Type: `<relationship>`

One of the following exact strings, when used in the `relationship` key of an [`<edge>` object](#data-type-edge):

* `contains`
* `before`
* `required-by`
* `notifies`
* `subscription-of`

**Note:** Regardless of the relationship type, the "source" resource is always managed **before** the "target" resource. This means that, functionally speaking, `required-by` is a synonym of `before` and `subscription-of` is a synonym of `notifies`. In this catalog format, the different relationship types preserve information about the _origin_ of the relationship.


String            | Relationship Type        | Origin of Relationship
------------------|--------------------------|-----------------------
`contains`        | [containment][]          | Class or defined type [containment][]
`before`          | ordering                 | `before` metaparam on source, or `->` chaining arrow
`required-by`     | ordering                 | `require` metaparam on target, or `require` function
`notifies`        | ordering w/ notification | `notify` metaparam on source, or `~>` chaining arrow
`subscription-of` | ordering w/ notification | `subscribe` metaparam on target


### Data Type: `<resource>`

A JSON object of the following form, which represents a [Puppet resource][resource]:

    {"type": <string>,
     "title": <string>,
     "aliases": [<string>, <string>, ...],
     "exported": <boolean>,
     "file": <string>,
     "line": <string>,
     "tags": [<string>, <string>, ...],
     "parameters": {<string>: <JSON object>,
                    <string>: <JSON object>,
                    ...}
    }

The eight keys in a resource object are `type`, `title`, `aliases`, `exported`, `file`, `line`, `tags` and `parameters`. All of them are **required.**

#### `type`

String. The [type][] of the resource, **capitalized.** (E.g. `File`, `Service`, `Class`, `Apache::Vhost`.) Note that every segment must be capitalized if the type includes a namespace separator (`::`).

#### `title`

String. The [title][] of the resource.

#### `aliases`

List of strings. Includes **every** alias for the resource, including the value of its [name/namevar][namevar] and any extra names added with the `"alias"` metaparameter.

#### `exported`

Boolean. Whether or not this is an exported resource.

#### `file`

String. The manifest file in which the resource definition is located.

#### `line`

Positive integer. The line (of the containing manifest file) at which the resource definition can be found.

#### `tags`

List of strings. Includes every tag the resource has. This is a normalized superset of the value of the resource's `tag` attribute.

#### `parameters`

JSON object. Includes all of the resource's [attributes][] and their associated values. The value of an attribute may be any JSON data type, but Puppet will only provide booleans, strings, arrays, and hashes --- [resource references][resource_ref] and [numbers][] in attributes are converted to strings before being inserted into the catalog. Attributes with [undef][] values are not added to the catalog.
