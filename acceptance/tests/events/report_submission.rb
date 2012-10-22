require 'json'

test_name "basic validation of puppet report submission" do

  Log.notify "Setting up manifest file"
  manifest = <<MANIFEST
notify { "hi": }
MANIFEST

  tmpdir = master.tmpdir('storeconfigs')

  manifest_file = File.join(tmpdir, 'site.pp')

  create_remote_file(master, manifest_file, manifest)

  on master, "chmod -R +rX #{tmpdir}"


  step "do an agent run to generate the report" do
    with_master_running_on master, "--storeconfigs --storeconfigs_backend puppetdb --reports=store,puppetdb --autosign true --manifest #{manifest_file}", :preserve_ssl => true do
      agents.each do |agent|
        # This is a little unfortunate.  What we want to do is trigger a puppet run
        # on a node (which will trigger its report submission), and then verify
        # that the data in the submitted report is correct.  However, we have no
        # real way of identifying the "event group" of the report; it has a unique
        # id, but we don't know what it is.
        #
        # The only choice we really have for the moment is to do the puppet run
        # for *exactly* one node, then issue a query to puppetdb to get *all*
        # of the event-groups in the database.  From that, we can simply grab
        # the most recent one and get the ID from it.  Then we can query for
        # all of the events that match that ID.  Finally, we can do our
        # comparisons against that result set.
        #
        # Problems with this approach:
        # * It relies on their being *exactly* one event group (report) being
        #   submitted at a time, so if we were ever to parallelize the acceptance
        #   tests, the query to get the event group id could be at risk of
        #   returning something unpredictable.
        # * If there end up being a large number of event groups in the database
        #   as we build up more tests, the query that just retrieves *all* of them
        #   could become expensive or risk using too much RAM on the puppetdb
        #   server.  (This could be alleviated with a &limit parameter when we
        #   add support for that.)

        # First run the agent.
        run_agent_on agent, "--test --server #{master}", :acceptable_exit_codes => [0,2]

        # Now query for all of the event groups
        result = on database, "curl -G -H 'Accept: application/json' http://localhost:8080/event-groups"

        # They are sorted by most-recent first, so just grab the first one.
        group = JSON.parse(result.stdout)[0]

        # Now query for all of the events in this group
        result = on database, "curl -G -H 'Accept: application/json' 'http://localhost:8080/events' --data-urlencode 'group-id=#{group["group-id"]}'"
        events = JSON.parse(result.stdout)

        # This is a bit weird as well; all "skipped" resources during a puppet
        # run will end up having events generated for them.  However, during a
        # typical puppet run there are a bunch of "Schedule" resources that will
        # always show up as skipped.  Here we filter them out because they're
        # not really interesting for this test.
        events = events.reject {|x| x["resource-type"] == "Schedule" }

        assert_equal(1, events.length)

        event = events[0]
        assert_equal("Notify", event["resource-type"], "resource-type doesn't match!")
        assert_equal("hi", event["resource-title"], "resource-title doesn't match!")
        assert_equal(agent.node_name, event["certname"], "certname doesn't match!")
        assert_equal("message", event["property-name"], "property-name doesn't match!")
        assert_equal("hi", event["property-value"], "property-value doesn't match!")
      end
    end
  end
end
