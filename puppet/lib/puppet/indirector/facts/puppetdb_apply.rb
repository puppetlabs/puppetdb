require 'puppet/indirector/facts/puppetdb'

# This class provides an alternative implementation of the Facts::Puppetdb
# terminus that better suits execution via `puppet apply`.
#
# This terminus is designed to be used as a cache terminus, to ensure that facts
# are stored in PuppetDB. It does not act as a real cache itself however, it
# tells Puppet to fallback to the `terminus` instead.
class Puppet::Node::Facts::PuppetdbApply < Puppet::Node::Facts::Puppetdb
  attr_writer :dbstored

  # Here we override the normal save, only saving the first time, as a `save`
  # can be called multiple times in a puppet run.
  def save(args)
    unless @dbstored
      @dbstored = true
      super(args)
    end
  end

  # By returning nil, we force puppet to use the real terminus.
  def find(args)
    nil
  end
end
