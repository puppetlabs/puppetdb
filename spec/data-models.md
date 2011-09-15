# Puppet CMDB Data Models

## WARNING: Not The Official Formats!

----------

REVISIT: This is a first draft of thoughts, rather than a final spec.  You should look at the broken-out per-type documents to see the way things have actually gone.

----------

This specifies the models of resources that the CMDB manages and, concretely, exposes to the user through the query API.

Each resource model is represented as a JSON map, which contains primitive fields represented the obvious way in JSON, or nested data for other resources in the model.

Generally, the resource model favours returning more data than less, within the bounds of reasonable performance, since this gives the client a more consistent view of the ever-changing data underlying the network.


## Facts (`application/vnd.com.puppetlabs.cmdb.facts+json`)

The set of facts for a node, at a point in time.  The model is:

*field* | *type* | *count* | *detail*
------- | ------ | ------- | --------
`uri`      | URI | 1 | The URI that can be used to fetch an updated version of the resource.
`certname` | URI | 1 | The URI for the `certname` that these facts belong to.
`facts`    | Map | 1 | The set of facts, the fact name to the fact value.

At the moment the `facts` are all strings; this replicates the format facts in the rest of the network.


## Catalog Edge (`application/vnd.com.puppetlabs.cmdb.catalog-edge+json`)

An "edge" in a catalog, which represents a relationship between two Resources in the nodes catalog.

*field* | *type* | *count* | *detail*
------- | ------ | ------- | --------
`uri`   | URI    | 1 | The URI that can be used to fetch an updated version of the resource.
`from`  | URI    | 1 | The resource this relationship is from.
`to`    | URI    | 1 | The resource this relationship is to.
`type`  | String | 1 | The type of edge; this is an enumeration...


## Catalog (`application/vnd.com.puppetlabs.cmdb.catalog+json`)

A full catalog at a point in time.

*field* | *type* | *count* | *detail*
------- | ------ | ------- | --------
`uri`   | URI | 1 | The URI that can be used to fetch an update version of the resource.
`certname` | String | 1 | The certname of this node.
`classes` | String[] | 0..1 | The unordered set of classes applied to this catalog.
`tags` | String[] | 0..1 | The tags applied to this catalog.
`resources` | Resource[] | 0..1 | The resources contained in this catalog.
`edges` | Catalog Edge[] | 0..1 | The edges between resources in this catalog.
