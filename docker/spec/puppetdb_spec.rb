require 'timeout'
require 'json'
require 'rspec'
require 'net/http'

describe 'puppetdb container specs' do
  include Pupperware::SpecHelpers

  VOLUMES = [
    'pgdata'
  ]

  before(:all) do
    if ENV['PUPPET_TEST_DOCKER_IMAGE'].nil?
      fail <<-MSG
      error_message = <<-MSG
  * * * * *
  PUPPET_TEST_DOCKER_IMAGE environment variable must be set so we
  know which image to test against!
  * * * * *
      MSG
    end
    status = run_command('docker-compose --no-ansi version')[:status]
    if status.exitstatus != 0
      fail "`docker-compose` must be installed and available in your PATH"
    end

    teardown_cluster()
    # LCOW requires directories to exist
    create_host_volume_targets(ENV['VOLUME_ROOT'], VOLUMES)

    # fire up the cluster and wait for puppetdb creation in postgres
    run_command('docker-compose --no-ansi up --detach')
    wait_on_service_health('postgres', 90)
  end

  after(:all) do
    emit_logs()
    teardown_cluster()
  end

  it 'should have installed postgres extensions' do
    installed_extensions = get_postgres_extensions
    expect(installed_extensions).to match(/^\s+pg_trgm\s+/)
    expect(installed_extensions).to match(/^\s+pgcrypto\s+/)
  end

  it 'should have started puppetdb' do
    expect(get_service_container('puppetdb', 60)).to_not be_empty
  end

  it 'should have a "running" puppetdb container' do
    expect(wait_on_service_health('puppetdb', 300)).to eq('healthy')
  end
end
