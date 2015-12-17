require 'puppet'

module Puppet
module Util
module Puppetdb
module CharEncoding


  # Some of this code is modeled after:
  #  https://github.com/brianmario/utf8/blob/ef10c033/ext/utf8/utf8proc.c
  #  https://github.com/brianmario/utf8/blob/ef10c033/ext/utf8/string_utf8.c

  Utf8CharLens = [
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
      2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
      3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
      4, 4, 4, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  ]

  Utf8ReplacementChar = [ 0xEF, 0xBF, 0xBD ].pack("c*")

  DEFAULT_INVALID_CHAR = "\ufffd"

  # @api private
  #
  # Takes an array and returns a sub-array without the last element
  #
  # @return [Object]
  def self.drop_last(array)
    array[0..-2]
  end

  # @api private
  #
  # Takes an array of increasing integers and collapses the sequential
  # integers into ranges
  #
  # @param index_array an array of sorted integers
  # @return [Range]
  def self.collapse_ranges(index_array)
    ranges = index_array.each.inject([]) do |spans, n|
      if spans.empty? || spans.last.end != n - 1
        spans << Range.new(n, n)
      else
        drop_last(spans) << Range.new(spans.last.begin,n)
      end
    end
  end

  # @api private
  #
  # Scans the string s with bad characters found at bad_char_indexes
  # and returns an array of messages that give some context around the
  # bad characters. This will give up to 100 characters prior to the
  # bad character and 100 after. It will return fewer if it's at the
  # beginning of a string or if another bad character appears before
  # reaching the 100 characters
  #
  # @param str string coming from to_pson, likely a command to be submitted to PDB
  # @param bad_char_indexes an array of indexes into the string where invalid characters were found
  # @return [String]
  def self.error_char_context(str, bad_char_indexes)
    bad_char_ranges = collapse_ranges(bad_char_indexes)
    bad_char_ranges.each_with_index.inject([]) do |state, (r, index)|
      gap = r.to_a.length

      prev_bad_char_end = bad_char_ranges[index-1].end + 1 if index > 0
      next_bad_char_begin = bad_char_ranges[index+1].begin - 1 if index < bad_char_ranges.length - 1

      start_char = [prev_bad_char_end || 0, r.begin-100].max
      end_char = [next_bad_char_begin || str.length - 1, r.end+100].min
      x = [next_bad_char_begin || str.length, r.end+100, str.length]
      prefix = str[start_char..r.begin-1]
      suffix = str[r.end+1..end_char]

      state << "'#{prefix}' followed by #{gap} invalid/undefined bytes then '#{suffix}'"
    end
  end

  # @api private
  #
  # Warns the user if an invalid character was found. If debugging is
  # enabled will also log contextual information about where the bad
  # character(s) were found
  #
  # @param str A string coming from to_pson, likely a command to be submitted to PDB
  # @param error_context_str information about where this string came from for use in error messages
  # @return String
  def self.warn_if_invalid_chars(str, error_context_str)
    first_bad_char_index = str.index(DEFAULT_INVALID_CHAR)
    if first_bad_char_index.nil?
      str
    else
      Puppet.warning "#{error_context_str} ignoring invalid UTF-8 byte sequences in data to be sent to PuppetDB, see debug logging for more info"
      if Puppet.settings[:log_level] == "debug"
        Puppet.debug error_context_str + "\n" + error_char_context(str, [first_bad_char_index]).join("\n")
      end

      str
    end
  end

  # @api private
  #
  # Attempts to coerce str to UTF-8, if that fails will output context
  # information using error_context_str
  #
  # @param str A string coming from to_pson, likely a command to be submitted to PDB
  # @param error_context_str information about where this string came from for use in error messages
  # @return Str
  def self.coerce_to_utf8(str, error_context_str)
    str_copy = str.dup
    # This code is passed in a string that was created by
    # to_pson. to_pson calls force_encoding('ASCII-8BIT') on the
    # string before it returns it. This leaves the actual UTF-8 bytes
    # alone. Below we check to see if this is the case (this should be
    # most common). In this case, the bytes are still UTF-8 and we can
    # just encode! and we're good to go. If They are not valid UTF-8
    # bytes, that means there is probably some binary data mixed in
    # the middle of the UTF-8 string. In this case we need to output a
    # warning and give the user more information
    str_copy.force_encoding("UTF-8")
    if str_copy.valid_encoding?
      str_copy.encode!("UTF-8")
    else
      # This is force_encoded as US-ASCII to avoid any overlapping
      # byte related issues that could arise from mis-interpreting a
      # random extra byte as part of a multi-byte UTF-8 character
      str_copy.force_encoding("US-ASCII")
      warn_if_invalid_chars(str_copy.encode!("UTF-8",
                                             :invalid => :replace,
                                             :undef => :replace,
                                             :replace => DEFAULT_INVALID_CHAR),
                            error_context_str)
    end
  end

  def self.utf8_string(str, error_context_str)
    begin
      coerce_to_utf8(str, error_context_str)
    rescue Encoding::InvalidByteSequenceError, Encoding::UndefinedConversionError => e
      # If we got an exception, the string is either invalid or not
      # convertible to UTF-8, so drop those bytes.

      warn_if_changed(str, str.encode('UTF-8', :invalid => :replace, :undef => :replace))
    end
  end

  # @api private
  def self.warn_if_changed(str, converted_str)
    if converted_str != str
      Puppet.warning "Ignoring invalid UTF-8 byte sequences in data to be sent to PuppetDB"
    end
    converted_str
  end

end
end
end
end
