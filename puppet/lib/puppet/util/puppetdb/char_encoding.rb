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


  def self.utf8_string(str)
    if RUBY_VERSION =~ /^1.8/
      # Ruby 1.8 doesn't have String#encode and related methods, and there
      #  appears to be a bug in iconv that will interpret some byte sequences
      #  as 6-byte characters.  Thus, we are forced to resort to some unfortunate
      #  manual chicanery.
      warn_if_changed(str, ruby18_clean_utf8(str))
    elsif str.encoding == Encoding::UTF_8
      # If we get here, we're in ruby 1.9+, so we have the string encoding methods
      #  available.  However, just because  a ruby String object is already
      #  marked as UTF-8, that doesn't guarantee that its contents are actually
      #  valid; and if you call ruby's ".encode" method with an encoding of
      #  "utf-8" for a String that ruby already believes is UTF-8, ruby
      #  seems to optimize that to be a no-op.  So, we have to do some more
      #  complex handling...

      # If the string already has valid encoding then we're fine.
      return str if str.valid_encoding?

      # If not, we basically have to walk over the characters and replace
      #  them by hand.
      warn_if_changed(str, str.each_char.map { |c| c.valid_encoding? ? c : "\ufffd"}.join)
    else
      # if we get here, we're ruby 1.9 and the current string is *not* encoded
      #  as UTF-8.  Thus we can actually rely on ruby's "encode" method.
      begin
        str.encode('UTF-8')
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

      begin
        fn = '/tmp/puppetdb-invalid-utf8-sequences'

        analyser = EncodingAnalyser.new(fn)
        analyser.original = str
        analyser.converted = converted_str
        analyser.write_summary

        Puppet.warning "See #{fn} for details of last occurence"
      rescue => e
        Puppet.warning "EncodingAnalyser failed because of #{e.class.name}"
        Puppet.warning e
      end
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

  # This EncodingAnalyser is supposed to be used by this module only it tries
  # to break the string into smaller pieces in order to make line-wise diffing
  # possible.
  #
  # In most circumstances the diff should give enough context to understand the
  # issue better.
  class EncodingAnalyser
    class String
      attr_reader :filename

      def initialize(string, base_filename, type)
        @string   = string
        @type     = type
        @filename = "#{base_filename}-#{@type}"
        @errors   = []
      end

      def encoding
        @string.encoding
      end

      def size
        @string.size
      end

      def write
        File.open(@filename, 'w') do |file|
          file.flock(File::LOCK_EX)
          file.puts split
          file.chmod(0666)
          file.flock(File::LOCK_UN)
        end
        filename
      end

      def errors
        if @errors.any?
          ["Errors in #{@type} string", @errors].join("\n")
        else
          ''
        end
      end

      private

      # Splitting the string does the following two things:
      #   - split hashes and array that are values from their keys
      #   - split lists of hashes into new lines
      def split(second_try = false)
        begin
          @string.gsub(/(['"]:)({|\[)/, "\\1\n\\2").gsub(/(}|\]),({|\[)/, "\\1,\n\\2")
        rescue ArgumentError => e
          if second_try
            @errors << "The #{@type} string cannot be split into diffable chunks."
            @string
          else
            @string.encode!(@string.encoding, :invalid => :replace, :undef => :replace, :replace => "?")
            @errors << "Invalid bytes encountered. These have been replaced with '?'."
            @errors << "This should be enough to produce a helpful diff."
            split(:second_try)
          end
        end
      end
    end

    def initialize(filename)
      @filename = filename
    end

    def original= string
      @original = String.new(string, @filename, 'original')
    end

    def converted= string
      @converted = String.new(string, @filename, 'converted')
    end

    def write_summary
      File.open(@filename, 'w') do |file|
        file.flock(File::LOCK_EX)
        file.puts diff_header

        orig_fn = @original.write
        conv_fn = @converted.write

        file.puts "diff of #{orig_fn} and #{conv_fn}"
        file.puts ""
        file.puts `diff -u5 #{orig_fn} #{conv_fn}`

        [@original, @converted].each do |str|
          if str.errors.size > 0
            file.puts ""
            file.puts str.errors
          end
        end

        file.puts diff_footer

        file.chmod(0666)
        file.flock(File::LOCK_UN)
      end
    end

    private

    def diff_header
      <<-EOSTRING
Ignoring invalid UTF-8 byte sequences in data to be sent to PuppetDB
These are the details of the last occurence

str: #{@original.encoding} #{@original.size}
converted_str: #{@converted.encoding} #{@converted.size}

      EOSTRING
    end

    def diff_footer
      <<-EOSTRING

The other full catalog-json is stored with some line-breaks in

  #{@original.filename}
  #{@converted.filename}

Your faithful employee,
puppetdb-terminus
      EOSTRING
    end
  end


end
end
end
end
