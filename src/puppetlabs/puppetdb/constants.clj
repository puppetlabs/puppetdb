(ns puppetlabs.puppetdb.constants)

(def filename-forbidden-characters
  [\/ \u0000 \\ \:])

(def sha1-hex-length
  "The length (in ASCII/UTF-8 bytes) of a SHA1 sum when encoded in hexadecimal."
  40)
