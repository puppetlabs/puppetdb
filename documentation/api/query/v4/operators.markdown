---
title: "PuppetDB 3.2 » API » v4 » Query Operators"
layout: default
canonical: "/puppetdb/latest/api/query/v4/operators.html"
---

[root]: ./index.html
[catalogs]: ./catalogs.html
[edges]: ./edges.html
[environments]: ./environments.html
[events]: ./events.html
[facts]: ./facts.html
[fact-contents]: ./fact-contents.html
[fact-paths]: ./fact-paths.html
[nodes]: ./nodes.html
[query]: ./query.html
[reports]: ./reports.html
[resources]: ./resources.html
[entities]: ./entities.html

PuppetDB's [query strings][query] can use several common operators.

## Binary Operators

Each of these operators accepts two arguments: a **field,** and a
**value.** These operators are **non-transitive:** their syntax must always be:

    ["<OPERATOR>", "<FIELD>", "<VALUE>"]

The available fields for each endpoint are listed in that endpoint's documentation.

### `=` (equality)

**Works with:** strings, numbers, timestamps, booleans, arrays, multi, path

**Matches if:** the field's actual value is exactly the same as the provided value.

* Most fields are strings.
* Some fields are booleans.
* Arrays match if any **one** of their elements match.
* Path matches are a special kind of array, and must be exactly matched with this operator.

### `>` (greater than)

**Works with:** numbers, timestamps, multi

**Matches if:** the field is greater than the provided value.

### `<` (less than)

**Works with:** numbers, timestamps, multi

**Matches if:** the field is less than the provided value.

### `>=` (greater than or equal to)

**Works with:** numbers, timestamps, multi

**Matches if:** the field is greater than or equal to the provided value.

### `<=` (less than or equal to)

**Works with:** numbers, timestamps, multi

**Matches if:** the field is less than or equal to the provided value.

### `~` (regexp match)

**Works with:** strings, multi

**Matches if:** the field's actual value matches the provided regular expression. The provided value must be a regular expression represented as a JSON string:

* The regexp **must not** be surrounded by the slash characters (`/rexegp/`) that delimit regexps in many languages.
* Every backslash character **must** be escaped with an additional backslash. Thus, a sequence like `\d` would be represented as `\\d`, and a literal backslash (represented in a regexp as a double-backslash `\\`) would be represented as a quadruple-backslash (`\\\\`).

The following example would match if the `certname` field's actual value resembled something like `www03.example.com`:

    ["~", "certname", "www\\d+\\.example\\.com"]

