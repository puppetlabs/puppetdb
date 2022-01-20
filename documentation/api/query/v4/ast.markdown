---
title: "AST query language"
layout: default
canonical: "/puppetdb/latest/api/query/v4/ast.html"
---

# AST query language

[root]: ./overview.markdown
[catalogs]: ./catalogs.markdown
[contact]: ../../../pdb_support_guide.markdown#contact-us
[edges]: ./edges.markdown
[environments]: ./environments.markdown
[events]: ./events.markdown
[facts]: ./facts.markdown
[fact-contents]: ./fact-contents.markdown
[fact-paths]: ./fact-paths.markdown
[inventory]: ./inventory.markdown
[nodes]: ./nodes.markdown
[pg-regex]: https://www.postgresql.org/docs/11/functions-matching.html#FUNCTIONS-POSIX-REGEXP
[producers]: ./producers.markdown
[query]: query.markdown
[reports]: ./reports.markdown
[resources]: ./resources.markdown
[entities]: ./entities.markdown
[pql]: ./pql.markdown
[urlencode]: http://en.wikipedia.org/wiki/Percent-encoding
[to-char]: http://www.postgresql.org/docs/11/static/functions-formatting.html

## Summary

The AST (abstract syntax tree) query language for PuppetDB is a language that presents
itself as a raw AST format. It can be used to provide complex querying via REST on each of
PuppetDB's query [endpoints][entities].

This document outlines the operator syntax for this query language.

An easier to use alternative to this query language is the [Puppet query language][pql], which is
largely based on the AST query language.

## Query strings

An AST query string passed to the `query` URL parameter of a REST endpoint must be a [URL-encoded][urlencode]
JSON array, which may contain scalar data types (usually strings) and additional arrays, that describes a
complex _comparison operation_ in _prefix notation_ with an **operator** first and its **arguments** following.

That is, before being URL-encoded, all AST query strings follow this form:

    [ "<OPERATOR>", "<ARGUMENT>", (..."<ARGUMENT>"...) ]

Different operators may take different numbers (and types) of arguments.

## Binary operators

Each of these operators accepts two arguments: a **field** and a
**value.** These operators are **non-transitive,** which means that their syntax must always be:

    ["<OPERATOR>", "<FIELD>", "<VALUE>"]

The available fields for each endpoint are listed in that endpoint's documentation.

### `=` (equality)

**Works with:** strings, numbers, timestamps, Booleans, arrays, multi, path.

**Matches if:** the field's actual value is exactly the same as the provided value.

* Most fields are strings.
* Some fields are Booleans.
* Arrays match if any **one** of their elements matches.
* Path matches are a special kind of array, and must be exactly matched with this operator.

### `>` (greater than)

**Works with:** numbers, timestamps, multi.

**Matches if:** the field is greater than the provided value.

### `<` (less than)

**Works with:** numbers, timestamps, multi.

**Matches if:** the field is less than the provided value.

### `>=` (greater than or equal to)

**Works with:** numbers, timestamps, multi.

**Matches if:** the field is greater than or equal to the provided value.

### `<=` (less than or equal to)

**Works with:** numbers, timestamps, multi.

**Matches if:** the field is less than or equal to the provided value.

### `~` (regexp match)

**Works with:** strings, multi.

**Matches if:** the field's actual value matches the provided regular expression. The provided value must be a regular expression represented as a JSON string:

* The regexp **must not** be surrounded by the slash characters (`/rexegp/`) that delimit regexps in many languages.
* Every backslash character **must** be escaped with an additional backslash. Thus, a sequence like `\d` would be represented as `\\d`, and a literal backslash (represented in a regexp as a double-backslash `\\`) would be represented as a quadruple-backslash (`\\\\`).

The following example would match if the `certname` field's actual value resembled something like `www03.example.com`:

    ["~", "certname", "www\\d+\\.example\\.com"]

