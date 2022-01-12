unless (test_config[:skip_presuite_provisioning])
  step "Flushing iptables chains" do
    hosts.each do |host|
      on host, 'apt-get install -y iptables' if is_bullseye
      on host, "iptables -F INPUT -t filter"
      on host, "iptables -F FORWARD -t filter"
    end
  end
end
