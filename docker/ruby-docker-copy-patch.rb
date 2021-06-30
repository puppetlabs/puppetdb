# frozen_string_literal: true

require "fileutils"

# Fixes a linux 5.6 - 5.10 kernel bug around copy_file_range syscall
# https://github.com/docker/for-linux/issues/1015

module FileUtils
  class Entry_
    def copy_file(dest)
      File.open(path) do |s|
        File.open(dest, 'wb', s.stat.mode) do |d|
          s.chmod s.lstat.mode
          IO.copy_stream(s, d)
          d.chmod(d.lstat.mode)
        end
      end
    end
  end
end
