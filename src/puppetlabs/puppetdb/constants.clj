(ns puppetlabs.puppetdb.constants)

(def filename-forbidden-characters
  [\/ \u0000 \\ \:])

(def sha1-hex-length
  "The length (in ASCII/UTF-8 bytes) of a SHA1 sum when encoded in hexadecimal."
  40)

;; Use to signal when a change isn't backwards compatible with pdbext ha-sync.
;; Bumping this version number will cause pdbext sync requests to fail with 409s
;; until both pdbs are upgraded and on the same sync version.
(def pdb-sync-ver 1)
