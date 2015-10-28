require 'thread'

module Puppet::Util::Puppetdb
  class Atom
    def initialize(value)
      @value = value
      @mutex = Mutex.new
    end

    def deref()
      @mutex.synchronize {
        @value
      }
    end

    def reset(value)
      @mutex.synchronize {
        @value = value
      }
    end
  end
end
