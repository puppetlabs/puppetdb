---
title: "PuppetDB 3.2 » API » v4 » Puppet query language (PQL)"
layout: default
canonical: "/puppetdb/latest/api/query/v4/pql.html"
---

[entities]: ./entities.html
[subquery]: #subqueries
[ast]: ./ast.html

> **Experimental Feature**: This featureset is experimental, and it may be altered or removed in
> a future release.

Puppet Query Language (PQL) is a query language designed with PuppetDB and Puppet data in mind. It
provides a string based query language as an alternative to the [AST query language][ast] PuppetDB has
always supported.

## Executing PQL queries using curl

PQL queries are always made against the index endpoint, using either a `GET` or a `POST`. The query
string is provided as a value provided to the `query` parameter.

The following example shows using `curl` to execute a `GET` request:

    curl -X GET http://localhost:8080/pdb/query/v4 \
      --data-urlencode 'query=nodes { certname = "macbook-pro.local" }'

And the following example shows how to execute a `POST` request:

    curl -X POST http://localhost:8080/pdb/query/v4 \
      -H 'Content-Type: application/json'
      -d '{"query":"nodes { certname = \"macbook-pro.local\" }"}'

## Query Structure

A PQL query has the following structure:

    entity [projection] { filter modifiers }

Which is broken up into the following parts:

* `entity`: Required. The entity context that this query executes on.
* `projection`: Optional. Restricts the output to a selection of fields or
  function results.
* `filter`: Optional. The filter to match on for this `entity`.
* `modifiers`: Optional. Contains modifiers for the query.

As an example, if you wanted to query for `nodes` related data, but only see the `certname` field
for each node, with a regex filter across certname:

    nodes[certname] { certname ~ "^web" }

In this case, this would return only the certname field of nodes starting with `web`.

## Entities

The entity or context of a query (or subquery) defines what results you will get returned when
performing a query, and provides the main context for any projections or filters in the query.
There are many entities, for a full list see the [entities] documentation.

For PQL queries, the entity context is the minimal amount of information one must provide, as it
defines the results returned. For example, if you wanted to see all node information, you could
provide a query as follows:

    nodes {}

And it would be enough to return all node data, without filtering or pagination.

The entity context can also be used within a subquery, see the [subquery] section for more details.

## Projection

The projection part of a query provides a mechanism to choose a subset of fields that are returned
or to modify the way those fields are displayed with the usage of functions

The projection lives within the brackets of a query:

    nodes[projection] {}

Projections are a comma-separated list of fields and functions. When a field is
surrounded in angle brackets (`<`, `>`), the results will be grouped by that
field. When angle brackets are used without a function, the result will be a
list of distinct combinations of the bracketed field(s). When a function is
supplied, the function will be applied to the results of the filter expression
grouped by the specified fields.

For example,

    facts[<name>] {}

will return a list of all unique fact names stored in PuppetDB. Alternatively,

    facts[<name>, count()] {}

will return a list of all unique fact names and the number of times they occur.
In SQL terms, the latter is conceptually equivalent to

    select name, count(*) from facts group by name;

If you provide a projection with no fields or functions, then all fields will be displayed. So
the following two examples are equivalent:

    facts[] {}
    facts {}

### Fields

Entity field selection is an optional capability to ensure that only certain fields are returned in
a response.

For a basic query, if you don't provide any entity fields all data gets returned. However this can
be be inefficient for both the database and the network to do this. By providing an entity field
however, you can reduce what fields are returned.

The entity field section of a query, can contain a number of field names separated by a comma:

    entity[field1, field2, field3] {}

As an example, to return only `certname` for all nodes:

    nodes[certname] {}

Or to return both the `name` and `value` of all facts:

    facts[name, value] {}

Only fields that are available for the entity type can be returned by PQL today.

### Functions

PQL supports the usage of some functions in the projection, but only aggregate functions are
available today.

Aggregate functions perform a calculation on a set of values and return a single value. Functions
themselves are provided in the projection much like fields are:

    entity[function(argument)] {}

As an example, to query how many objects exist that start with a certname of `web` you could use
the following filter and function combination:

    nodes[count()] { certname ~ "web.*" }

There are only a few functions that are supported today by PQL, see the list below for more detail.

#### `count()`

Returns the number of objects returned by the query, instead of returning the actual results.

