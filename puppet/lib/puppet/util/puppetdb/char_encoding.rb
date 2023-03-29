require 'puppet'

module Puppet
module Util
module Puppetdb
module CharEncoding


  # Some of this code is modeled after:
  #  https://github.com/brianmario/utf8/blob/ef10c033/ext/utf8/utf8proc.c
  #  https://github.com/brianmario/utf8/blob/ef10c033/ext/utf8/string_utf8.c

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
  # @param str A string: for example a command to be submitted to PDB
  # @return String
  def self.error_char_context(str)

    bad_char_range = first_invalid_char_range(str)

    gap = bad_char_range.to_a.length

    start_char = [0, bad_char_range.begin-100].max
    end_char = [str.index(DEFAULT_INVALID_CHAR, bad_char_range.end+1) || str.length, bad_char_range.end+100].min
    prefix = str[start_char..bad_char_range.begin-1]
    suffix = str[bad_char_range.end+1..end_char-1]

    "'#{prefix}' followed by #{gap} invalid/undefined bytes then '#{suffix}'"
  end

  # @api private
  #
  # Attempts to coerce str to UTF-8
  #
  # @param str A string: for example a command to be submitted to PDB
  # use in error messages. Defaults to nil, in which case no error is reported.
  # @return Str
  def self.coerce_to_utf8(str)
    str_copy = str.dup
    str_copy.force_encoding("UTF-8")
    if str_copy.valid_encoding?
      str_copy.encode!("UTF-8")
    else
      # This is force_encoded as US-ASCII to avoid any overlapping
      # byte related issues that could arise from mis-interpreting a
      # random extra byte as part of a multi-byte UTF-8 character
      str_copy.force_encoding("US-ASCII")

      str_copy.encode!("UTF-8",
                       :invalid => :replace,
                       :undef => :replace,
                       :replace => DEFAULT_INVALID_CHAR)
    end
  end

end
end
end
end
