## Problems we're trying to solve

There are a number of issues with the built-in JSON wire format used
in Puppet:

1. The format isn't actually JSON, it's PSON. Notably, a catalog can
contain non-UTF-8 data. This presents problems for conforming JSON
parsers.

2. Dependency edges aren't represented as first-class entities in the
wire format. Instead, dependencies have to be parsed out of resource
attributes.

3. Containment edges can point to resources that aren't in the
catalog's list of resources. Things like Stage[main], or other special
classes are good examples of this.

4. There are no (good) provisions for binary data, which can show up
in a catalog via use of generate, among other functions

5. Resources can refer to other resources by proper name, by alias, by
using a type-specific namevar (such as $path for the file type). None
of this is normalized in any way, and consumers of the wire format
have to sift through all of this. And for the case of type-specific
namevars, it may be impossible for a consumer to reconcile (because
the consumer may not have access to puppet source code)

In general, for communication between master and agent I can see how
it's useful to have the wire format be as stripped-down as
possible. But for other consumers, the catalog needs to be precise in
its semantics. Otherwise, consumers just end up (poorly) re-coding the
catalog-manipulation logic from puppet proper. We need to think beyond
our current codebase and begin thinking about how consuming code (that
may not even be written by us) can handle this data.

## Catalog interchange format

A catalog is represented as JSON (this implies UTF-8 encoding). Unless
otherwise noted, null is not allowed anywhere in the catalog.

    {"metadata": {
        "type": "catalog",
        "version": 1
        }
     "data": {
        "name": <string>,
        "version": <string>,
        "classes":
            [<string>, <string>, ...],
        "tags":
            [<string>, <string>, ...],
        "edges":
            [<edge>, <edge>, ...],
        "resources":
            [<resource>, <resources>, ...]
        }
    }

All keys are mandatory, though values that are lists may be empty
lists.

`"name"` is the certname the catalog is associated with.

`"version"` is an arbitrary tag to uniquely identify this catalog
across time for a single node.

### Encoding

The entire catalog is expected to be valid JSON, which implies UTF-8
encoding.

### Data type: `<string>`

A JSON string. By virtue of the catalog being UTF-8, these must also
be UTF-8.

### Data type: `<integer>`

A JSON int.

### Data type: `<boolean>`

A JSON boolean.

### Data type: `<edge>`

A JSON Object of the following form:

    {"source": <resource-spec>,
     "target": <resource-spec>,
     "relationship": <relationship>}

All keys are required.

### Data type: `<resource-spec>`

Synonym: `<resource-hash>`. A JSON Object of the following form:

    {"type": <string>,
     "title": <string>}

For every resource-spec mentioned in the catalog, there *must* be a
resource in the "resources" list with a matching type and title
attributes. This implies certain things, such as edges referring to
resources by their resource-spec, and not by an alias or implicit
namevar.

### Data type: `<relationship>`

One of the following strings:

* "contains"
* "required-by"
* "notifies",
* "before"
* "subscription-of"

The intent is to mirror Puppet's `->` construct in terms of what's the
source of an edge, and what's a target.

### Data type: `<resource>`

    {"type": <string>,
     "title": <string>,
     "aliases": [<string>, <string>, ...],
     "exported": <boolean>,
     "file": <string>,
     "line": <string>,
     "tags": [<string>, <string>, ...],
     "parameters": {<string>: JSON Object,
                    <string>: JSON Object,
                    ...}
    }

All keys are required. The `"aliases"` list must include all aliases
for a resource beyond the title itself. This includes names mentioned
in explicit `"alias"` parameters, or implied namevars of the type
itself.

## Notable differences from the current wire format

First, it's actually documented somewhere. That sounds glib, but it's
a huge deal.

Second, information that can only be deduced on the puppetmaster is
codified inside of the wire format. All possible aliases for a
resource are listed as attributes of each resource. The list of edges
now contains edges of all types, not just containment edges. And that
list of edges is normalized to refer to the `Type` and `Title` of a
resource, as opposed to referring to it by any of its aliases.

Third, it's explicitly versioned. This format is version 1, and that's
that.

Fourth, we'll be explicitly transforming catalogs into this
format. Currently, we just rely on the behavior of `#to_pson` to Do
The Right Thing in terms of serialization.

## Gaps with this wire format

1. Composite namevars need to be dealt with

2. Binary data needs to be dealt with

3. We should consider using a more compact, binary representation of
the wire format, perhaps via something like MessagePack, BSON, Thrift,
or Protocol Buffers.
