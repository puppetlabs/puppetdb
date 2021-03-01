
(disable-warning
 {:linter :deprecations
  :symbol-matches
  #{#"^#'puppetlabs\.puppetdb\.jdbc/call-with-array-converted-query-rows$"
    #"^#'puppetlabs\.trapperkeeper\.testutils\.logging/atom-appender$"
    #"^#'puppetlabs\.trapperkeeper\.testutils\.logging/atom-logger$"
    #"^#'puppetlabs\.trapperkeeper\.testutils\.logging/logs-matching$"}})
