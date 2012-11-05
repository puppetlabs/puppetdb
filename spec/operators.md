# Query Operators

## v2

### Binary operators

Each of these operators accepts two arguments: a field, and a value. The
allowed fields for each endpoint are documented in the spec for that endpoint.
The numeric operators (every operator except `=`) will try to coerce their
arguments to float or integer. If they can't be coerced, the operator will
return false. `=` is pure string equality ("0" is not = to "0.0").

The list of binary operators is:

`= > < >= <=`

### Boolean operators

Each of these operators accepts a list of expressions, and applies a logical
operation to the results of those expressions.

`and`: True if every expression returns true  
`or`: True if any expression returns true  
`not`: True if no expression returns true  

### Subqueries

Sometimes, a query needs to correlate data from multiple sources, or multiple
rows. For instance, a query such as "fetch the IP addresses of all nodes with
Class[Apache]". Because this query uses both facts and resources, it needs to
include a subquery.

Available subquery operators:

`in-result`: This operator returns true if the supplied field exists in the
query results that follow.

`project`: This operator is used to extract the field we care about from the
resources, so that we can find the corresponding facts.

#### Examples

##### `select-resources`

This query expression queries the `/facts` endpoint for the IP address fact for
all nodes with Class[Apache]:

    ["and"
      ["=" ["fact" "name"] "ipaddress"]
      ["in-result" "certname"
        ["project" "certname"
          ["select-resources"
            ["and"
              ["=" "type" "Class"]
              ["=" "title" "Apache"]]]]]]

This operator is the meat of a resource subquery. It will execute a [resource
query](resources.md), returning the same results as though the query were
made against the `/resources` endpoint. In this case, the resource query is
`["and" ["=" "type" "Class"] ["=" "title" "Apache"]]`, which returns
resources matching Class[Apache].

##### `select-facts`

This query expression queries the `/facts` endpoint for the IP address fact of
all Debian nodes.

    ["and"
      ["=" ["fact" "name"] "ipaddress"]
      ["in-result" "certname"]
        ["project" "certname"
          ["select-facts"
            ["and"
              ["=" ["fact" "name"] "operatingsystem"]
              ["=" ["fact" "value"] "Debian"]]]]]]

This operator is similar to `select-resources`, but will make a subquery
[against facts](facts.md).
