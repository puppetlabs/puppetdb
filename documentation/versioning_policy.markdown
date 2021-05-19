---
title: "Versioning policy"
layout: default
canonical: "/puppetdb/latest/versioning_policy.html"
---
# Versioning policy

[semver]: http://semver.org
[commands]: ./api/command/v1/commands.markdown

This document aims to create some transparency about how we version the PuppetDB software, so that both developers and users can understand the rules we try to follow internally.

There are a few different levels of versioning we have to consider when it comes to PuppetDB:

* PuppetDB software
* HTTP API
* Metrics API
* Commands and wire formats
* Upgrades

Some general statements about this policy:

* The API versioning policy only applies to versions after v3.
* If it's not written down, one shouldn’t assume it to be true or false. We expect people to raise bugs on this policy if they find a matter that needs clarification and is not already covered.

## PuppetDB software

This relates to the versioning associated with an overall PuppetDB release. In this case, we follow the rules of [Semantic Versioning][semver] as closely as possible. We’ll speak about that in this document using the X.Y.Z notation:

> "A normal version number MUST take the form X.Y.Z where X, Y, and Z are non-negative integers, and MUST NOT contain leading zeroes. X is the major version, Y is the minor version, and Z is the patch version." - semver.org

## HTTP API

This means the top level (such as /v2, /v3, /v4 ... /v10) parts we see prefixed to HTTP end-points. Today, this includes the query API, metrics API, and the command submission API.

There are four states a versioned API can be in:

* Current
* Experimental/Future
* Deprecated
* Retired

### Current

Changes to the existing "stable" or "current" API can be made if the change is largely deemed backward compatible (so long as the consumer is less strict about new unexpected parameters).

For example:

* Adding a new optional parameter to an existing query end-point response
* A new end-point
* A new query operator
* New content-type support for submission (for example: application/json or application/msgpack)
* A new optional parameter for /command submission

Changes that remove or rename endpoints, fields, and query operators, however, must be performed in experimental API versions only.

### Experimental

The experimental API is where non-backward-compatible "breaking" changes belong. This API version also includes features that require some experimentation and user testing before they are able to be moved into the current version.

The experimental API may change without notice to allow us to refine this future API rapidly. However, we will endeavor to notify users of this change in our release notes.

The experimental API will usually become current on the next major version boundary of PuppetDB (a version X release from a semver perspective).

### Deprecated

A deprecated API is no longer current and is on its way to retirement. These APIs are no longer actively maintained/changed. As soon as a version is marked as deprecated, users should be moving off of it immediately.

Deprecation of an old API version implies retirement on the next major version boundary of PuppetDB (a version X release from a semver perspective).

### Retired

Retired APIs have been removed and no longer function. A deprecated API will usually become retired implicitly on the next PuppetDB X release boundary.

At this stage all functionality is removed and documentation is removed.

## Commands and wire formats

Commands can be versioned on an individual command basis, so we are generally free to create revisions as required. This is quite different from the query API, where we need to version all endpoints at the same time.

Commands are primarily represented by a corresponding wire format. Wire formats are versioned along with the corresponding command.

Changes to an existing command version can be made if the change is backward compatible. For example:

* Addition of optional parameters within a command or wire format
* Change of a required parameter to optional
* Addition of a new enum value for an existing field

Some examples of changes that *will* require a new command version:

* Removal or renaming of parameters or parameter values within a command or wire format
* Change to serialization for wire formats inside payload

The [API commands][commands] documentation contains more concrete information about the existing commands, versions and statuses for this version of PuppetDB.

## Upgrades

PuppetDB supports upgrading from prior releases. Upgrading ensures data and configuration information is preserved across releases. Upgrades are only supported from any previous release in the same major version or any release in the prior major version. As an example, it's safe to upgrade from 2.0.0 to 2.2.2, or from 1.6.0 to 2.2.2. We don't support upgrading from 1.6.0 straight to 3.0.0. Users in this situation will want to first upgrade from 1.6.0 to 2.2.2, then from 2.2.2 to 3.0.0.
