test_name "anonymize tool - with profile anonymization" do
  sbin_loc = puppetdb_sbin_dir(database)

  step "clear puppetdb database so that we can control exactly what we will eventually be exporting" do
    clear_and_restart_puppetdb(database)
  end

  step "setup a test manifest for the master and perform agent runs" do
    manifest = <<-MANIFEST
      node default {
        @@notify { "exported_resource": }
        notify { "non_exported_resource": }
     }
    MANIFEST

    run_agents_with_new_site_pp(master, manifest)
  end

  export_file1 = "./puppetdb-export1.tar.gz"
  anon_file = "./puppetdb-anon.tar.gz"
  export_file2 = "./puppetdb-export2.tar.gz"

  step "export data from puppetdb" do
    on database, "#{sbin_loc}/puppetdb export --outfile #{export_file1}"
    scp_from(database, export_file1, ".")
  end

  ["full", "moderate", "none"].each do |type|
    step "clear puppetdb database so that we can import into a clean db" do
      clear_and_restart_puppetdb(database)
    end

    step "anonymize the data with profile '#{type}'" do
      on database, "#{sbin_loc}/puppetdb anonymize --infile #{export_file1} --outfile #{anon_file} --profile #{type}"
      scp_from(database, anon_file, ".")
    end

    step "import data into puppetdb" do
      on database, "#{sbin_loc}/puppetdb import --infile #{anon_file}"
      sleep_until_queue_empty(database)
    end

    step "export data from puppetdb again" do
      on database, "#{sbin_loc}/puppetdb export --outfile #{export_file2}"
      scp_from(database, export_file2, ".")
    end

    if type == "none"
      step "verify original export data matches new export data" do
        compare_export_data(export_file1, export_file2, :facts => false)
      end
    else
      step "verify anonymized data matches new export data" do
        compare_export_data(anon_file, export_file2, :facts => false)
      end
    end
  end
end
