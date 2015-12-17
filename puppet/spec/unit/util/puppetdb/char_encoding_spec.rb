#!/usr/bin/env rspec
# encoding: utf-8

require 'spec_helper'

require 'puppet/util/puppetdb/char_encoding'

describe Puppet::Util::Puppetdb::CharEncoding do
  describe "#utf8_string" do
    describe "on ruby >= 1.9" do
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

  describe "on ruby >= 1.9" do

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
