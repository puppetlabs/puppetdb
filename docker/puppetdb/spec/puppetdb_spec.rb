require 'timeout'
require 'json'
require 'rspec'
require 'net/http'

describe 'puppetdb container specs' do
  include Helpers

  VOLUMES = [
    'pgdata'
  ]

  def count_postgres_database(database)
    cmd = "docker-compose --no-ansi exec -T postgres psql -t --username=puppetdb --command=\"SELECT count(datname) FROM pg_database where datname = '#{database}'\""
    run_command(cmd)[:stdout].strip
  end

  def wait_on_postgres_db(database, seconds = 240)
    return retry_block_up_to_timeout(seconds) do
      count_postgres_database('puppetdb') == '1' ? '1' :
        raise("database #{database} never created")
    end
  end

  def get_puppetdb_state
    # make sure PDB container hasn't stopped
    get_service_container('puppetdb', 5)
    # now query its status endpoint
    pdb_uri = URI::join(get_service_base_uri('puppetdb', 8080), '/status/v1/services/puppetdb-status')
    response = Net::HTTP.get_response(pdb_uri)
    STDOUT.puts "retrieved raw puppetdb status: #{response.body}"
    case response
      when Net::HTTPSuccess then
        return JSON.parse(response.body)['state']
      else
        return ''
    end
  rescue Errno::ECONNREFUSED, Errno::ECONNRESET, EOFError => e
    STDOUT.puts "PDB not accepting connections yet #{pdb_uri}: #{e}"
    return ''
  rescue JSON::ParserError
    STDOUT.puts "Invalid JSON response: #{e}"
    return ''
  rescue
    STDOUT.puts "Failure querying #{pdb_uri}: #{$!}"
    raise
  end

  def get_postgres_extensions
    return retry_block_up_to_timeout(30) do
      query = 'docker-compose --no-ansi exec -T postgres psql --username=puppetdb --command="SELECT * FROM pg_extension"'
      extensions = run_command(query)[:stdout].chomp
      raise('failed to retrieve extensions') if extensions.empty?
      STDOUT.puts("retrieved extensions: #{extensions}")
      extensions
    end
  end

  def wait_on_puppetdb_status(seconds = 240)
    # since pdb doesn't have a proper healthcheck yet, this could spin forever
    # add a timeout so it eventually returns.
    return retry_block_up_to_timeout(seconds) do
      get_puppetdb_state() == 'running' ? 'running' :
        raise('puppetdb never entered running state')
    end
  end

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
    wait_on_postgres_db('puppetdb')
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
    expect(wait_on_puppetdb_status()).to eq('running')
  end
end
