require 'open3'
require 'timeout'

module Helpers
  def run_command(command)
    stdout_string = ''
    status = nil

    Open3.popen3(command) do |stdin, stdout, stderr, wait_thread|
      Thread.new do
        stdout.each { |l| stdout_string << l and STDOUT.puts l }
      end
      Thread.new do
        stderr.each { |l| STDOUT.puts l }
      end

      stdin.close
      status = wait_thread.value
    end

    { status: status, stdout: stdout_string }
  end

  def retry_block_up_to_timeout(timeout, &block)
    ex = nil
    started = Process.clock_gettime(Process::CLOCK_MONOTONIC)

    loop do
      begin
        return yield
      rescue => e
        ex = e
        sleep(1)
      ensure
        if (Process.clock_gettime(Process::CLOCK_MONOTONIC) - started) > timeout
          raise Timeout::Error.new(ex)
        end
      end
    end
  end

  # Windows requires directories to exist prior, whereas Linux will create them
  def create_host_volume_targets(root, volumes)
    return unless !!File::ALT_SEPARATOR

    STDOUT.puts("Creating volumes directory structure in #{root}")
    volumes.each { |subdir| FileUtils.mkdir_p(File.join(root, subdir)) }
    # Hack: grant all users access to this temp dir for the sake of Docker daemon
    run_command("icacls \"#{root}\" /grant Users:\"(OI)(CI)F\" /T")
  end

  def get_service_base_uri(service, port)
    @mapped_ports["#{service}:#{port}"] ||= begin
      result = run_command("docker-compose --no-ansi port #{service} #{port}")
      service_ip_port = result[:stdout].chomp
      raise "Could not retrieve service endpoint for #{service}:#{port}" if service_ip_port == ''
      uri = URI("http://#{service_ip_port}")
      uri.host = 'localhost' if uri.host == '0.0.0.0'
      STDOUT.puts "determined #{service} endpoint for port #{port}: #{uri}"
      uri
    end
    @mapped_ports["#{service}:#{port}"]
  end

  def get_service_container(service, timeout = 120)
    return retry_block_up_to_timeout(timeout) do
      container = run_command("docker-compose --no-ansi ps --quiet #{service}")[:stdout].chomp
      if container.empty?
        raise "docker-compose never started a service named '#{service}' in #{timeout} seconds"
      end

      STDOUT.puts("service named '#{service}' is hosted in container: '#{container}'")
      container
    end
  end

  def teardown_cluster
    STDOUT.puts("Tearing down test cluster")
    get_containers.each do |id|
      STDOUT.puts("Killing container #{id}")
      run_command("docker container kill #{id}")
    end
    # still needed to remove network / provide failsafe
    run_command('docker-compose --no-ansi down --volumes')
  end

  def get_containers
    result = run_command('docker-compose --no-ansi --log-level INFO ps -q')
    ids = result[:stdout].chomp
    STDOUT.puts("Retrieved running container ids:\n#{ids}")
    ids.lines.map(&:chomp)
  end

  def inspect_container(container, query)
    result = run_command("docker inspect \"#{container}\" --format \"#{query}\"")
    status = result[:stdout].chomp
    STDOUT.puts "queried #{query} of #{container}: #{status}"
    return status
  end

  def get_container_name(container)
    inspect_container(container, '{{.Name}}')
  end

  def emit_log(container)
    container_name = get_container_name(container)
    STDOUT.puts("#{'*' * 80}\nContainer logs for #{container_name} / #{container}\n#{'*' * 80}\n")
    logs = run_command("docker logs --details --timestamps #{container}")[:stdout]
    STDOUT.puts(logs)
  end

  def emit_logs
    STDOUT.puts("Emitting container logs")
    get_containers.each { |id| emit_log(id) }
  end
end
