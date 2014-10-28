step "Create a fake entry in /etc/hosts to prevent test nodes from checking in with dujour" do
  manifest_content = <<-EOS
    host { "updates.puppetlabs.com":
       ip     => '127.0.0.1',
       ensure => 'present',
    }
  EOS
  databases.each do |database|
    manifest_path = database.tmpfile("puppetdb_manifest.pp")
    create_remote_file(database, manifest_path, manifest_content)
    on database, puppet_apply("#{manifest_path}")
  end
end
