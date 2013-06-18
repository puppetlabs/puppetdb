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

  with_master_running_on master, "--autosign true --manifest #{manifest_file} --modulepath #{moduledir}", :preserve_ssl => true do

    step "Run agent to submit catalog" do
      run_agent_on hosts, "--test --server #{master}", :acceptable_exit_codes => [0,2]
    end

  end
end
