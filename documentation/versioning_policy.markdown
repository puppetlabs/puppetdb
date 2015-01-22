---
title: "PuppetDB 2.2 » Versioning Policy"
layout: default
canonical: "/puppetdb/latest/versioning_policy.html"
---

[semver]: http://semver.org
[commands]: ./api/commands.html

This document aims to create some transparency about how we version the PuppetDB software, so that both developers and users can understand what rules we try to follow internally.

Specifically there are a few different levels of versioning we have to consider when it comes to PuppetDB:

* PuppetDB Software
* HTTP API
* Metrics API
* Commands & Wire Formats

Some general statements about this policy:

* The API versioning policy only applies to versions after v3.
* If it's not written down, one shouldn’t assume it to be true or false. We expect people to raise bugs on this policy if they find a matter needs clarification and it is not already covered.

## PuppetDB Software

This relates to the versioning associated with an overall PuppetDB release. In this case, we follow the rules of [Semantic Versioning][semver] as closely as possible. And we’ll speak about that in this document using the X.Y.Z notation:

> "A normal version number MUST take the form X.Y.Z where X, Y, and Z are non-negative integers, and MUST NOT contain leading zeroes. X is the major version, Y is the minor version, and Z is the patch version." - semver.org

## HTTP API

This means the top level (eg. /v2, /v3, /v4 ... /v10) parts we see prefixed to HTTP end-points. This includes the query API, metrics API and the command submission API today.

There are 4 states a versioned API can be in:

* Current
* Future/Experimental
* Deprecated
* Retired

> **Note:** The commands end-point is also versioned with the query end-points, however commands themselves have their own versioning and as such fall under different versioning rules.

### Current

Things that we can add to the existing ‘stable’ or ‘current’ API that are largely deemed as backwards compatible as long as the consumer is less strict about new unexpected parameters.

For example:
* Adding new optional parameter to an existing query end-point response
* New end-point
* New query operator
* New content-type support for submission (for example: application/json or application/msgpack)
* New optional parameter for /command submission

Changes that remove or rename end-points, fields and query operators however must be performed in experimental only.

### Experimental

The experimental API is where breaking changes belong, changes that are not backwards compatible and unable to be place in current. This also includes features that require some experimentation and user testing before they are able to be moved into current.

The experimental API may change without notice to allow us to refine this future API rapidly, however we will endeavour to notify users of this change in our release notes.

The experimental API will usually become current on the next major version boundary of PuppetDB (a version X release from a semver perspective).

### Deprecated

Deprecated API's are no longer current and are on their way to retirement and are thus no longer actively maintained/changed. As soon as a version is marked as deprecated, users should be moving off of it immediately.

Deprecation of an old API version implies retirement on the next major version boundary of PuppetDB (a version X release from a semver perspective).

### Retired

Retired API’s that have now been removed and no longer function. A deprecated API will usually become retired implicitly on the next PuppetDB X release boundary.

At this stage all functionality is removed and documentation is removed.

## Commands & Wire Formats

Commands can be versioned on an individual command basis so they are fairly flexible in that respect and we generally freely create new revisions as required. This is quite different to the query API, where we need to version all end-points at the same time.

Commands are primarily represented by a corresponding wire format. Wire formats are versioned along with the corresponding command.

The reasons to trigger a new command version are more common, and in general if we aren’t sure it's easy enough to create another version anyway.

Some examples of changes that will require a new command version:

* Any change to the parameters or parameter values within a command or wire format.
* Change to serialization for wire formats inside payload.

The [API Commands][commands] documentation contains more concrete information about the existing commands, versions and statuses for this version of PuppetDB.
