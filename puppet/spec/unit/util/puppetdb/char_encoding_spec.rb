#!/usr/bin/env rspec
# encoding: utf-8

require 'spec_helper'

require 'puppet/util/puppetdb/char_encoding'

describe Puppet::Util::Puppetdb::CharEncoding do
  describe "#coerce_to_utf8" do
    describe "on ruby >= 1.9" do
      # UndefinedConversionError
      it "should replace undefined characters and warn when converting from binary" do
        str = "an invalid binary string \xff".force_encoding('binary')

        # \ufffd == unicode replacement character
        subject.coerce_to_utf8(str).should == "an invalid binary string \ufffd"
      end

      # InvalidByteSequenceError
      it "should replace undefined characters and warn if the string is invalid UTF-8" do
        str = "an invalid utf-8 string \xff".force_encoding('utf-8')
        subject.coerce_to_utf8(str).should == "an invalid utf-8 string \ufffd"
      end

      it "should leave the string alone if it's valid UTF-8" do
        str = "a valid utf-8 string".force_encoding('utf-8')
        subject.coerce_to_utf8(str).should == str
      end

      it "should leave the string alone if it's valid UTF-8 with non-ascii characters" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string Ö"
        subject.coerce_to_utf8(str.dup.force_encoding('ASCII-8BIT')).should == str
      end
    end
  end

  describe "on ruby >= 1.9" do
    it "finds first change of character" do
      described_class.first_invalid_char_range("123\ufffd\ufffd\ufffd\ufffd123123123\ufffd\ufffd").should == Range.new(3,6)
      described_class.first_invalid_char_range("1234567").should == nil
      described_class.first_invalid_char_range("123\ufffd4567").should == Range.new(3,3)
    end

    it "gives error context around each bad character" do
      described_class.error_char_context("abc\ufffddef").should ==
        "'abc' followed by 1 invalid/undefined bytes then 'def'"

      described_class.error_char_context("abc\ufffd\ufffd\ufffd\ufffddef").should ==
        "'abc' followed by 4 invalid/undefined bytes then 'def'"

      described_class.error_char_context("abc\ufffddef\ufffdg").should ==
        "'abc' followed by 1 invalid/undefined bytes then 'def'"
    end
  end
end
