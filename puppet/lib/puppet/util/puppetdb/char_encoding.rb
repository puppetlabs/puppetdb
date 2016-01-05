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
  NOT_INVALID_REGEX = Regexp.new( "[^" + DEFAULT_INVALID_CHAR + "]" )

  # @api private
  #
  # Finds the beginning and ending index of the first block of invalid
  # characters.
  #
  # @param str string to scan for invalid characters
  # @return Range
  def self.first_invalid_char_range(str)
    begin_bad_chars_idx = str.index(DEFAULT_INVALID_CHAR)

    if begin_bad_chars_idx
      first_good_char = str.index(NOT_INVALID_REGEX, begin_bad_chars_idx)
      Range.new(begin_bad_chars_idx, (first_good_char || str.length) - 1)
    else
      nil
    end
  end

  # @api private
  #
  # Scans the string str with invalid characters found at
  # bad_char_range and returns a message that give some context around
  # the bad characters. This will give up to 100 characters prior to
  # the bad character and 100 after. It will return fewer if it's at
  # the beginning of a string or if another bad character appears
  # before reaching the 100 characters
  #
  # @param str string coming from to_pson, likely a command to be submitted to PDB
  # @param bad_char_range a range indicating a block of invalid characters
  # @return String
  def self.error_char_context(str, bad_char_range)

    gap = bad_char_range.to_a.length

    start_char = [0, bad_char_range.begin-100].max
    end_char = [str.index(DEFAULT_INVALID_CHAR, bad_char_range.end+1) || str.length, bad_char_range.end+100].min
    prefix = str[start_char..bad_char_range.begin-1]
    suffix = str[bad_char_range.end+1..end_char-1]

    "'#{prefix}' followed by #{gap} invalid/undefined bytes then '#{suffix}'"
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

    if str.index(DEFAULT_INVALID_CHAR).nil?
      str
    else
      Puppet.warning "#{error_context_str} ignoring invalid UTF-8 byte sequences in data to be sent to PuppetDB, see debug logging for more info"

      if Puppet.settings[:log_level] == "debug"
        Puppet.debug error_context_str + "\n" + error_char_context(str, first_invalid_char_range(str))
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
  # @param error_context_str information about where this string came from for
  # use in error messages. Defaults to nil, in which case no error is reported.
  # @return Str
  def self.coerce_to_utf8(str, error_context_str=nil)
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

      str_lossy = str_copy.encode!("UTF-8",
                                   :invalid => :replace,
                                   :undef => :replace,
                                   :replace => DEFAULT_INVALID_CHAR)
      if !error_context_str.nil?
        warn_if_invalid_chars(str_lossy, error_context_str)
      else
        str_lossy
      end
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
