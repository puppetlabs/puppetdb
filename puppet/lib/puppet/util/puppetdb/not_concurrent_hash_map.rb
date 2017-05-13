require 'thread'

module Puppet::Util::Puppetdb
  # A class meant to resemble java.util.concurrent.ConcurrentHashMap, to the
  # extent we use it. When running under puppet server, a real ConcurrentHashMap
  # will be injected which is shared between all local JRuby instances. When
  # running under plain ruby, we use this as a substitute. It isn't actually
  # thread-safe in any way, as we don't need it to be in that context.
  class NotConcurrentHashMap
    def initialize()
      @value = {}
    end

    def get(key)
      @value[key]
    end

    def put(key, value)
      @value[key] = value
    end

    def putIfAbsent(key, value)
      @value[key] = value unless @value[key]
    end
  end
end
