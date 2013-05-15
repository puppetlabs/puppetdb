---
title: "PuppetDB 1.3 » API » Catalog Wire Format, Version 1"
layout: default
canonical: "/puppetdb/latest/api/wire_format/catalog_format.html"
---

[containment]: /puppet/2.7/reference/lang_containment.html
[relationship]: /puppet/2.7/reference/lang_relationships.html
[chain]: /puppet/2.7/reference/lang_relationships.html#chaining-arrows
[metaparameters]: /puppet/2.7/reference/lang_relationships.html#relationship-metaparameters
[require]: /puppet/2.7/reference/lang_relationships.html#the-require-function
[resource_ref]: /puppet/2.7/reference/lang_datatypes.html#resource-references
[numbers]: /puppet/2.7/reference/lang_datatypes.html#numbers
[undef]: /puppet/2.7/reference/lang_datatypes.html#undef
[namevar]: /puppet/2.7/reference/lang_resources.html#namenamevar
[resource]: /puppet/2.7/reference/lang_resources.html
[title]: /puppet/2.7/reference/lang_resources.html#title
[type]: /puppet/2.7/reference/lang_resources.html#type
[attributes]: /puppet/2.7/reference/lang_resources.html#attributes
[replace2]: ../commands.html#replace-catalog-version-2
[replace1]: ../commands.html#replace-catalog-version-1

PuppetDB receives catalogs from puppet masters in the following wire format. This format is subtly different from the internal format used by Puppet so catalogs are converted by the [PuppetDB terminus plugins](../../connect_puppet_master.html) before they are sent. [See below][below] for the justification for this separate format.

Catalog Interchange Format
-----

### Version

This is **version 1** of the catalog interchange format, which is used by PuppetDB 1 (and all pre-1.0 releases).


### Encoding

The entire catalog is serialized as JSON, which requires strict UTF-8 encoding. Unless otherwise noted, null is not allowed anywhere in the catalog.

### Main Data Type: Catalog

A catalog is a JSON object with two keys: `"metadata"` and `"data"`. [Version 2 of the "replace catalog" command][replace2] will strictly validate this object and throw an error in the event of missing or extra fields. [Version 1 of the "replace catalog" command][replace1] will silently tolerate some inexact catalog objects.

    {"metadata": {
        "api_version": 1
        }
     "data": {
        "name": <string>,
        "version": <string>,
        "edges":
            [<edge>, <edge>, ...],
        "resources":
            [<resource>, <resource>, ...]
        }
    }

The value of the `"metadata"` key must be `{ "api_version": 1 }` --- no other value is valid for this version of the format.

The value of the `"data"` key must be a JSON object with four keys: `"name"`, `"version"`, `"edges"`, and `"resources"`. Each of the keys is mandatory, although values that are lists may be empty lists. The value of each key in the data object is as follows:

`"name"`

: String. The name of the node for which the catalog was compiled.

`"version"`

: String. An arbitrary string that uniquely identifies this specific catalog across time for a single node. This is controlled by Puppet's [`config_version` setting](/references/latest/configuration.html#configversion) and is usually the seconds elapsed since the epoch.

`"edges"`

