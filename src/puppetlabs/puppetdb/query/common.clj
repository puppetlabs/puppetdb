(ns puppetlabs.puppetdb.query.common)

;; A macro so that if nothing else clojure.test ERROR message
;; file/line numbers will be right (only reports first stack entry).
(defmacro bad-query-ex [msg]
  `(ex-info ~msg {:kind :puppetlabs.puppetdb.query/invalid
                  :puppetlabs.puppetdb/known-error? true}))
