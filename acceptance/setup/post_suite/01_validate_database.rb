step "Verify we've been talking to the correct database" do

  # The goal here is just to try to make sure we've tested
  #  what we think we've tested.  e.g., if we intended to
  #  run the tests against postgres, let's try to make
  #  sure that they didn't accidentally get run against the
  #  embedded db and vice-versa.  (The tests could theoretically
  #  all pass even if they were running against a different
  #  database than we'd intended.)

  # If we're running w/postgres, we're going to use some hacky raw SQL
  #  and shell magic to validate that the database schema version is the
  #  same in the database as in the source code, and that the certnames
  #  table contains exactly the list of hostnames as the hosts array.
  # If so, we'll assume that puppetdb was communicating with postgres
  #  successfully during the test run. (These conditions were chosen
  #  somewhat arbitrarily.)

  step "Validate database schema version" do
    db_migration_version = on(database, "PGPASSWORD=puppetdb psql -h localhost -U puppetdb -d puppetdb --tuples-only -c \"SELECT max(version) from schema_migrations\" |tr -d \" \" |grep -v \"^\s*$\"").stdout.strip

    # This is terrible; it's dependent on the order of some function definitions
    # in the code, and it's just generally moronic.  We should provide a lein task
    # or some way of interrogating the latest expected schema version from
    # the command line so that we can get rid of this crap.
    source_migration_version = on(database, "java -cp /usr/share/puppetdb/puppetdb.jar clojure.main -m puppetlabs.puppetdb.core version |grep target_schema_version|cut -f2 -d'='").stdout.strip

    assert_equal(db_migration_version, source_migration_version,
                 "Expected migration version from source code '#{source_migration_version}' " +
                 "to match migration version found in postgres database '#{db_migration_version}'")
  end


  step "Validate the contents of the certname table" do
    db_hostnames_str = on(database, "PGPASSWORD=puppetdb psql -h localhost -U puppetdb -d puppetdb --tuples-only -c \"SELECT name from certnames\" |grep -v \"^\s*$\"").stdout.strip
    db_hostnames = Set.new(db_hostnames_str.split.map { |x| x.strip })
    acceptance_hostnames = Set.new(hosts.map { |host| host.node_name })
    assert_equal(acceptance_hostnames, db_hostnames, "Expected hostnames from certnames table to match the ones known to the acceptance harness")
  end
end
