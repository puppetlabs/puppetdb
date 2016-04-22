require 'puppet/util/puppetdb'
Puppet::Functions.create_function(:puppetdb_query) do
  dispatch :puppetdb_query do
    required_param 'Variant[String[1], Array[Data, 1]]', :query
  end

  def puppetdb_query(query)
    Puppet::Util::Puppetdb.query_puppetdb(query)
  end
end
