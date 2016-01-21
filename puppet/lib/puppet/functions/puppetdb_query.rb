require 'puppet/util/puppetdb'
Puppet::Functions.create_function(:puppetdb_query) do
  def puppetdb_query(query)
    Puppet::Util::Puppetdb.query_puppetdb(query)
  end
end
