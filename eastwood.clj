
;; Alphabetically ordered by :linter

(disable-warning
 {:linter :constant-test
  :for-macro 'clojure.core/coll?
  :if-inside-macroexpansion-of #{'puppetlabs.structured-logging.core/maplog}
  :within-depth 2
  :reason "maplog checks the logger type at compile time."})

(disable-warning
 {:linter :deprecations
  :symbol-matches
  #{#"^#'puppetlabs\.puppetdb\.jdbc/call-with-array-converted-query-rows$"
    #"^#'puppetlabs\.trapperkeeper\.testutils\.logging/atom-appender$"
    #"^#'puppetlabs\.trapperkeeper\.testutils\.logging/atom-logger$"
    #"^#'puppetlabs\.trapperkeeper\.testutils\.logging/logs-matching$"}})

(disable-warning
 {:linter :suspicious-expression
  :for-macro 'clojure.core/or
  :if-inside-macroexpansion-of #{'clojure.core.async/alt!!}
  :within-depth 6 ;; determined experimentally
  :reason "alt!! creates one-armed or expressions"})
