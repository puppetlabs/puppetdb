
;; Alphabetically ordered by :linter then :if-inside-macroexpansion-of

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

;; This is exactly the same as the one built in to eastwood, except
;; that we raised the :within-depth limit to accomodate some
;; constructs like those in query-eng.engine.
(disable-warning
 {:linter :suspicious-expression
  ;; specifically, those detected in function suspicious-macro-invocations
  :for-macro 'clojure.core/and
  :if-inside-macroexpansion-of #{'clojure.core.match/match}
  :within-depth 43
  :reason "Many clojure.core.match/match macro expansions contain expressions of the form (and expr).  This is normal, and probably simplifies the definition of match."})