: List of [`<edge>` objects](#data-type-edge). **Every** [relationship][] between any two resources in the catalog, which may have been made with [chaining arrows][chain], [metaparameters][], or [the `require` function][require].

  > **Notes:**
  >
  > * "Autorequire" relationships are not currently encoded in the catalog.
  > * This key is significantly different from its equivalent in Puppet's internal catalog format, which only encodes containment edges.

`"resources"`

: List of [`<resource>` objects](#data-type-resource). Contains **every** resource in the catalog.


### Data Type: `<string>`

A JSON string. Because the catalog is UTF-8, these must also be UTF-8.

### Data Type: `<integer>`

A JSON int.

### Data Type: `<boolean>`

A JSON boolean.

### Data Type: `<edge>`

A JSON object of the following form, which represents a [relationship][] between two resources:

    {"source": <resource-spec>,
     "target": <resource-spec>,
     "relationship": <relationship>}

All edges are normalized so that the "source" resource is managed **before** the "target" resource. To do this, the Puppet language's "require" and "subscribe" [relationship types][relationship] are munged into "required-by" and "subscription-of" when they are converted into edges.

The keys of an edge are `source`, `target`, and `relationship`, all of which are required.

`source`

: A [`<resource-spec>`](#data-type-resource-spec). The resource which should be managed **first.**

`target`

: A [`<resource-spec>`](#data-type-resource-spec). The resource which should be managed **second.**

`relationship`

: A [`<relationship>`](#data-type-relationship). The way the two resources are related.

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

`type`

: String. The [type][] of the resource, **capitalized.** (E.g. `File`, `Service`, `Class`, `Apache::Vhost`.) Note that every segment must be capitalized if the type includes a namespace separator (`::`).

`title`

: String. The [title][] of the resource.

`aliases`

: List of strings. Includes **every** alias for the resource, including the value of its [name/namevar][namevar] and any extra names added with the `"alias"` metaparameter.

`exported`

: Boolean. Whether or not this is an exported resource.

`file`

: String. The manifest file in which the resource definition is located.

`line`

: Positive integer. The line (of the containing manifest file) at which the resource definition can be found.

`tags`

: List of strings. Includes every tag the resource has. This is a normalized superset of the value of the resource's `tag` attribute.

`parameters`

: JSON object. Includes all of the resource's [attributes][] and their associated values. The value of an attribute may be any JSON data type, but Puppet will only provide booleans, strings, arrays, and hashes --- [resource references][resource_ref] and [numbers][] in attributes are converted to strings before being inserted into the catalog. Attributes with [undef][] values are not added to the catalog.




Why a new wire format?
-----

[below]: #why-a-new-wire-format

###  Previous Wire Format Shortcomings

There were a number of issues with the built-in JSON wire format used
in Puppet prior to PuppetDB:

1. The format isn't actually JSON, it's PSON. This means a catalog may contain non-UTF-8 data. This can present problems for conforming JSON parsers that expect Unicode.
2. Dependency edges aren't represented as first-class entities in the wire format. Instead, dependencies have to be parsed out of resource attributes.
3. Containment edges can point to resources that aren't in the catalog's list of resources. Examples of this include things like `Stage[main]`, or other special classes.
4. There are no (good) provisions for binary data, which can show up in a catalog via use of `generate`, among other functions.
5. Resources can refer to other resources in several ways: by proper name, by alias, by using a type-specific namevar (such as `path` for the file type). None of this is normalized in any way, and consumers of the wire format have to sift through all of this. And for the case of type-specific namevars, it may be impossible for a consumer to reconcile (because the consumer may not have access to puppet source code)

In general, for communication between master and agent, it's useful to have the wire format as stripped-down as possible. But for other consumers, the catalog needs to be precise in its semantics. Otherwise, consumers just end up (poorly) re-coding the catalog-manipulation logic from puppet proper. Hence the need for a wire format that allows consuming code (which may not even originate from puppet) to handle this data.


### Differences from Current Wire Format

1. The format is fully documented here.
2.  Information that previously had to be deduced by Puppet is now codified inside of the wire format. All possible aliases for a resource are listed as attributes of that resource. The list of edges now contains edges of all types, not just containment edges. And that list of edges is normalized to refer to the `Type` and `Title` of a resource, as opposed to referring to it by any of its aliases.
3. The new format is explicitly versioned. This format is version 1.0.0, unambiguously.
4. Catalogs will be explictly transformed into this format. Currently, the behavior of `#to_pson` is simply expected to "Do The Right Thing" in terms of serialization.

### Future Development Goals

1. Binary data support is yet to be developed.
2. The use of a more compact, binary representation of the wire format may be considered. For example, using something like MessagePack, BSON, Thrift, or Protocol Buffers.
