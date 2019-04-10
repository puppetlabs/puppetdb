require 'open3'

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

  # Windows requires directories to exist prior, whereas Linux will create them
  def create_host_volume_targets(root, volumes)
    return unless !!File::ALT_SEPARATOR

    STDOUT.puts("Creating volumes directory structure in #{root}")
    volumes.each { |subdir| FileUtils.mkdir_p(File.join(root, subdir)) }
    # Hack: grant all users access to this temp dir for the sake of Docker daemon
    run_command("icacls \"#{root}\" /grant Users:\"(OI)(CI)F\" /T")
  end

  def get_container_port(container, port)
    @mapped_ports["#{container}:#{port}"] ||= begin
      service_ip_port = run_command("docker port #{container} #{port}/tcp")[:stdout].chomp
      uri = URI("http://#{service_ip_port}")
      uri.host = 'localhost' if uri.host == '0.0.0.0'
      STDOUT.puts "determined #{container} endpoint for port #{port}: #{uri}"
      uri
    end
    @mapped_ports["#{container}:#{port}"]
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

end
