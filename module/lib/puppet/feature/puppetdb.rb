Puppet.features.add(:puppetdb) do
  begin
    default_server = "puppetdb"
    default_port = 8080

    require 'puppet/util/inifile'

    config = File.join(Puppet[:confdir], "puppetdb.conf")

    if File.exists?(config)
      Puppet.debug("Configuring PuppetDB terminuses with config file #{config}")
    else
      Puppet.debug("No puppetdb.conf file found; falling back to default #{default_server}:#{default_port}")
    end

    ini = Puppet::Util::IniConfig::File.new
    ini.read(config)

    main_section = ini[:main] || {}
    server = main_section['server'] || default_server
    port = main_section['port'] || default_port

    server = server.strip
    port = port.to_i

    # Make sure we've loaded the terminuses first
    [:catalog, :facts, :resource, :node].each do |type|
      Puppet::Indirector::Terminus.terminus_class(type, :puppetdb)
    end

    {
      :catalog  => Puppet::Resource::Catalog::Puppetdb,
      :facts    => Puppet::Node::Facts::Puppetdb,
      :resource => Puppet::Resource::Puppetdb,
      :node     => Puppet::Node::Puppetdb,
    }.each do |type, terminus|
      terminus.meta_def(:server) { server }
      terminus.meta_def(:port) { port }
    end

    true
  rescue => detail
    puts detail.backtrace if Puppet[:trace]
    Puppet.warning "Could not configure PuppetDB terminuses: #{detail}"
    raise
  end
end