> **Note:** Regular expression matching is performed by the database
> backend, so the available
> [regexp features](http://www.postgresql.org/docs/9.4/static/functions-matching.html#POSIX-SYNTAX-DETAILS)
> are determined by PostgreSQL.  For best results, use the simplest
> and most common features that can accomplish your task.

### `~>` (regexp array match)

**Works with:** paths

**Matches if:** the array matches using the regular expressions provided within in each element. Array indexes are coerced to strings.

The following example would match any network interface names starting with "eth":

    ["~>", "path", ["networking", "eth.*", "macaddress"]]

If you want to match any index for an array path element, you can use regular expressions to do this as the element acts like a string:

    ["~>", "path", ["array_fact", ".*"]]

### `null?` (is null)

**Works with:** fields that may be null

**Matches if:** the field's value is null (when second argument is `true`) or the field is **not** null, i.e. has a real value (when second argument is `false`).

The following example would return events that do not have an associated line number:

    ["null?", "line", true]

Similarly, the below query would return events that do have a specified line number:

    ["null?", "line", false]

## Boolean Operators

Every argument of these operators should be a **complete query string** in its own right. These operators are **transitive:** the order of their arguments does not matter.

### `and`

**Matches if:** **all** of its arguments would match. Accepts any number of query strings as its arguments.

### `or`

**Matches if:** **at least one** of its arguments would match. Accepts any number of query strings as its arguments.

### `not`

**Matches if:** its argument **would not** match. Accepts a **single** query string as its argument.

## Projection Operators

### `extract`

To reduce the keypairs returned for each result in the response, you can use **extract**:

    ["extract", ["hash", "certname", "transaction_uuid"]
      ["=", "certname", "foo.com"]]

When only extracting a single column, the [] are optional

    ["extract", "transaction_uuid"
      ["=", "certname", "foo.com"]]

When applying an aggregate function over a `group_by` clause, an extract
statement takes the form

    ["extract", [["function", "count"], "status"],
      ["=", "certname", "foo.com"],
      ["group_by", "status"]]

Extract can also be used with a standalone function application:

    ["extract", [["function", "count"]], ["~", "certname", ".\*.com"]]

or 

    ["extract", [["function", "count"]]]

### `function`

The **function** operator is used to call a function on the result of a
subquery. Supported functions are `count`, `avg`, `sum`, `min`, and `max`. The
function operator is applied within the first argument of an extract, as in
the examples above. The `avg`, `sum`, `min`, and `max` functions will ignore
non-numeric fact values.

### `group_by`

The **group_by** operator must be applied as the last argument of an extract,
and takes one or more column names as arguments. For instance, to get event
status counts for active certname by status, you can query the events endpoint
with:

    ["extract", [["function", "count"], "status", "certname"],
      ["=", ["node", "active"], true], ["group_by", "status", "certname"]]

To get the average uptime for your nodes,

    ["extract", [["function", "avg", "value"]], ["=", "name", "uptime_seconds"]]

## Context Operators

*Note:* Context setting support is new and experimental. Setting the context at
the top of the query is only supported on the [root] endpoint.

Setting context in a query allows you to choose the entity you are querying
on. This augments the endpoint support we have today, whereby the endpoint
decides the context. For example `/pdb/query/v4/nodes` sets the context of the query
to `nodes`.


### `from`

The `from` operator allows one to choose the [entity][entities] that you want to query and
provide an optional query clause for filtering those results. This operator can
be used at the top-level context of a query like so:

    ["from", "nodes", ["=", "certname", "myserver"]]

The `from` operator can also be used in a subquery for setting the context when
using the [`in` operator](#subquery-operators).

When querying a particular endpoint, such as `/pdb/query/v4/nodes` the endpoint provides
the context for the query. While querying the [root] endpoint requires specifying a
context explicitly.

## Subquery Operators

Subqueries allow you to correlate data from multiple sources or multiple
rows. For instance, a query such as "fetch the IP addresses of all nodes with
`Class[Apache]`" would have to use both facts and resources to return a list of facts.

There are two forms of subqueries, implicit and explicit, and both forms work the
same under the hood. The implicit form however, only requires you to specify the
related entity, while the explicit form requires you to be specify exactly how
data should be joined during the subquery.

### `subquery` (Implicit Subqueries)

*Note:* Implicit subqueries are a new experimental feature, be warned the functionality
may change as we provide improvements.

Implicit queries work like most operators, and simply require you to specify the
related entity and the query to use:

    ["subquery", "<ENTITY>", <SUBQUERY STATEMENT>]

The [`<ENTITY>`][entities] is the particular entity you are subquerying on, however not
all entities are implicitly relatable to all other entities, as not every relationship makes sense.
Consult the documentation for the chosen [`<ENTITY>`][entities] for details on what
implicit relationships are supported.

Internal to PuppetDB, we keep a mapping of how different entities relate to each
other, and so no other data beyond the entity is needed in this case. This is
different from explicit subqueries, for those you must specify yourself how
two entities are related, although functionally they can produce the same results.

#### Implicit Subquery Examples

A query string like the following on the [nodes][`nodes`] endpoint will return the list
of all nodes with the `Package[Tomcat]` resource in their catalog, and a certname starting
with `web1`:

    ["and",
      ["~", "certname", "^web1"],
      ["subquery", "resources",
        ["and",
          ["=", "type", "Package"],
          ["=", "title", "Tomcat"]]]]

If you wanted to display the entire `networking` fact, if the hosts interfaces uses a certain mac address
you can do the following on the [facts][`facts`] endpoint:

    ["and",
      ["=", "name", "networking"],
      ["subquery", "fact_contents",
        ["and",
          ["~>", "path", ["networking", ".*", "macaddresses", ".*"]],
          ["=", "value", "aa:bb:cc:dd:ee:00"]]]]

### Explicit Subqueries

While implicit subqueries can make your syntax succinct, not all relationships are
mapped internally. For these more advanced subqueries, you need to specify exactly the fields that
a subquery should join on. This is where an explicit subquery can be useful.

Explicit subqueries are unlike the other operators listed above. They always appear
together in one of the following forms:

    ["in", ["<FIELDS>"], ["extract", ["<FIELDS>"], <SUBQUERY STATEMENT>] ]

The second new methodology uses `from` to set the context, and now looks like this:

    ["in", ["<FIELDS>"], ["from", <ENTITY>, ["extract", ["<FIELDS>"], <SUBQUERY>] ] ]

*Note:* This new format is experimental and may change or be removed in the future.

That is:

* The `in` operator results in a complete query string. The `extract` operator and the subqueries do not.
* An `in` statement **must** contain one or more fields and an `extract` statement.
* An `extract` statement **must** contain one or more fields and a subquery statement.

These statements work together as follows (working "outward" and starting with the subquery):

* The subquery collects a group of PuppetDB objects (specifically, a group of [resources][], [facts][], [fact-contents][], or [nodes][]). Each of these objects has many **fields.**
* The `extract` statement collects the value of one or more **fields** across every object returned by the subquery.
* The `in` statement **matches** if its field values are present in the list returned by the `extract` statement.

Subquery | Extract | In
---------|---------|---
Every resource whose type is "Class" and title is "Apache." (Note that all resource objects have a `certname` field, among other fields.) | Every `certname` field from the results of the subquery. | Match if the `certname` field is present in the list from the `extract` statement.

The complete `in` statement described in the table above would match any object that shares a `certname` with a node that has `Class[Apache]`. This could be combined with a boolean operator to get a specific fact from every node that matches the `in` statement.

#### `in`

An `in` statement constitutes a full query string, which can be used alone or as an argument for a [boolean operator](#boolean-operators).

"In" statements are **non-transitive** and take two arguments:

* The first argument **must** consist of one or more **fields** for the endpoint or entity **being queried.**. This is a string or vector of strings.
* The second argument **must** be either:
** an **`extract` statement,** which acts as a list of fields to extract during the subquery for matching against the **fields** in the `in` clause.
** a **`from` statement,** which sets the context, and allows for an extract statement to be provided. *Note:* this syntax is new and experimental.

**Matches if:** the field values are included in the list of values created by the `extract` or `from` statement.

#### `from`

*Note:* the use of `from` in a subquery is experimental. It may change or be removed in the future.

This statement works like the top-level [`from`](#context-operators) operator, and expects an [entity][entities] as the first argument and an optional query in the second
argument, however when used within an `in` clause an `extract` statement is expected to choose the fields like so:

    ["in", "certname",
     ["from", "facts",
      ["extract", "certname",
       [<QUERY>]]]]

#### `extract`

"Extract" statements are **non-transitive** and take two arguments:

* The first argument **must** be a valid set of **fields** for the endpoint **being subqueried** (see second argument). This is a string or vector of strings.
* The second argument:
** **must** contain a **subquery statement**
** or when used with the new `from` operator, **may** contain an optional query.

As the second argument of an `in` statement, an `extract` statement acts as a list of possible values. This list is compiled by extracting the value of the requested field from every result of the subquery.

#### `select_<ENTITY>` Subquery Statements

A subquery statement **does not** constitute a full query string. It may only be used as the second argument of an `extract` statement.

Subquery statements are **non-transitive** and take two arguments:

* The first argument **must** be the **name** of one of the available subqueries (listed below).
* The second argument **must** be a **full query string** that makes sense for the endpoint being subqueried.

As the second argument of an `extract` statement, a subquery statement acts as a collection of PuppetDB objects. Each of the objects returned by the subquery has many fields; the `extract` statement takes the value of one field from each of those objects, and passes that list of values to the `in` statement that contains it.

Each subquery acts as a normal query to one of the PuppetDB endpoints. For info on constructing useful queries, see the docs page for the endpoint matching the subquery:

* [`select_catalogs`][catalogs]
* [`select_edges`][edges]
* [`select_environments`][environments]
* [`select_events`][events]
* [`select_facts`][facts]
* [`select_fact_contents`][fact-contents]
* [`select_fact_paths`][fact-paths]
* [`select_nodes`][nodes]
* [`select_reports`][reports]
* [`select_resources`][resources]

#### Explicit Subquery Examples

This query string queries the `/facts` endpoint for the IP address of
all nodes with `Class[Apache]`:

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname",
          ["select_resources",
            ["and",
              ["=", "type", "Class"],
              ["=", "title", "Apache"]]]]]]

This query string queries the `/nodes` endpoint for all nodes with `Class[Apache]`:

    ["in", "certname",
      ["extract", "certname",
        ["select_resources",
          ["and",
            ["=", "type", "Class"],
            ["=", "title", "Apache"]]]]]]

