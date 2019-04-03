require 'timeout'
require 'json'
require 'open3'
require 'rspec'
require 'net/http'

describe 'puppetdb container specs' do
  include Helpers

  def count_database(container, database)
    cmd = "docker exec #{container} psql -t --username=puppetdb --command=\"SELECT count(datname) FROM pg_database where datname = '#{database}'\""
    run_command(cmd)[:stdout].strip
  end

  def wait_on_postgres_db(container, database)
    Timeout::timeout(120) do
      while count_database(container, database) != '1'
        sleep(1)
      end
    end
  rescue Timeout::Error
    STDOUT.puts("database #{database} never created")
    raise
  end

  def run_postgres_container
    image_name = File::ALT_SEPARATOR.nil? ?
      'postgres:9.6' :
      'stellirin/postgres-windows:9.6'
    run_command("docker pull #{image_name}")

    postgres_custom_source = File.join(File.expand_path(__dir__), '..', 'postgres-custom')

    postgres_custom_target = File::ALT_SEPARATOR.nil? ?
      '/docker-entrypoint-initdb.d' :
      'c:\docker-entrypoint-initdb.d'

    result = run_command("docker run --detach \
          --env POSTGRES_PASSWORD=puppetdb \
          --env POSTGRES_USER=puppetdb \
          --env POSTGRES_DB=puppetdb \
          --name postgres \
          --network #{@network} \
          --hostname postgres \
          --publish-all \
          --mount type=bind,source=#{postgres_custom_source},target=#{postgres_custom_target} \
          #{image_name}")
    fail 'Failed to create postgres container' unless result[:status].exitstatus == 0
    id = result[:stdout].chomp

    # this is necessary to add a wait for database creation
    wait_on_postgres_db(id, 'puppetdb')

    return id
  end

  def run_puppetdb_container
    # skip Postgres SSL initialization for tests with USE_PUPPETSERVER
    result = run_command("docker run --detach \
          --env USE_PUPPETSERVER=false \
          --env PUPPERWARE_DISABLE_ANALYTICS=true \
          --name puppetdb \
          --hostname puppetdb \
          --publish-all \
          --network #{@network} \
          #{@pdb_image}")
    fail 'Failed to create puppetdb container' unless result[:status].exitstatus == 0
    result[:stdout].chomp
  end

  def get_puppetdb_state
    pdb_uri = URI::join(get_container_port(@pdb_container, 8080), '/status/v1/services/puppetdb-status')
    status = Net::HTTP.get_response(pdb_uri).body
    STDOUT.puts "retrieved raw puppetdb status: #{status}"
    return JSON.parse(status)['state'] unless status.empty?
  rescue
    STDOUT.puts "Failure querying #{pdb_uri}: #{$!}"
    return ''
  end

  def get_postgres_extensions
    result = run_command("docker exec #{@postgres_container} psql --username=puppetdb --command=\"SELECT * FROM pg_extension\"")
    extensions = result[:stdout].chomp
    STDOUT.puts("retrieved extensions: #{extensions}")
    extensions
  end

  def start_puppetdb
    status = get_puppetdb_state
    # since pdb doesn't have a proper healthcheck yet, this could spin forever
    # add a timeout so it eventually returns.
    # puppetdb entrypoint waits on a response from the master
    Timeout::timeout(240) do
      while status != 'running'
        sleep(1)
        status = get_puppetdb_state
      end
    end
  rescue Timeout::Error
    STDOUT.puts('puppetdb never entered running state')
    return ''
  else
    return status
  end

  before(:all) do
    @pdb_image = ENV['PUPPET_TEST_DOCKER_IMAGE']
    if @pdb_image.nil?
      error_message = <<-MSG
  * * * * *
  PUPPET_TEST_DOCKER_IMAGE environment variable must be set so we
  know which image to test against!
  * * * * *
      MSG
      fail error_message
    end

    @mapped_ports = {}
    # Windows doesn't have the default 'bridge network driver
    network_opt = File::ALT_SEPARATOR.nil? ? '' : '--driver=nat'

    result = run_command("docker network create #{network_opt} puppetdb_test_network")
    fail 'Failed to create network' unless result[:status].exitstatus == 0
    @network = result[:stdout].chomp

    @postgres_container = run_postgres_container

    @pdb_container = run_puppetdb_container
  end

  after(:all) do
    [
      @postgres_container,
      @pdb_container,
    ].each do |id|
      emit_log(id)
      STDOUT.puts("Killing container #{id}")
      run_command("docker container kill #{id}")
    end
    run_command("docker network rm #{@network}") unless @network.nil?
  end

  it 'should have started postgres' do
    expect(@postgres_container).to_not be_empty
  end

  it 'should have installed postgres extensions' do
    installed_extensions = get_postgres_extensions
    expect(installed_extensions).to match(/^\s+pg_trgm\s+/)
    expect(installed_extensions).to match(/^\s+pgcrypto\s+/)
  end

  it 'should have started puppetdb' do
    expect(@pdb_container).to_not be_empty
  end

  it 'should have a "running" puppetdb container' do
    status = start_puppetdb
    expect(status).to eq('running')
  end
end
