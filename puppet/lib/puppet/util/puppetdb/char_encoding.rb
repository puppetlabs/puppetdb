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
  def self.all_indexes_of_char(str, char)
    (0..str.length).find_all{ |i| str[i] == char}
  end

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
    bad_char_indexes = all_indexes_of_char(str, DEFAULT_INVALID_CHAR)
    if bad_char_indexes.empty?
      str
    else
      Puppet.warning "#{error_context_str} ignoring invalid UTF-8 byte sequences in data to be sent to PuppetDB, see debug logging for more info"
      if Puppet.settings[:log_level] == "debug"
        Puppet.debug error_context_str + "\n" + error_char_context(str, bad_char_indexes).join("\n")
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
    if RUBY_VERSION =~ /^1.8/
      # Ruby 1.8 doesn't have String#encode and related methods, and there
      #  appears to be a bug in iconv that will interpret some byte sequences
      #  as 6-byte characters.  Thus, we are forced to resort to some unfortunate
      #  manual chicanery.
      warn_if_changed(str, ruby18_clean_utf8(str))
    else
      begin
        coerce_to_utf8(str, error_context_str)
      rescue Encoding::InvalidByteSequenceError, Encoding::UndefinedConversionError => e
          # If we got an exception, the string is either invalid or not
          # convertible to UTF-8, so drop those bytes.

        warn_if_changed(str, str.encode('UTF-8', :invalid => :replace, :undef => :replace))
      end
    end
  end

  # @api private
  def self.warn_if_changed(str, converted_str)
    if converted_str != str
      Puppet.warning "Ignoring invalid UTF-8 byte sequences in data to be sent to PuppetDB"
    end
    converted_str
  end

  # @api private
  def self.ruby18_clean_utf8(str)
    #iconv_to_utf8(str)
    #ruby18_manually_clean_utf8(str)

    # So, we've tried doing this UTF8 cleaning for ruby 1.8 a few different
    # ways.  Doing it via IConv, we don't do a good job of handling characters
    # whose codepoints would exceed the legal maximum for UTF-8.  Doing it via
    # our manual scrubbing process is slower and doesn't catch overlong
    # encodings.  Since this code really shouldn't even exist in the first place
    # we've decided to simply compose the two scrubbing methods for now, rather
    # than trying to add detection of overlong encodings.  It'd be a non-trivial
    # chunk of code, and it'd have to do a lot of bitwise arithmetic (which Ruby
    # is not blazingly fast at).
    ruby18_manually_clean_utf8(iconv_to_utf8(str))
  end


  # @todo we're not using this anymore, but I wanted to leave it around
  #  for a little while just to make sure that the new code pans out.
  # @api private
  def self.iconv_to_utf8(str)
    iconv = Iconv.new('UTF-8//IGNORE', 'UTF-8')

    # http://po-ru.com/diary/fixing-invalid-utf-8-in-ruby-revisited/
    iconv.iconv(str + " ")[0..-2]
  end

  # @api private
  def self.get_char_len(byte)
    Utf8CharLens[byte]
  end

  # Manually cleans a string by stripping any byte sequences that are
  # not valid UTF-8 characters.  If you'd prefer for the invalid bytes to be
  # replaced with the unicode replacement character rather than being stripped,
  # you may pass `false` for the optional second parameter (`strip`, which
  # defaults to `true`).
  #
  # @api private
  def self.ruby18_manually_clean_utf8(str, strip = true)

    # This is a hack to allow this code to work with either ruby 1.8 or 1.9,
    # which is useful for debugging and benchmarking.  For more info see the
    # comments in the #get_byte method below.
    @has_get_byte = str.respond_to?(:getbyte)


    i = 0
    len = str.length
    result = ""

    while i < len
      byte = get_byte(str, i)

      i += 1

      char_len = get_char_len(byte)
      case char_len
      when 0
        result.concat(Utf8ReplacementChar) unless strip
      when 1
        result << byte
      when 2..4
        ruby18_handle_multibyte_char(result, byte, str, i,  char_len, strip)
        i += char_len - 1
      else
        raise Puppet::DevError, "Unhandled UTF8 char length: '#{char_len}'"
      end

    end

    result
  end

  # @api private
  def self.ruby18_handle_multibyte_char(result_str, byte, str, i, char_len, strip = true)
    # keeping an array of bytes for now because we need to do some
    #  bitwise math on them.
    char_additional_bytes = []

    # If we don't have enough bytes left to read the full character, we
    #  put on a replacement character and bail.
    if i + (char_len - 1) > str.length
      result_str.concat(Utf8ReplacementChar) unless strip
      return
    end

    # we've already read the first byte, so we need to set up a range
    #  from 0 to (n-2); e.g. if it's a 2-byte char, we will have a range
    #  from 0 to 0 which will result in reading 1 more byte
    (0..char_len - 2).each do |x|
      char_additional_bytes << get_byte(str, i + x)
    end

    if (is_valid_multibyte_suffix(byte, char_additional_bytes))
      result_str << byte
      result_str.concat(char_additional_bytes.pack("c*"))
    else
      result_str.concat(Utf8ReplacementChar) unless strip
    end
  end

  # @api private
  def self.is_valid_multibyte_suffix(byte, additional_bytes)
    # This is heinous, but the UTF-8 spec says that codepoints greater than
    #  0x10FFFF are illegal.  The first character that is over that limit is
    #  0xF490bfbf, so if the first byte is F4 then we have to check for
    #  that condition.
    if byte == 0xF4
      val = additional_bytes.inject(0) { |result, b | (result << 8) + b}
      if val >= 0x90bfbf
        return false
      end
    end
    additional_bytes.all? { |b| ((b & 0xC0) == 0x80) }
  end

  # @api private
  def self.get_byte(str, index)
    # This method is a hack to allow this code to work with either ruby 1.8
    #  or 1.9.  In production this code path should never be exercised by
    #  1.9 because it has a much more sane way to accomplish our goal, but
    #  for testing, it is useful to be able to run the 1.8 codepath in 1.9.
    if @has_get_byte
      str.getbyte(index)
    else
      str[index]
    end
  end

end
end
end
end
