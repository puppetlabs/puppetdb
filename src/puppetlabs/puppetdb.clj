(ns puppetlabs.puppetdb)

;;; Notable namespace keys

;; :puppetlabs.puppetdb.known-error? - indicates that the error is
;; expected, e.g. a query or command syntax error, JSON parse error,
;; configuration error, etc.  In general, the stack trace should be
;; considered uninteresting (i.e. only the message is expected to be
;; interesting), but if there's a cause (.getCause) or suppressed
;; exceptions (.getSuppressed) then they may also be interesting (in
;; their entirety).  A cause should only be added intentionally (when
;; constructing the ex-info), but suppressed exceptions may be added
;; indirectly, while unwinding, via for example java's
;; try-with-resources or murphy.
