require 'puppet/util/puppetdb/not_concurrent_hash_map'

module Puppet::Util::Puppetdb
  # A place to track the information which should be shared between all terminus
  # instances running on a node - the index of the last URL we were able to use
  # for querying. This should be as sticky as possible to prevent exported
  # resource drift when master-puppetdb conections flicker.
  #
  # When running under puppet server, a real ConcurrentHashMap will be injected
  # via the shared_terminus_state= class method which is shared between all
  # local JRuby instances. When running under plain ruby, a local ruby
  # implementation (called ConcurrentHashMap) is used.
  class SharedTerminusState
    MAP_KEY = :puppetdb_last_good_query_server_url_index

    @@shared_terminus_state = NotConcurrentHashMap.new

    def self.shared_terminus_state=(val)
      @@shared_terminus_state = val
    end

    def self.last_good_query_server_url_index
      @@shared_terminus_state.putIfAbsent(MAP_KEY, 0)
      @@shared_terminus_state.get(MAP_KEY)
    end

    def self.last_good_query_server_url_index=(val)
      @@shared_terminus_state.put(MAP_KEY, val)
    end
  end
end
