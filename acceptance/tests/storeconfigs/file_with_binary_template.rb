test_name "submit a catalog that contains a file built from a binary template" do

  test_config = PuppetDBExtensions.config

# This test came about as a result of ticket #14873 .  The issue was that
# if a node had a file whose contents were provided by a template, and if
# the template produced certain kinds of binary data, we were not
# handling our UTF-8 sanitizing properly.  This was resulting in a checksum
# failure between the Ruby terminus and the Clojure puppetdb service, because
# they were not seeing the same string.
#
# A successful catalog submission containing a sampling of bytes that were
# known to cause this problem indicates that the checksums are now matching
# properly.

  manifest = <<-EOF
file { "/tmp/myfile":
  owner => "root",
  group => "root",
  mode => 755,

  content => template("foomodule/mytemplate.erb"),
  tag => "binary_file"
}
  EOF

  tmpdir = master.tmpdir('storeconfigs')
  moduledir = master.tmpdir('storeconfigs-moduledir')

  test_module = File.join(test_config[:acceptance_data_dir], "storeconfigs", "file_with_binary_template", "modules", "foomodule")

  scp_to(master, test_module, moduledir)

  manifest_file = File.join(tmpdir, 'site.pp')
  create_remote_file(master, manifest_file, manifest)

  on master, "chmod -R +rX #{tmpdir}"
  on master, "chmod -R +rX #{moduledir}"

  result = on master, "puppet master --configprint modulepath"
  resmod = "#{result.stdout.strip}:#{moduledir}"

  with_puppet_running_on(master,
    'master' => {
      'autosign' => 'true',
      'manifest' => manifest_file,
      'modulepath' => resmod
    }) do
    step "Run agent to submit catalog" do
      run_agent_on hosts, "--test --server #{master}", :acceptable_exit_codes => (0..4)
    end

  end

  sleep_until_queue_empty database

  on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/v3/resources --data 'query=["=",%20"tag",%20"binary_file"]' > binary_file.json|
  # We redirected this output to a file because if the invalid binary data was printed to the log from 
  # the curl statement, then Jenkins would try to parse it at the end of the run and fail.
  scp_from(database, "binary_file.json", ".")
  resources = JSON.parse(File.read("binary_file.json"))
  hosts.each do |host|
    assert_block("Catalog for #{host} was not stored in PuppetDB") do
      resources.any? do |resource| 
        resource["certname"] == host.node_name and
          resource["type"] == "File" and
          resource["title"] == "/tmp/myfile"
      end
    end
  end
end