#### `avg(field)`

Returns the average value for the values held in the `<field>` argument.

#### `sum(field)`

Returns the sum of values for the values held in the `<field>` argument.

#### `min(field)`

Returns the minimum value for all the values held in the `<field>` argument.

#### `max(field)`

Returns the maximum value for all the values held in the `<field>` argument.

## Filter

Filtering a query allows you to reduce the number of responses from PuppetDB based on a filter.

In a basic query, a filter is optional, and is provided in the `filter` area as a set of boolean
and conditional operators that make up a filter. For example:

    entity { field1 = 'mystring' and field2 < 3 }

You can also modify boolean operator precedence by using parentheses:

    entity { !(field1 = 'mystring' and field2 < 3) or field3 = 'mars' }

All filters are made up of a series of conditions, combined together with boolean operators.

### Conditional operators

Conditions provide the basic tests that are preformed to decide if a filter is true or not. The
following operators are available within PQL:

#### Equality: `=`

Matches the field value, with the literal value provided.

    nodes { certname = "foo" }

#### Numeric comparison: `>=`, `<=`, `>`, `<`

These operators allow for numeric comparison, and will return true if the field, the value and the
operator combination are true:

* `>` - greater than
* `>=` - greater than or equal to
* `<` - less than
* `<=` - less than or equal to

Some examples of their usage:

    facts { value >= 4 }
    facts { value < 4 }
    facts { value <= 4 }
    facts { value < 4 }

The operator will only work on numbers however, any other types will return errors.

#### Regexp: `~`

For strings you can match using a regular expression pattern, by using the `~` operator and a valid
regular expression:

    nodes { certname ~ "foo.*" }

* The regexp **must not** be surrounded by the slash characters (`/rexegp/`) that delimit regexps in many languages.
* Every backslash character **must** be escaped with an additional backslash. Thus, a sequence like `\d` would be represented as `\\d`, and a literal backslash (represented in a regexp as a double-backslash `\\`) would be represented as a quadruple-backslash (`\\\\`).

