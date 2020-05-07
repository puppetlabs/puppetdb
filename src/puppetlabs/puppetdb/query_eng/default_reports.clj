(ns puppetlabs.puppetdb.query-eng.default-reports
  (:require
   [clojure.core.match :as cm]
   [clojure.set :as set]
   [clojure.string :as str]
   [puppetlabs.puppetdb.query-eng.engine :refer [user-name->query-rec-name]]))

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

   [[]] false
   [[(op :guard #{"=" ">" "<" "<=" ">=" "~" "~>"}) field value]] (= field "type")
   [[(op :guard #{"and" "or"}) & exprs]] (boolean (some mentions-report-type? exprs))
   [["not" expr]] (mentions-report-type? expr)
   ;; FIXME: unless we allow (null? "type") then maybe -> true?
   [["null?" field]] false
   ;; FIXME: maybe -- depends on what we do with the type type
   [["in" field ["array" & values]]] (= field "type")

   ;; This needs to precede (or maybe just obviates?) the "from" below.
   ;; New-style explicit subquery
   [["in" fields ["from" entity ["extract" sub-fields expr & page-order-opts]]]]
   false

   ;; Old-style select_foo subquery.  Suppose we could take this as an
   ;; opportunity to just rewrite it new-style and recurse, which
   ;; might allow simplifications in the later plan passes.
   [["in" fields ["extract" sub-fields [select-entity expr & page-order-opts]]]]
   false

   [["from" entity ["extract" fields expr & page-order-opts]]]
   false

   ;; This "from" formulation (without an extract) is only valid at
   ;; the top level.
   ;; FIXME: Is this just unnecessary given the cases above?
   [["from" entity expr & page-order-opts]]
   false

   [["subquery" entity expr]] false

   :else (throw
          ;; FIXME: better info
          (ex-info (str "Unrecognized ast clause: " (pr-str ast))
                   {:kind ::unrecognized-ast-syntax}))))

(defn qrec-tables
  [qrec]
  (apply set/union
         (:source-tables qrec)
         (for [[name info] (:projections qrec)]
           (:join-deps info))))

(defn qrec-involving-reports? [qrec]
  (boolean (:reports (qrec-tables qrec))))

(defn maybe-restrict-expr-to-agent-reports
  [qrec expr]
  (if (or (not (qrec-involving-reports? qrec))
          (mentions-report-type? expr))
    expr
    ;; FIXME: order?
    (if (seq expr)
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

(declare maybe-add-agent-report-filter-to-query)

(defn maybe-add-agent-report-filter-to-subqueries
  "Returns the given ast expression with the filter clauses of any
  subqueries adjusted to only include reports with a type of agent if
  the filter doesn't already mention the report type."
  [ast]
  ;; This just needs to be good enough to accurately find/handle the
  ;; relevant contexts.
  (cm/match
   [ast]

   [[]] ast
   [[(op :guard #{"=" ">" "<" "<=" ">=" "~" "~>"}) field value]] ast
   [["null?" field]] ast

   [[(op :guard #{"and" "or"})  & exprs]]
   (mapv maybe-add-agent-report-filter-to-subqueries exprs)

   [["not" expr]] (mapv maybe-add-agent-report-filter-to-subqueries expr)

   [["in" field ["array" & values]]] ast

   ;; This needs to precede (or maybe just obviates?) the "from" below.
   ;; New-style explicit subquery
   [["in" fields
     ["from" entity
      ["extract" sub-fields expr & page-order-opts]]]]
   (let [qrec (qrec-for-entity entity)
         [_ expr] (maybe-add-agent-report-filter-to-query qrec expr)]
     `["in" ~fields
       ["from" ~entity
        ["extract" ~fields ~expr ~@page-order-opts]]])
   
   ;; Old-style select_foo subquery.  Suppose we could take this as an
   ;; opportunity to just rewrite it new-style and recurse, which
   ;; might allow simplifications in the later plan passes.
   [["in" fields
     ["extract" sub-fields
      [select-entity expr & page-order-opts]]]]
   (let [qrec (qrec-for-select-entity select-entity)
         [_ expr] (maybe-add-agent-report-filter-to-query qrec expr)]
     `["in" fields
       ["extract" fields
        [~select-entity ~expr ~@page-order-opts]]])

   ;; FIXME: Is this just unnecessary given the cases above?
   [["from" entity expr & page-order-opts]]
   (let [qrec (qrec-for-entity entity)
         [_ expr] (maybe-add-agent-report-filter-to-query qrec expr)]
     `["from" ~entity ~expr ~@page-order-opts])

   ;; This "from" formulation (without an extract) is only valid at
   ;; the top level (FIXME: remove if we end up handling this
   ;; case in the parent caller).
   [["from" entity ["extract" fields expr & page-order-opts]]]
   (let [qrec (qrec-for-entity entity)
         [_ expr] (maybe-add-agent-report-filter-to-query qrec expr)]
     `["from" ~entity
       ["extract" ~fields ~expr ~@page-order-opts]])

   [["subquery" entity expr]]
   (let [qrec (qrec-for-entity entity)
         [_ expr] (maybe-add-agent-report-filter-to-query qrec expr)]
     `["subquery" ~entity ~expr])

   :else (throw
          ;; FIXME: better info
          (ex-info (str "Unrecognized ast clause: " (pr-str ast))
                   {:kind ::unrecognized-ast-syntax}))))

(defn maybe-add-agent-report-filter-to-query
  "Returns [qrec ast] after adjusting the top-level filter in the ast
  expression, and filters in any sub-queries to only include reports
  with a type of agent, if the filter doesn't already mention the
  report type."
  [qrec ast]
  ;; This is in no way comprehensive, but it just needs to be good
  ;; enough to accurately find/handle the relevant contexts.
  [qrec (->> (maybe-add-agent-report-filter-to-subqueries ast)
             (maybe-restrict-expr-to-agent-reports qrec))])
