test_name "load series of facts see how long it takes" do
  clear_and_restart_puppetdb(database)

  step "run benchmark" do
    on(database, "#{LeinCommandPrefix} lein run benchmark -F acceptance/benchmark/facts/ -n 1000 -c acceptance/benchmark/config.ini -N 5 --rand-perc 10")
  end

  step "wait for queue to empty" do
    sleep_until_queue_empty database, 600
  end

  step "check to ensure no commands were rejected or discarded" do
    assert_equal(0, command_processing_stats(database, "discarded")["Count"])
    assert_equal(0, command_processing_stats(database, "fatal")["Count"])
  end
end
