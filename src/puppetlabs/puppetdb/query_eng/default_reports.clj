(ns puppetlabs.puppetdb.query-eng.default-reports
  (:require
   [clojure.core.match :as cm]
   [puppetlabs.i18n.core :refer [trs]]
   [puppetlabs.puppetdb.query-eng.engine :as eng :refer [user-name->query-rec-name]])
  (:import
    (clojure.lang ExceptionInfo)))

(defn is-page-order-opt?
  [page-order-opts]
  (let [valid-opts ["limit" "group_by" "order_by" "offset"]]
    (every? (fn [opt] (some #(= % (first opt)) valid-opts)) page-order-opts)))

(defn mentions-report-type?
  "Returns true if the ast expression mentions a \"type\" field anywhere
  up to, but not including subexpressions."
  [ast]

  ;; This is in no way comprehensive, but it just needs to be good
  ;; enough to accurately find/handle the relevant contexts.

  ;; Expects to only be called when the "type" field is available and
  ;; means the report type.
  (cm/match
   [ast]

   [nil] false
   [[]] false
   [::elide] false
   [[(_op :guard #{"=" ">" "<" "<=" ">=" "~" "~>"}) field _value]] (= field "type")
   [[(_op :guard #{"and" "or"}) & exprs]] (boolean (some mentions-report-type? exprs))
   [["not" expr]] (mentions-report-type? expr)
   [["null?" field _bool]] (= field "type")
   [["in" field ["array" & _values]]] (= field "type")

   ;; New-style explicit subquery
   [["in" _fields ["from" _entity ["extract" _sub-fields _expr] & _page-order-opts]]]
   false

   ;; Old-style select_foo subquery.  Suppose we could take this as an
   ;; opportunity to just rewrite it new-style and recurse, which
   ;; might allow simplifications in the later plan passes.
   [["in" _fields ["extract" _sub-fields [_select-entity _expr & _page-order-opts]]]]
   false

   [["subquery" _entity _expr]] false

   :else (throw
          (ex-info "Unrecognized ast clause."
                   {:kind ::unrecognized-ast-syntax
                    :clause ast}))))

(defn qrec-tables
  [qrec]
  (:source-tables qrec))

(defn qrec-involving-reports? [qrec]
  (boolean (:reports (qrec-tables qrec))))

(defn maybe-restrict-expr-to-agent-reports
  [qrec expr]
  (if (or (not (qrec-involving-reports? qrec))
          (mentions-report-type? expr))
    expr
    (if (and (not= ::elide expr) (seq expr))
      ["and" expr ["=" "type" "agent"]]
      ["=" "type" "agent"])))

(defn qrec-for-entity
  [name]
  {:pre [(string? name)]}
  (user-name->query-rec-name (str "select_" name)))

(defn qrec-for-select-entity
  [name]
  {:pre [(string? name)]}
  (user-name->query-rec-name name))

(declare maybe-add-agent-report-filter)

(defn maybe-add-agent-report-filter-to-subqueries
  "Returns the given ast expression with the filter clauses of any
  subqueries adjusted to only include reports with a type of agent if
  the filter doesn't already mention the report type."
  [ast]
  ;; This just needs to be good enough to accurately find/handle the
  ;; relevant contexts.
  (cm/match
   [ast]

   [nil] ast
   [[]] ast
   [::elide] ast
   [[(_op :guard #{"=" ">" "<" "<=" ">=" "~" "~>"}) _field _value]] ast
   [["null?" _field _bool]] ast

   [[(op :guard #{"and" "or"})  & exprs]]
   (let [exprs (mapv maybe-add-agent-report-filter-to-subqueries exprs)]
     `[~op ~@exprs])

   [["not" expr]]
   (let [expr (maybe-add-agent-report-filter-to-subqueries expr)]
     `["not" ~expr])

   [["in" _field ["array" & _values]]] ast

   ;; New-style explicit subquery
   [["in" fields
     ["from" entity
      ["extract" sub-fields]
      & (page-order-opts :guard is-page-order-opt?)]]]
   (let [qrec (qrec-for-entity entity)
         [_ expr] (maybe-add-agent-report-filter qrec ::elide)
         extract (if (= ::elide expr)
                   ["extract" sub-fields]
                   ["extract" sub-fields expr])]
     `["in" ~fields
       ["from" ~entity
        ~extract
        ~@page-order-opts]])

   [["in" fields
     ["from" entity
      ["extract" sub-fields expr]
      & (page-order-opts :guard is-page-order-opt?)]]]
   (let [qrec (qrec-for-entity entity)
         [_ expr] (maybe-add-agent-report-filter qrec expr)]
     `["in" ~fields
       ["from" ~entity
        ["extract" ~sub-fields ~expr]
        ~@page-order-opts]])

   ;; Old-style select_foo subquery.  Suppose we could take this as an
   ;; opportunity to just rewrite it new-style and recurse, which
   ;; might allow simplifications in the later plan passes.
   [["in" fields
     ["extract" sub-fields
      [select-entity & (page-order-opts :guard is-page-order-opt?)]]]]
   (let [qrec (qrec-for-select-entity select-entity)
         ;; elide is not a seq, so it'll get entirely replaced by the filter
         ;; if necessary, otherwise, it'll be removed by an AST walk in the
         ;; query engine
         [_ expr] (maybe-add-agent-report-filter qrec ::elide)
         select (if (= ::elide expr)
                  `[~select-entity ~@page-order-opts]
                  `[~select-entity ~expr ~@page-order-opts])]
     `["in" ~fields
       ["extract" ~sub-fields
        ~select]])

   [["in" fields
     ["extract" sub-fields
      [select-entity expr & page-order-opts]]]]
   (let [qrec (qrec-for-select-entity select-entity)
         [_ expr] (maybe-add-agent-report-filter qrec expr)]
     `["in" ~fields
       ["extract" ~sub-fields
        [~select-entity ~expr ~@page-order-opts]]])

   [["subquery" entity expr]]
   (let [qrec (qrec-for-entity entity)
         [_ expr] (maybe-add-agent-report-filter qrec expr)]
     `["subquery" ~entity ~expr])

   :else (throw
          (ex-info "Unrecognized ast clause."
                   {:kind ::unrecognized-ast-syntax
                    :clause ast}))))

(defn maybe-add-agent-report-filter
  "Returns [qrec ast] after adjusting the top-level filter in the ast
  expression, and filters in any sub-queries to only include reports
  with a type of agent, if the filter doesn't already mention the
  report type."
  [qrec ast]
  ;; This is in no way comprehensive, but it just needs to be good
  ;; enough to accurately find/handle the relevant contexts.
  [qrec (->> (maybe-add-agent-report-filter-to-subqueries ast)
             (maybe-restrict-expr-to-agent-reports qrec))])

;; Public

(defn maybe-add-agent-report-filter-to-query
  [qrec ast]
  (try
    (cm/match
      [ast]
      ;; Top level 'extract' has had the preceding 'from' removed and it
      ;; only valid at the very top level of the query, not in subqueries
      [["extract" fields & (page-order-opts :guard is-page-order-opt?)]]
      (let [[_ expr] (maybe-add-agent-report-filter qrec ::elide)]
        (if (= ::elide expr)
          `["extract" ~fields ~@page-order-opts]
          `["extract" ~fields ~expr ~@page-order-opts]))

    [["extract" fields expr & page-order-opts]]
    (let [[_ expr] (maybe-add-agent-report-filter qrec expr)]
      `["extract" ~fields ~expr ~@page-order-opts])

    :else (second (maybe-add-agent-report-filter qrec ast)))
    (catch ExceptionInfo e
      (let [data (ex-data e)
            failed-ast-clause (:clause data)]
      (when (not= ::unrecognized-ast-syntax (:kind data))
        (throw e))
      (throw
        (ex-info (trs "Unrecognized ast clause {0} in ast query {1}" (pr-str failed-ast-clause) ast)
                 {:kind ::unrecognized-ast-syntax}))))))
