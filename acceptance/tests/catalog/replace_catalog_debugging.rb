require 'json'
require 'time'
require 'cgi'

test_name "validation of basic PuppetDB resource event queries" do


  step "updating the config.ini to include catalog hash conflict debugging" do

    on database, "cp -p #{puppetdb_confdir(database)}/conf.d/config.ini #{puppetdb_confdir(database)}/conf.d/config.ini.bak"

    on database, "echo '
[global]
catalog-hash-conflict-debugging=true
' >> #{puppetdb_confdir(database)}/conf.d/config.ini"

    restart_puppetdb(database)
    sleep_until_started(database)
  end

  catalog_file_dir = master.tmpdir('catalog-test')

  first_run_manifest = <<MANIFEST
  file { '#{catalog_file_dir}' :
    ensure => directory
  }
  file { '#{catalog_file_dir}/foo' :
    ensure => present,
    content => "foo",
  }
MANIFEST

  rerun_agents_with_new_site_pp(master, first_run_manifest)

  sleep_until_queue_empty database

  second_run_manifest = <<MANIFEST

  file{ '#{catalog_file_dir}' :
    ensure => directory
  }
  file { '#{catalog_file_dir}/foo' :
    ensure => present,
    content => "foo",
  }
  file { '#{catalog_file_dir}/bar':
    ensure => present,
    content => "bar"
  }

MANIFEST

  catalog_hash_dir = catalog_hash_debug_dir(database)

  Log.notify "Clearing the catalog hash debugging directory: #{catalog_hash_dir}"

  on database, "rm -rf #{catalog_hash_dir}*"
  
  rerun_agents_with_new_site_pp(master, second_run_manifest)

  sleep_until_queue_empty database

  step "assert debugging results" do

    files = nil

    on database, "ls #{catalog_hash_dir}" do |res|
      files = res.stdout.split.map {|file| file.strip}
    end

    assert_equal(5 * agents.count, files.count)

    old_catalog_suffix = files.select { |file| file.end_with?("catalog-old.json") }.first
    old_catalog_path = File.join(catalog_hash_dir, old_catalog_suffix)
    scp_from(database, old_catalog_path, ".")

    new_catalog_suffix = files.select { |file| file.end_with?("catalog-new.json") }.first
    new_catalog_path = File.join(catalog_hash_dir, new_catalog_suffix)
    scp_from(database, new_catalog_path, ".")

    old_catalog = JSON.parse( File.read(old_catalog_suffix) )
    new_catalog = JSON.parse( File.read(new_catalog_suffix) )

    old_foo_resource = old_catalog['resources'].select{ |res| res['title'] == "#{catalog_file_dir}/foo" }.first
    assert_equal("foo", old_foo_resource['parameters']['content'])

    new_foo_resource = new_catalog['resources'].select{ |res| res['title'] == "#{catalog_file_dir}/foo" }.first
    assert_equal("foo", new_foo_resource['parameters']['content'])

    assert(old_catalog['resources'].select{ |res| res['title'] == "#{catalog_file_dir}/bar" }.empty?)

    new_bar_resource = new_catalog['resources'].select{ |res| res['title'] == "#{catalog_file_dir}/bar" }.first
    assert_equal("bar", new_bar_resource['parameters']['content'])

  end

  on database, "cp -p #{puppetdb_confdir(database)}/conf.d/config.ini.bak #{puppetdb_confdir(database)}/conf.d/config.ini"
end
