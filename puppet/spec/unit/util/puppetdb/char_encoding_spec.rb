#!/usr/bin/env rspec
# encoding: UTF-8

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
        subject.utf8_string(str).should == str
      end

      it "should strip invalid chars from non-overlapping latin-1 with a warning" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "a latin-1 string \xd6"
        subject.utf8_string(str).should == "a latin-1 string "
      end

      it "should strip invalid chars and warn if the string is invalid UTF-8" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid utf-8 string \xff"
        subject.utf8_string(str).should == "an invalid utf-8 string "
      end

      it "should return a valid utf-8 string without warning" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string \xc3\x96"
        subject.utf8_string(str).should == str
      end
    end

    describe "on ruby > 1.8", :if => RUBY_VERSION !~ /^1.8/ do
      it "should convert from ascii without a warning" do
        Puppet.expects(:warning).never

        str = "any ascii string".force_encoding('us-ascii')
        subject.utf8_string(str).should == str
      end

      it "should convert from latin-1 without a warning" do
        Puppet.expects(:warning).never

        str = "a latin-1 string \xd6".force_encoding('iso-8859-1')
        subject.utf8_string(str).should == "a latin-1 string Ö"
      end

      # UndefinedConversionError
      it "should replace undefined characters and warn when converting from binary" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid binary string \xff".force_encoding('binary')
        # \ufffd == unicode replacement character
        subject.utf8_string(str).should == "an invalid binary string \ufffd"
      end

      # InvalidByteSequenceError
      it "should replace undefined characters and warn if the string is invalid UTF-8" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid utf-8 string \xff".force_encoding('utf-8')
        subject.utf8_string(str).should == "an invalid utf-8 string \ufffd"
      end

      it "should leave the string alone if it's valid UTF-8" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string".force_encoding('utf-8')
        subject.utf8_string(str).should == str
      end
    end
  end

end
