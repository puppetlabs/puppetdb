step "Flushing iptables chains" do
  hosts.each do |host|
    on host, "iptables -F INPUT -t filter"
    on host, "iptables -F FORWARD -t filter"
  end
end
