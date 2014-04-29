---
title: "PuppetDB 2.0 » Versioning Policy"
layout: default
canonical: "/puppetdb/latest/versioning_policy.html"
---

[semver]: http://semver.org

This document aims to create some transparency about how we version the PuppetDB software, so that both developers and users can understand what rules we try to follow internally.

Specifically there are a few different levels of versioning we have to consider when it comes to PuppetDB:

* PuppetDB Software
* HTTP API
* Commands & Wire Formats

Some general statements about this policy:

* The API versioning policy does not apply historically, this is a new policy for v4. Once v4 becomes ‘current’ all rules applicable to current apply to it.
* If its not written down, one shouldn’t assume it to be true or false. We expect people to raise bugs on this policy if they find a matter needs clarification and it is not already covered.

## PuppetDB Software

This relates to the versionioning associated with an overall PuppetDB release. In this case, we follow the rules of [Semantic Versioning][semver] as closely as possible. And we’ll speak about that in this document using the X.Y.Z notation:

> "A normal version number MUST take the form X.Y.Z where X, Y, and Z are non-negative integers, and MUST NOT contain leading zeroes. X is the major version, Y is the minor version, and Z is the patch version." - semver.org

There are some ambiguities in semver which we have debated already, and other parts we disagree with. The exceptions/policies we have adopted on top of semver include:

* If we want to release a new distro of an existing release (say 1.6.1); we can cut a Z release (in this case 1.6.2) with that distro only. We do not consider supporting a new OS distro as a mandatory Y axis increment.

## HTTP API

This means the top level (eg. /v2, /v3, /v4 ... /v10) parts we see prefixed to HTTP end-points. This includes the query API and the command submission API today.

There are 4 states a versioned API can be in:

* Current
* Future/Experimental
* Deprecated
* Retired

> **Note:** The commands end-point are also versioned with the query end-points, however commands themselves have their own versioning independently (which we talk about in another section).

### Current

Things that we can add to the existing ‘stable’ or ‘current’ API that are largely deemed as backwards compatible as long as the consumer is less strict about new unexpected parameters.

For example:
* Adding new optional parameter to an existing query end-point response
* New end-point
* New query operator
* New content-type support for submission (for example: application/json or application/msgpack)
* New optional parameter for /command submission

Now to be clear, all new ‘features’ are Y releases from a PuppetDB software perspective at the very least.

### Experimental

This versioning state is for future versioning.

Now for us to trigger something going into the ‘experimental’ version of an API, that would indicate a non-backwards compatible behaviour, generally meaning that old queries or commands would accept old parameters, or would stop returning old parameters in responses.

This layers also provides for features that are generally not ‘stable’ in their implementation, and we want features to soak before they are provided in a stable release.

A new version of the Experimental API is creatable for a PuppetDB Y release as long as the current and old API’s are still maintained.

Some examples of changes that need to be in the 'Experimental' API:

* Retiring parameters from an existing query end-point
* Change to existing query operator

The experimental API is implicitly marked as experimental until it becomes current and may change without notice to allow us to refine this future API, however we will endevaour to notify users of this change and keep breaking API changes confined to Y releases of the software (5.1.0, 6.3.0 etc.).

### Deprecated

Deprecated API's are on their way to retirement and are thus no longer actively maintained/changed. Once an API version is graduated from Future/Experimental to Current the existing current MUST be marked as deprecated. All bug-fixes and improvements only go into other versions of the API, with the exception of security fixes. As soon as a version is marked as deprecated, users should be moving off of it immediately.

Retirement of an old API version implies retirement on the next major version boundary of PuppetDB (that is, the X in X.Y.Z from a semver perspective). So if the API is old in 7.3.2, it will be removed in 8.0.0.

### Retired

Retired API’s were already deprecated API’s that have now been removed. Deprecated API’s can only move into a retired state during an X release (eg 3.0.0, 4.0.0).

At this stage all functionality is removed and documentation is removed.

## Commands & Wire Formats

Commands can be versioned on an individual command basis so they are fairly flexible in that respect and we generally freely create new revisions as required. This is quite different to the query API, where we need to version all end-points at the same time.

Commands are primarily represented by a corresponding wire format (with the exception of “deactivate node” which only takes 1 parameter). Wire formats are versioned along with their corresponding command.

The reasons to trigger a new command version are more common, and in general if we aren’t sure its easy enough to create another version anyway.

Some examples of changes that will require a new command version:

* Any change to the parameters or parameter values within a command or wire format.
* Change to serialization for wire formats inside payload.

All new commands will start at version 1.
