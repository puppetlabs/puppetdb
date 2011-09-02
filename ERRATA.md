## Known issues

* We don't correctly model dependencies between Puppet resources that
  have an auto-require relationship. If you have 2 file resources
  where one is a parent directory of the other, Puppet will
  automatically create a dependency such that the child requires the
  parent. The problem is that such a dependency is *not* reflected in
  the catalog...the catalog, as currently represented, contains no
  hint that such a relationship ever existed.
