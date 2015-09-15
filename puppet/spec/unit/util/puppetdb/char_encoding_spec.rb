#!/usr/bin/env rspec
# encoding: utf-8

require 'spec_helper'

require 'puppet/util/puppetdb/char_encoding'

describe Puppet::Util::Puppetdb::CharEncoding do
  describe "#ruby_18_clean_utf8", :if => RUBY_VERSION =~ /^1.8/ do

    def test_utf8_clean(in_bytes, expected_bytes)
      instr = in_bytes.pack('c*')
      out = described_class.ruby18_clean_utf8(instr)
      out.should == expected_bytes.pack('c*')
    end


    it "should recognize (and not modify) valid multi-byte characters" do
      in_bytes = [0xE2, 0x9B, 0x87]
      expected_bytes = [0xE2, 0x9B, 0x87]
      test_utf8_clean(in_bytes, expected_bytes)
    end

    Utf8ReplacementChar = [0xEF, 0xBF, 0xBD]
    it "should strip invalid UTF-8 characters from an invalid multi-byte sequence" do
      in_bytes = [0xE2, 0xCB, 0x87]
      test_utf8_clean(in_bytes, [0xCB, 0x87])
    end

    it "should strip incomplete multi-byte characters" do
      in_bytes = [0xE2, 0x9B]
      test_utf8_clean(in_bytes, [])
    end

    it "should replace invalid characters with the unicode replacement character" do
      # This is related to ticket #14873; our utf8_string code for 1.9 is being
      #  much more aggressive about replacing bytes with the unicode replacement char;
      #  it appears to be more correct, as the way that the 1.8/IConv approach
      #  was handling it was causing certain strings to decode differently in
      #  clojure, thus causing checksum errors.
      in_bytes = [0x21, 0x7F, 0xFD, 0x80, 0xBD, 0xBB, 0xB6, 0xA1]
      expected_bytes = [0x21, 0x7F]
      test_utf8_clean(in_bytes, expected_bytes)
    end

    # A multi-byte sequence beginning with any of the following bytes is
    # illegal.  For more info, see http://en.wikipedia.org/wiki/UTF-8
    [[[0xC0, 0xC1], 2],
     [[0xF5, 0xF6, 0xF7], 4],
     [[0xF8, 0xF9, 0xFA, 0xFB], 5],
     [[0xFC, 0xFD, 0xFE, 0xFF], 6]].each do |bytes, num_bytes|
      bytes.each do |first_byte|
        it "should strip the invalid bytes from a #{num_bytes}-byte character starting with 0x#{first_byte.to_s(16)}" do
          in_bytes = [first_byte]
          (num_bytes - 1).times { in_bytes << 0x80 }
          test_utf8_clean(in_bytes, [])
        end
      end
    end

    context "when dealing with multi-byte sequences beginning with 0xF4" do
      it "should accept characters that are below the 0x10FFFF limit of Unicode" do
        in_bytes = [0xF4, 0x8f, 0xbf, 0xbf]
        expected_bytes = [0xF4, 0x8f, 0xbf, 0xbf]
        test_utf8_clean(in_bytes, expected_bytes)
      end

      it "should reject characters that are above the 0x10FFFF limit of Unicode" do
        in_bytes = [0xF4, 0x90, 0xbf, 0xbf]
        test_utf8_clean(in_bytes, [])
      end
    end
  end


  describe "#utf8_string" do
    describe "on ruby 1.8", :if => RUBY_VERSION =~ /^1.8/ do
      it "should convert from ascii without a warning" do
        Puppet.expects(:warning).never

        str = "any ascii string"
        subject.utf8_string(str, nil).should == str
      end

      it "should strip invalid chars from non-overlapping latin-1 with a warning" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "a latin-1 string \xd6"
        subject.utf8_string(str, nil).should == "a latin-1 string "
      end

      it "should strip invalid chars and warn if the string is invalid UTF-8" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid utf-8 string \xff"
        subject.utf8_string(str, nil).should == "an invalid utf-8 string "
      end

      it "should return a valid utf-8 string without warning" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string \xc3\x96"
        subject.utf8_string(str, nil).should == str
      end
    end

    describe "on ruby > 1.8", :if => RUBY_VERSION !~ /^1.8/ do
      it "should convert from ascii without a warning" do
        Puppet.expects(:warning).never

        str = "any ascii string".force_encoding('us-ascii')
        subject.utf8_string(str, nil).should == str
      end

      it "should convert from latin-1 without a warning" do
        Puppet.expects(:warning).never

        str = "a latin-1 string Ö".force_encoding('ASCII-8BIT')
        subject.utf8_string(str, nil).should == "a latin-1 string Ö"
      end

      # UndefinedConversionError
      it "should replace undefined characters and warn when converting from binary" do
        Puppet.expects(:warning).with {|msg| msg =~ /Error with command ignoring invalid UTF-8 byte sequences/}

        str = "an invalid binary string \xff".force_encoding('binary')
        # \ufffd == unicode replacement character
        subject.utf8_string(str, "Error with command").should == "an invalid binary string \ufffd"
      end

      # InvalidByteSequenceError
      it "should replace undefined characters and warn if the string is invalid UTF-8" do
        Puppet.expects(:warning).with {|msg| msg =~ /Error with command ignoring invalid UTF-8 byte sequences/}

        str = "an invalid utf-8 string \xff".force_encoding('utf-8')
        subject.utf8_string(str, "Error with command").should == "an invalid utf-8 string \ufffd"
      end

      it "should leave the string alone if it's valid UTF-8" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string".force_encoding('utf-8')
        subject.utf8_string(str, nil).should == str
      end

      it "should leave the string alone if it's valid UTF-8 with non-ascii characters" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string Ö"
        subject.utf8_string(str.dup.force_encoding('ASCII-8BIT'), nil).should == str
      end

      describe "Debug log testing of bad data" do
        let!(:existing_log_level){ Puppet[:log_level]}

        before :each do
          Puppet[:log_level] = "debug"
        end

        after :each do
          Puppet[:log_level] = "notice"
        end

        it "should emit a warning and debug messages when bad characters are found" do
          Puppet[:log_level] = "debug"
          Puppet.expects(:warning).with {|msg| msg =~ /Error encoding a 'replace facts' command for host 'foo.com' ignoring invalid/}
          Puppet.expects(:debug).with do |msg|
            msg =~ /Error encoding a 'replace facts' command for host 'foo.com'/ &&
            msg =~ /'some valid string' followed by 1 invalid\/undefined bytes then ''/
          end

          # This will create a UTF-8 string literal, then switch to ASCII-8Bit when the bad
          # bytes are concated on below
          str = "some valid string" << [192].pack('c*')
          subject.utf8_string(str, "Error encoding a 'replace facts' command for host 'foo.com'").should == "some valid string\ufffd"
        end
      end

      it "should emit a warning and no debug messages" do
        Puppet.expects(:warning).with {|msg| msg =~ /Error on replace catalog ignoring invalid UTF-8 byte sequences/}
        Puppet.expects(:debug).never
        str = "some valid string" << [192].pack('c*')
        subject.utf8_string(str, "Error on replace catalog").should == "some valid string\ufffd"
      end
    end
  end

  describe "on ruby > 1.8", :if => RUBY_VERSION !~ /^1.8/ do
    it "finds all index of a given character" do
      described_class.all_indexes_of_char("a\u2192b\u2192c\u2192d\u2192", "\u2192").should == [1, 3, 5, 7]
      described_class.all_indexes_of_char("abcd", "\u2192").should == []
    end

    it "should collapse consecutive integers into ranges" do
      described_class.collapse_ranges((1..5).to_a).should == [1..5]
      described_class.collapse_ranges([]).should == []
      described_class.collapse_ranges([1,2,3,5,7,8,9]).should == [1..3, 5..5, 7..9]
    end

    it "gives error context around each bad character" do
      described_class.error_char_context("abc\ufffddef", [3]).should ==
        ["'abc' followed by 1 invalid/undefined bytes then 'def'"]

      described_class.error_char_context("abc\ufffd\ufffd\ufffd\ufffddef", [3,4,5,6]).should ==
        ["'abc' followed by 4 invalid/undefined bytes then 'def'"]

      described_class.error_char_context("abc\ufffddef\ufffdg", [3, 7]).should ==
        ["'abc' followed by 1 invalid/undefined bytes then 'def'",
         "'def' followed by 1 invalid/undefined bytes then 'g'"]
    end
  end
end