> **Note:** Regular expression matching is performed by the database
> backend, so the available [regexp features](#pg-regex) are
> determined by PostgreSQL. For best results, use the simplest and
> most common features that can accomplish your task.

### `~>` (regexp array match)

**Works with:** paths.

**Matches if:** each array element, which must be a PostgreSQL regular
expression or an integer, matches each element of the path.  Integers
only match array indexes, regular expressions that only contain
integer digits like `"123"` do not match array indexes, and all other
regular expressions, including something like `"[12]3"`, match both
array indexes and map keys.

The following example would match any network interface names starting with "eth":

    ["~>", "path", ["networking", "eth.*", "macaddress"]]

If you want to match any index for an array path element, you can use regular expressions, as the element acts like a string:

    ["~>", "path", [<array_fact>, ".*"]]

> Limitations: with the current implementation an anchored expression
> like `"^sda.*"` may never match an array element.  Currently
> those expressions will match for queries against the
> [fact-contents][fact-contents], but for now, that should not be
> considered reliable across PuppetDB upgrades.

### `null?` (is null)

**Works with:** fields that may be null.

**Matches if:** the field's value is null (when second argument is `true`) or the field is **not** null, or has a real value (when second argument is `false`).

The following example would return events that do not have an associated line number:

    ["null?", "line", true]

Similarly, the below query would return events that do have a specified line number:

    ["null?", "line", false]

## Boolean operators

Every argument of these operators should be a **complete query string** in its own right. These operators are **transitive:** the order of their arguments does not matter.

### `and`

**Matches if:** **all** of its arguments would match. Accepts any number of query strings as its arguments.

### `or`

**Matches if:** **at least one** of its arguments would match. Accepts any number of query strings as its arguments.

### `not`

**Matches if:** its argument **would not** match. Accepts a **single** query string as its argument.

## Projection operators

### `extract`

To reduce the keypairs returned for each result in the response, you can use **extract**:

    ["extract", ["hash", "certname", "transaction_uuid"],
      ["=", "certname", "foo.com"]]

When only extracting a single column, the `[]` are optional:

    ["extract", "transaction_uuid",
      ["=", "certname", "foo.com"]]

When applying an aggregate function over a `group_by` clause, an extract
statement takes the form:

    ["extract", [["function", "count"], "status"],
      ["=", "certname", "foo.com"],
      ["group_by", "status"]]

Extract can also be used with a standalone function application:

    ["extract", [["function", "count"]], ["~", "certname", ".\*.com"]]

or

    ["extract", [["function", "count"]]]

#### Extracting a subtree

The JSON fields that support dot notation for hash descendance also support
dot notation for extracting a subtree. See the Dot notation section below
for more information.

    ["extract", ["facts.os.family"]]

### `function`

The **function** operator is used to call a function on the result of a
subquery. Supported functions are described below.

#### `avg`, `sum`, `min`, `max`
These functions operate on any numeric column and they take the column
name as an argument, as in the examples above.

#### `count`
The `count` function can be used with or without a column. When no column is
supplied, it will return the number of results in the associated subquery.
Using the function with a column will return the number of results where the
specified column is not null.

#### `to_string`
The `to_string` function operates on timestamps and integers, allowing them to
be formatted in a user-defined manner before being returned from puppetdb.
Available  formats are the same as those documented for [PostgreSQL's `to_char`
function][to-char]. For instance, to get the full lower case month name of the
`producer_timestamp`,  you can query the reports endpoint with:

```
["extract", [["function", "to_string", "producer_timestamp", "month"]]]
```

To get the last 2 digits of the year a report was submitted  from the Puppet Server:

```
["extract", [["function", "to_string", "producer_timestamp", "YY"]]]]
```

To get the uptime_seconds fact's value as a string, the following query can be used on
facts or fact-contents endpoint:

```
["extract", [["function", "to_string", "value", "999999999"]], ["=","name", "uptime_seconds"]]
```

Please note that in order for `to_string` function to work with integer values, a mask
must be provided. For more information about masks and how to provide them, please read
the documentation for [PostgreSQL's `to_char`function][to-char].

### `group_by`

The **group_by** operator must be applied as the last argument of an extract,
and takes one or more column names as arguments. For instance, to get event
status counts for active certname by status, you can query the events endpoint
with:

    ["extract", [["function", "count"], "status", "certname"],
      ["group_by", "status", "certname"]]

To get the average uptime for your nodes:

    ["extract", [["function", "avg", "value"]], ["=", "name", "uptime_seconds"]]

## Dot notation

*Note*: Dot notation for hash descendence is under development. Currently it has
full support on the `facts` and `trusted` response keys of the `inventory`
endpoint, and partial support on the `parameters` column of the resources
endpoint. It may be expanded to other endpoints in the future based on demand.

Certain types of JSON data returned by PuppetDB can be queried in a structured
way using `dot notation`. The rules for dot notation are:
* Hash descendence is represented by a period-separated sequence of key names
* Array indexing (`inventory` only) is represented with brackets (`[]`) on the
end of a key.
* Regular expression matching ([`inventory`](#inventory) only) is
  represented with the `match` operator, but note that [`match` in its
  current form has been deprecated](#dotted-field-syntax), and is
  likely to be removed or altered in a backward-incompatible way in a
  future release.

For example, given the inventory response


    {
        "certname" : "mbp.local",
        "timestamp" : "2016-07-11T20:02:33.190Z",
        "environment" : "production",
        "facts" : {
            "kernel" : "Darwin",
            "operatingsystem" : "Darwin",
            "macaddress_p2p0" : "0e:15:c2:d6:f8:4e",
            "system_uptime" : {
                "days" : 0,
                "hours" : 1,
                "uptime" : "1:52 hours",
                "seconds" : 6733
            },
            "macaddress_awdl0" : "6e:31:ef:e6:36:54",
            "processors": {
                "models": [
                    "Intel(R) Core(TM) i7-4790 CPU @ 3.60GHz",
                    "Intel(R) Core(TM) i7-4790 CPU @ 3.60GHz",
                    "Intel(R) Core(TM) i7-4790 CPU @ 3.60GHz",
                    "Intel(R) Core(TM) i7-4790 CPU @ 3.60GHz"],
                "count": 4,
                "physicalcount": 1
            },
            ...
        },
        "trusted" : {
            "domain" : "local",
            "certname" : "mbp.local",
            "hostname" : "mbp",
            "extensions" : { },
            "authenticated" : "remote"
        }
    }

valid queries would include

* `["=", "facts.kernel", "Darwin"]`

* `["=", "facts.system_uptime.days", 0]`

* `[">", "facts.system_uptime.hours", 0]`

* `["~", "facts.processors.models[0]", "Intel.*"]`

### Dotted Projections

Dot notation is also supported for extracting a subtree of JSON fields.
For example you can query the inventory endpoint with

    ["extract", ["trusted.certname", "facts.system_uptime"]]

To get a response with only the elements you've asked for

    {
        "trusted.certname": "mbp.local",
        "facts.system_uptime.uptime": {
            "days" : 0,
            "hours" : 1,
            "uptime" : "1:52 hours",
            "seconds" : 6733
        }
    }

### Dotted field syntax

A dotted field, which repseents a path into a JSON tree is made up of
components separated by dots (`.`), for example `facts.kernel`. Any
path component can be double-quoted, for example `facts."x.y".z`, in
which case the name will include all of the characters after the first
double-quote, and before the next double-quote that is itself not
preceded by a backslash and is followed by either a dot, or the end of
the field. So the previous example `facts."x.y".z` represents the
three components, `facts`, `x.y`, and `z`. In AST queries, any
double-quotes will have to be properly JSON escaped. So in an
`extract` the path `x."y.z"` becomes `[extract "x.\"y.z\"", ...]`.

There is currently no way to represent a field component that contains
a dot and ends in a backslash. For example, a fact named `x.y\` must be
quoted, given the dot, but as just mentioned, quoted fields cannot end
in a backslash.

> **Note:** the `match()` operator described here is deprecated and is
> likely to be retired or altered in a backward-incompatible way in a
> future release.

In some cases (e.g. [inventory endpoint](#inventory)) dotted fields
can also contain a `match()` component, for example
`facts.partitions.match("sd.*")` The match pattern must be a
[PostgreSQL regular expression](#pg-regex), and must begin with
`match`, open paren, double quote, and it will end at the next double
quote, close paren that is not preceded by a backslash and is followed
by either a dot, or the end of the field.  The regex then, has
essentially the same syntax as a double quoted field. And similarly,
there is currently no way to specify a match regular expression that
ends in a backslash.

With the current implementation, the `match()` component's behavior is
not well defined, likley to be surprising, and likely to change in the
future, so we recommend avoiding it for now, but please do
[contact us](#contact-us) if you are currently using it, or would like
to use an operator with better semantics, so we can incorporate that
information into future plans.

As an example of the potentially surprising behavior, the appearance
of any `match()` operator in a dotted field can cause the entire
field, not just the `match()` segment, to be handled as a regular
expression in an awkward manner.

## Context operators

*Note:* Setting the context at the top of the query is only supported on the
[root][root] endpoint.

Setting context in a query allows you to choose the entity you are querying
on. This augments the endpoint support we have today, whereby the endpoint
decides the context. For example, `/pdb/query/v4/nodes` sets the context of the query
to `nodes`.

### `from`

The `from` operator allows you to choose the [entity][entities] that you want to query and
provide optional query and paging clauses to filter those results. This operator can
be used at the top-level context of a query:

    ["from", "nodes", ["=", "certname", "myserver"]]

The `from` operator can also be used in a subquery for setting the context when
using the [`in` operator](#subquery-operators).

When querying a particular endpoint, such as `/pdb/query/v4/nodes`, the endpoint provides
the context for the query. Querying the [root] endpoint requires specifying a
context explicitly.

## Paging operators (`limit`, `offset`, `order_by`)

PuppetDB allows specification of paging clauses within a "from" clause in a
query or subquery. The `limit` and `offset` operators both accept an
integer-valued argument, and `order_by` accepts a vector of either column names
or vector pairs containing a column name and an ordering of "asc" or "desc".
For example,

    ["limit", 1]

    ["offset", 1]

    ["order_by", ["certname"]]

    ["order_by", ["certname", ["producer_timestamp", "desc"]]]

When no ordering is explicitly specified, as in the case of "certname" in the
example above, ascending order is assumed. Here are a few examples of queries
using paging operators:

Return the most recent ten reports for a certname:

    ["from", "reports",
      ["=", "certname", "myserver"],
      ["order_by", [["producer_timestamp", "desc"]]],
      ["limit", 10]]

Return the next page of ten reports:

    ["from", "reports",
      ["=", "certname", "myserver"],
      ["order_by", [["receive_time", "desc"]]],
      ["limit", 10],
      ["offset", 10]]

Return the most recent ten reports for any certname:

    ["from", "reports",
      ["order_by", [["producer_timestamp", "desc"]]],
      ["limit", 10]]

Return the nodes represented in the ten most recent reports:

    ["from", "nodes",
      ["in", "certname",
        ["from", "reports",
          ["extract", "certname"],
          ["limit", 10],
          ["order_by", [["certname", "desc"]]]]]]

The order in which paging operators are supplied does not matter.

## Subquery operators

Subqueries allow you to correlate data from multiple sources or multiple
rows. For instance, a query such as "fetch the IP addresses of all nodes with
`Class[Apache]`" would have to use both facts and resources to return a list of facts.

There are two forms of subqueries, implicit and explicit, and both forms work the
same under the hood. Note, however, that the implicit form only requires you to specify the related entity, while the explicit form requires you to be specify exactly how
data should be joined during the subquery.

### `subquery` (implicit subqueries)

Implicit queries work like most operators, and simply require you to specify the
related entity and the query to use:

    ["subquery", "<ENTITY>", <SUBQUERY STATEMENT>]

The [`<ENTITY>`][entities] is the particular entity you are subquerying on, however not
all entities are implicitly relatable to all other entities, as not every relationship makes sense.
Consult the documentation for the chosen [`<ENTITY>`][entities] for details on what
implicit relationships are supported.

In PuppetDB, we keep a map of how different entities relate to each
other, and therefore no data beyond the entity is needed in this case. This is
different from explicit subqueries, where you must specify how
two entities are related. Implicit subqueries can be used to join any two
entities that have a `certname` field. Additional relationships are described
in the endpoint-specific documentation as applicable.

#### Implicit subquery examples

A query string like the following on the [`nodes`][nodes] endpoint will return the list
of all nodes with the `Package[Tomcat]` resource in their catalog, and a certname starting
with `web1`:

    ["and",
      ["~", "certname", "^web1"],
      ["subquery", "resources",
        ["and",
          ["=", "type", "Package"],
          ["=", "title", "Tomcat"]]]]

If you want to display the entire `networking` fact, and the host's interface uses a certain mac address,
you can do the following on the [`facts`][facts] endpoint:

    ["and",
      ["=", "name", "networking"],
      ["subquery", "fact_contents",
        ["and",
          ["~>", "path", ["networking", ".*", "macaddress", ".*"]],
          ["=", "value", "aa:bb:cc:dd:ee:00"]]]]

### Explicit subqueries

While implicit subqueries can make your syntax succinct, not all relationships are
mapped internally. For these more advanced subqueries, you need to specify exactly the fields that
a subquery should join on. This is where an explicit subquery can be useful.

Explicit subqueries are unlike the other operators listed above. They always appear
together in one of the following forms:

    ["in", ["<FIELDS>"], ["extract", ["<FIELDS>"], <SUBQUERY STATEMENT>] ]

The second new methodology uses `from` to set the context, and now looks like this:

    ["in", ["<FIELDS>"], ["from", <ENTITY>, ["extract", ["<FIELDS>"], <SUBQUERY>] ] ]

That is:

* The `in` operator results in a complete query string. The `extract` operator and the subqueries do not.
* An `in` statement **must** contain one or more fields and an `extract` statement.
* An `extract` statement **must** contain one or more fields and a subquery statement.

These statements work together as follows (working "outward" and starting with the subquery):

* The subquery collects a group of PuppetDB objects (specifically, a group of [resources][resources], [facts][facts], [fact-contents][fact-contents], or [nodes][nodes]). Each of these objects has many **fields.**
* The `extract` statement collects the value of one or more **fields** across every object returned by the subquery.
* The `in` statement **matches** if its field values are present in the list returned by the `extract` statement.

Subquery | Extract | In
---------|---------|---
Every resource whose type is "Class" and title is "Apache." (Note that all resource objects have a `certname` field, among other fields.) | Every `certname` field from the results of the subquery. | Match if the `certname` field is present in the list from the `extract` statement.

The complete `in` statement described in the table above would match any object that shares a `certname` with a node that has `Class[Apache]`. This could be combined with a Boolean operator to get a specific fact from every node that matches the `in` statement.

#### `in`

An `in` statement constitutes a full query string, which can be used alone or as an argument for a [Boolean operator](#boolean-operators).

"In" statements are **non-transitive** and take two arguments:

* The first argument **must** consist of one or more **fields** for the endpoint
  or entity **being queried.**. This is a string or vector of strings.
* The second argument **must** be either:
  * an **`extract` statement,** which acts as a list of fields to extract during
   the subquery for matching against the **fields** in the `in` clause.
  * a **`from` statement,** which sets the context, and allows for an extract
   statement to be provided.
  * an **`array` statement,** which acts as a list of values to match against the
   **field** in the `in` clause.

**Matches if:** the field values are included in the list of values created by the `extract` or `from` statement.

##### `array`

An `in` statement also accepts an `array` statement as a second argument.

"Array" statements take a single vector argument of values to match the first
argument of `in` against.

The following query filters for the nodes, `foo.local`, `bar.local`, and
`baz.local`:

    ["in", "certname",
     ["array",
      ["foo.local",
       "bar.local",
       "baz.local"]]]

which is equivalent to the following query:

    ["or",
     ["=","certname","foo.local"],
     ["=","certname","bar.local"],
     ["=","certname","baz.local"]]

The `in`-`array` operators support much of the same syntax as the `=` operator.
For example, the following query on the `/nodes` endpoint is valid:

    ["in", ["fact", "uptime_seconds"],
     ["array",
      [20000.0,
       150.0,
       30000.0]]]

#### `from`

This statement works like the top-level [`from`](#context-operators) operator,
and expects an [entity][entities] as the first argument and an optional query in
the second argument. However, when used within an `in` clause, an `extract`
statement is expected to choose the fields:

    ["in", "certname",
     ["from", "facts",
      ["extract", "certname",
       [<QUERY>]]]]

#### `extract`

"Extract" statements are **non-transitive** and take two arguments:

* The first argument **must** be a valid set of **fields** for the endpoint
  **being subqueried** (see second argument). This is a string or vector of
  strings.
* The second argument:
** **must** contain a **subquery statement**
** or when used with the new `from` operator, **may** contain an optional query.

As the second argument of an `in` statement, an `extract` statement acts as a
list of possible values. This list is compiled by extracting the value of the
requested field from every result of the subquery.

#### `select_<ENTITY>` subquery statements

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
* [`select_producers`][producers]
* [`select_reports`][reports]
* [`select_resources`][resources]

#### Explicit subquery examples

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
            ["=", "title", "Apache"]]]]]

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
            ["=", "path", ["networking", "eth0", "macaddress", 0]],
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

Queries are restricted to active nodes by default; to make this explicit, the
special "node_state" field may be queried using the values "active", "inactive",
or "any". For example, to list all catalogs from inactive nodes, use this on the
/catalogs endpoint:

    ["=", "node_state", "inactive"] 

This expands internally into comparisons against each node's deactivation and
expiration time; a node is consider inactive if either field is set.

#### Explicit subquery examples (with the `from` operator)

Additions to the query language in support of PQL introduced new ways to
express subqueries using the `from` operator. For example, a query such as this:

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname",
          ["select_resources",
            ["and",
              ["=", "type", "Class"],
              ["=", "title", "Apache"]]]]]]

will now look like this:

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
            ["=", "path", ["networking", "eth0", "macaddress", 0]],
            ["=", "value", "aa:bb:cc:dd:ee:00"]]]]]