This query string queries the `/facts` endpoint for the IP address of
all Debian nodes.

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname",
          ["select_facts",
            ["and",
              ["=", "name", "operatingsystem"],
              ["=", "value", "Debian"]]]]]]

This query string queries the `/facts` endpoint for uptime_hours of all nodes with
facts_environment `production`:

    ["and",
      ["=", "name", "uptime_hours"],
      ["in", "certname",
        ["extract", "certname",
          ["select_nodes",
            ["=", "facts_environment", "production"]]]]]

To find node information for a host that has a macaddress of `aa:bb:cc:dd:ee:00` as
its first macaddress on the interface `eth0`, you could use this query on '/nodes':

    ["in", "certname",
      ["extract", "certname",
        ["select_fact_contents",
          ["and",
            ["=", "path", ["networking", "eth0", "macaddresses", 0]],
            ["=", "value", "aa:bb:cc:dd:ee:00"]]]]]

To exhibit a subquery using multiple fields, you could use the following
on '/facts' to list all top-level facts containing fact contents with paths
starting with "up" and value less than 100:

    ["in", ["certname", "name"],
      ["extract", ["certname", "name"],
        ["select_fact_contents",
          ["and",
            ["~>", "path", ["up.*"]],
            ["<", "value", 100]]]]]

To use a subquery to restrict a query to active nodes only, you can use this
query:

    ["in", "certname",
      ["extract", "certname",
        ["select_nodes",
          ["and", ["null?", "deactivated", true],
                  ["null?", "expired", true]]]]]

