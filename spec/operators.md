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
include a subquery. The query expression used for this behavior is:

["and"
  ["=" ["fact" "name"] "ipaddress"]
  ["in-result" ["fact" "certname"]
    ["project" "certname"
      ["select-resources"
        ["and"
          ["=" "type" "Class"]
          ["=" "title" "Apache"]]]]]]

While this expression is long, its behavior is fairly straightforward. Let's break it down:

  `select-resources`: This operator is the meat of the subquery. It will
  execute a [resource query](resources.md), returning the same results as
  though the query were made against the `/resources` endpoint. In this case,
  the resource query is `["and" ["=" "type" "Class"] ["=" "title" "Apache"]]`,
  which returns resources matching Class[Apache].

  `project`: This operator is used to extract the field we care about from the
  resources, so that we can find the corresponding facts. This query is
  extracting the certname field, indicating it wants to find facts for nodes
  which have the Class[Apache] resource.

  `in-result`: This operator returns true if the supplied field (in this case
  `["fact" "certname"]`) exists in the query results that follow. This query
  will return true for facts whose certnames appear in the list of certnames
  which have the Class[Apache] resource.
