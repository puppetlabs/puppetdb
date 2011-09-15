# Resources

The resource data model is focused on the search needs of the StoreConfig system.  It is designed to efficiently return data as required to collect exported resources.

## Resource Data Model (`application/vnd.com.puppetlabs.cmdb.resource+json`)

An individual resource node, as found in a catalog.  Individual resource references are static, immutable objects; they never change, although they may vanish from the collection or be replaced by other objects.

*field* | *type* | *count* | *detail*
------- | ------ | ------- | --------
`uri`   | URI    | 1 | The URI that identifies this resource.
`type`  | String | 1 | the type of the resource.
`title` | String | 1 | the title of the resource.
`exported` | Boolean | 1 | Was this resource exported by the node?
`parameters` | Map | 1 | The parameters of this resource.

REVISIT: This is missing fields that are in the catalog, but are not required for the StoreConfigs use-case.  We should also support returning those, perhaps optionally to avoid wasting bandwidth??

The value of the `parameters` field has the structure:

*field* | *type*   | *count* | *detail*
------- | -------- | ------- | --------
`name`  | String   | 1 | The name of the parameter.
`value` | String[] | 1 | The value(s) of the parameter.

The CMDB MAY represent a single parameter value as an array containing a single string, even if only one value is semantically possible in the Puppet type.


## Resource Search

To support the StoreConfig system, the resource model has a search collection representing *all* known resources that are currently in catalogs on the server.

This is a *live* query of the data: as catalog content is added and removed the results of the query will change.

REVISIT: Do we want to support a *static* enumeration here?  We could do that, by "creating" a search resource, enumerating it, and allowing the server to garbage collect by having a mandatory TTL on the collection.

REVISIT: Even without making the enumeration static, do we want to support a Splunk-like "create search, read search" model?  By telling the client that we should keep the query live for a TTL, but they MUST recreate it if we return 404 (and signal no-content with 204), we can ensure that both sides can efficiently manage state without too much risk.

REVISIT: I don't know if I like the transient resource model better, although it is flexible.  For now, doing a single endpoint with a *very* simple format for input is enough to get us going.  When that gets complex?  I think this is the model to follow, but ... see what happens when we build it.



### `/resources` (`application/vnd.com.puppetlabs.cmdb.resource-list+json`)

Access all resources from all catalogs.  The returned data is an array containing `application/vnd.com.puppetlabs.cmdb.resource+json` objects.

#### Parameters

`query`
: The JSON representation of the query to return data from.

In addition to the custom parameters, resources query is a paged query, so respects the normal fields for [pagination](common.md#Pagination).

#### Query Format

The `query` parameter is a JSON encoded array representing the structured query, representing the following structure:

    query: [ {type} {query}+ ] | [ {match} {field} {value} ]
    field:  string | [ string+ ]
    value:  string
    type:   "or" | "and" | "not"
    match:  "="

For example, for file resources, tagged "magical", on any host except for "example.local" the JSON query structure would be:

    ["and" ["not" ["=" ["node", "certname"] "example.local"]]
           ["=" ["node" "fact" "example"] "whatever"]
           ["=" "type" "File"]
           ["=" "tag"  "magical"]
           ["=" ["parameter", "ensure"] "enabled"]

The conditional type behaviours are defined:

`or`
: If *any* condition is true the result is true.

`and`
: If *all* conditions are true the result is true.

`not`
: If *none* of the conditions are true the result is true.

The match operator behaviours are defined:

`=`
: Exact string equality of the field and the value.

#### Returned Data Format

An array of zero or more resource objects.  This is the entire set of objects found matching the query.