For the previous query, we also allow the shorthand

    ["=", ["node", "active"], true]

and its counterpart with `false`.

#### Explicit Subquery Examples (New Experimental Format)

*Note:* The new syntax is experimental and may change or be removed.

The new format re-orders the precedence of the context selection and the extraction
so the format has changed. For example, a query such as this:

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname",
          ["select_resources",
            ["and",
              ["=", "type", "Class"],
              ["=", "title", "Apache"]]]]]]

Will now look like this:

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["from", "resources",
          ["extract", "certname",
            ["and",
              ["=", "type", "Class"],
              ["=", "title", "Apache"]]]]]]

Executing this query on the `/facts` endpoint would filter for `uptime_hours` for all nodes with
`facts_environment` set to `production`:

    ["and",
      ["=", "name", "uptime_hours"],
      ["in", "certname",
        ["from", "nodes",
          ["extract", "certname",
            ["=", "facts_environment", "production"]]]]]

To find node information for a host that has a macaddress of `aa:bb:cc:dd:ee:00` as
its first macaddress on the interface `eth0`, you could use this query on `/nodes`:

    ["in", "certname",
      ["from", "fact_contents",
        ["extract", "certname",
          ["and",
            ["=", "path", ["networking", "eth0", "macaddresses", 0]],
            ["=", "value", "aa:bb:cc:dd:ee:00"]]]]]