> **Note:** Regular expression matching is performed by the database
> backend, so the available
> [regexp features](http://www.postgresql.org/docs/9.4/static/functions-matching.html#POSIX-SYNTAX-DETAILS)
> are determined by PostgreSQL.  For best results, use the simplest
> and most common features that can accomplish your task.

#### Array Match: `in`

The `in` operator matches a field, or set of fields against either an array or a subquery.

The `in` operator can be used in two ways. The simplest way is to see if a field, contains one of the values
provided in a list of literal values:

    nodes { certname in ["foo", "bar", "baz"] }

The operator can also be used to ensure the values of a field match the fields returned, from a subquery,
which has the form of a nested PQL query within the filter:

    nodes { certname in facts[certname] { value = "foo" } }

With the subquery form, we can even match on multiple fields, as long as the fields being matched, and the
subqueries projection fields match:

    facts { [certname, name] in fact_contents[certname, name] { value ~ "a" } }

#### Null detection: `is null`, `is not null`

Null values in PuppetDB are treated differently to other values. So to detect if a field is a null, instead of
doing an exact match comparison, you must use either the `is null` or `is not null` operator.

To test if a field contains a `null`:

    nodes { deactivated is null }

Or conversely, to test if a field does not contain a `null` value:

    nodes { deactivated is not null }

#### Regexp array match: `~>`

The array matches using the regular expressions provided within in each element. Array indexes are coerced
to strings.

For example. the following query would query the path element, matching any ethernet mac address:

    fact_contents { path ~> ["networking","interfaces",".*","mac"] }

The following example will match against the size of any disk on the system:

    fact_contents { path ~> ["disks",".*","size"] }

### Boolean operators

Boolean operators are used within PQL filters to join conditons together to perform the filtering
test within PuppetDB.

There are only 3 boolean operators today, in order of natural precedence:

* `!` - performs a logical `not` or negation
* `and` - performs a logical `and` or conjuction
* `or` - performs a logical `or` or disjunction

### Grouping

By default PQL binary operators are evaluated using the following natural order of precedence: `!`, `and`, and `or`.
To override this precedence, you can group conditions together explicitly using parenetheses:

    facts { name ~ "^operating" and ( name ~ "system" or value = "FlowerOS" ) }

In this case the `or` gets evaluated before the `and` despite the natural order of precedence.

You can nest as many levels of grouping as required.

### Literal types

Each field for an entity supports matching using a conditional against a provided literal value.

#### Strings

PQL supports legal UTF-8 strings as literals for comparison.

There are two types of literal strings:

* single quoted - no escaping, just straight text
* double quoted - supports escape characters

Double quoted strings follow the same rules as JSON strings, and can accept escape characters:

* `\n` - newline
* `\r` - carriage return
* `\b` - backspace
* `\f` - formfeed
* `\t` - tab
* `\uXXXX` - unicode character

For example to match on a string with a newline in it:

    facts { value = "first line\nsecondline" }

However if you wanted to match the literal `\n` set of characters, and not have it translated to a newline, you could do:

    facts { value = 'first line\nstill on first line' }

#### Booleans

Booleans are represented by using the bare words: `true` or `false`.

#### Numbers

Numbers in PQL can be either integers or reals:

    4
    4.1
    -10245
    -124.012

For real numbers, scientific notation is expressed using E notation:

    4.1E123
    -3.2E-123

E notation follows the same rules as JSON, but currently is only
accepted for real numbers, not integers.

#### Lists

Lists are groups of other literal values, and are expressed using
brackets with elements separated by commas:

    ['a', 'b', 'c', 'd']

Currently lists are only supported with the `in` operator.

### Implicit Subqueries

Implicit subqueries works very much the same way as the `in` operator provides, however the relationship
between some entities is clear. When an implicit relationship exists between two entity types, you can
avoid the overhead of having to provide the join columns like with the `in` operator by using implicit
subqueries instead.

Basically, an implicit subquery looks like a query, embedded within the filter of a PQL query:

    nodes {
      facts { name = "operatingsystem" and value = "Debian" }
    }

In this example, while the query context is set to `nodes`, we will only return `nodes` that have
a fact `name` of `operatingsystem` and `value` of `Debian` (so only Debian nodes basically).

As mentioned this often allows you to avoid having to know which fields are required like with an `in`
operator, but only some relationships are well defined. See the [entities] documentation for each
entity to learn which implicit subqueries are provided automatically.

Also, implicit subqueries are like any other conditional operator, so therefore can be combined with
basic filters. The following query combines the fact subquery as before, included with a `certname`
match on the node itself:

    nodes {
      facts { name = "operatingsystem" and value = "Debian" } and
      certname ~ "^web"
    }

They can even be combined with other implict subqueries, to provide more complex matching capabilities. This
query combined everything before, but with a `resource` subquery for `Package[tomcat]`:

    nodes {
      facts { name = "operatingsystem" and value = "Debian" } and
      resources { type = "Package" and title = "tomcat" } and
      certname ~ "^web"
    }

## Group By

As explained above in the `projections` section, a `group by` clause is
effected by surrounding a projection in angle brackets. When a function is
supplied in the projection, however, angle brackets are assumed on the other
fields. In other words,

    facts[<name>, count(value)] {certname ~ 'web.*'}

and

    facts[name, count(value)] {certname ~ 'web.*'}

are equivalent, whereas

    facts[<name>]{}

and
   
    facts[name]{}

are not, since the first will only return a list of distinct fact names.

## Paging
PQL supports restriction of the result set via the SQL-like paging clauses
`limit`, `offset`, and `order by`.

### `limit` and `offset`

Limit and offset clauses are supplied with integer arguments and may appear in
any order within the braced section of a PQL query. Offset always takes
precedence over limit:

    reports {certname = "foo.com" limit 10}

    reports {certname = "foo.com" limit 10 offset 10}

    reports {certname = "foo.com" offset 10 limit 10}

Note that since there is no default ordering for results returned by PuppetDB,
`limit` and `offset` are generally only useful in combination with `order by`.

### `order by`

An `order by` clause will order the result set on a selection of columns in
ascending or descending order. The argument to an `order by` is a
comma-separated list of fields, each optionally appended with a keyword `asc`
or `desc`. If no keyword is supplied, `asc` is assumed:

    reports {certname = "foo.com" order by receive_time}

    reports {certname ~ "web.*" order by receive_time, certname desc}

    reports {certname ~ "web.*" order by receive_time desc, certname desc limit 10}
