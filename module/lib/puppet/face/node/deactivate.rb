Puppet::Face.define(:node, '0.0.1') do
  action :deactivate do
    summary "Deactivate a set of nodes in PuppetDB"
    arguments "<node> [<node> ...]"
    description <<-DESC
      This will issue 'deactivate node' commands to the PuppetDB server for
      each node specified. The server is found by looking in
      $confdir/puppetdb.conf. If any command submissions fail, the process will
    be aborted.
    DESC

    when_invoked do |*args|
      opts = args.pop
      raise ArgumentError, "Please provide at least one node for deactivation" if args.empty?

      Puppet::Node.indirection.terminus_class = :puppetdb
      Puppet::Node.indirection.cache_class = nil

      args.inject({}) do |results,node|
        results.merge node => Puppet::Node.indirection.destroy(node)['uuid']
      end
    end

    when_rendering(:console) do |value|
      value.map do |node,uuid|
        "Submitted 'deactivate node' for #{node} with UUID #{uuid}"
      end.join("\n")
    end
  end
end
