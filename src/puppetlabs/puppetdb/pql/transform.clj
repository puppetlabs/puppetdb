(ns puppetlabs.puppetdb.pql.transform
  (:require [clojure.string :as str]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.zip :as zip]
            [clojure.core.match :as cm]))

(defn paging-clause?
  [v]
  (contains? #{"limit" "offset" "order_by"} (first v)))

(defn strip-and-move-groupedfields
  [v]
  (let [result (zip/post-order-visit
                 (zip/tree-zipper v)
                 #{}
                 [(fn [node state]
                    (cm/match node
                              [:groupedfield field]
                              {:node field :state (conj state field)}

                              :else nil))
                  (fn  [node state]
                    (when (seq state)
                      (cm/match node
                                ["extract" & _]
                                {:node (conj node (vec (cons "group_by" state)))}

                                :else nil)))])]
    (vec (:node result))))

(defn slurp-expr->extract
  [clauses]
  (let [paging-groups (group-by paging-clause? clauses)
        paging-clauses (get paging-groups true)
        other-clauses (get paging-groups false)
        result (if (and (= (ffirst other-clauses) "extract") (second other-clauses))
                 (cons (vec (concat (first other-clauses) (rest other-clauses)))
                       (vec paging-clauses))
                 clauses)]
    (-> result
        strip-and-move-groupedfields)))

(defn transform-from
  [entity & args]
  (vec (concat ["from" entity] (slurp-expr->extract args))))

(defn transform-subquery
  ([entity]
   ["subquery" entity])
  ([entity arg2]
   ["subquery" entity arg2]))

(defn assume-groupedfield-in-function-case
  [args]
  (if (some #(= (first %) "function") args)
    (for [arg args]
      (if (not (contains? #{"function" :groupedfield} (first arg)))
        [:groupedfield arg]
        arg))
    args))

(defn transform-extract
  [& args]
  ["extract" (vec (assume-groupedfield-in-function-case args))])

(defn transform-expr-or
  ;; Single arg? collapse
  ([data] data)
  ;; Multiple args? turn it into an or statement
  ([data & args] (vec (concat ["or" data] args))))

(defn transform-expr-and
  ;; Single arg? collapse
  ([data] data)
  ;; Multiple args? turn it into an and statement
  ([data & args] (vec (concat ["and" data] args))))

(defn transform-expr-not
  ;; Single arg? Just collapse the :expr-not and pass back the data,
  ;; closing the nesting.
  ([data] data)
  ;; Two args? This means :not [data] so convert it into a "not"
  ([_ data] ["not" data]))

(defn transform-function
  [entity args]
  (vec (concat ["function" entity] args)))

(defn transform-condexpression
  [a b c]
  [b a c])

(defn transform-condexpnull
  [entity type]
  ["null?" entity
   (case (first type)
     :condisnull true
     :condisnotnull false)])

(defn transform-groupedlist
  [& args]
  (vec args))

(defn transform-groupedliterallist
  [& args]
  ["array" args])

(defn transform-sqstring
  [s]
  ;; Un-escape any escaped single quotes
  (str/replace s #"\\'" "'"))

(defn transform-dqstring
  [s]
  ;; For now we just parse the contents using the JSON decoder
  (json/parse-string (str "\"" s "\"")))

(defn transform-boolean
  [bool]
  (case (first bool)
    :true true
    :false false))

(defn transform-integer
  ([int]
   (Integer. int))
  ([neg int]
   (- (Integer. int))))

(defn transform-real
  [& args]
  (Double. (apply str args)))

(defn transform-exp
  ([int]
   (str "E" int))
  ([mod int]
   (str "E" mod int)))

(defn transform-limit
  [arg]
  ["limit" arg])

(defn transform-offset
  [arg]
  ["offset" arg])

(defn transform-orderby
  [& args]
  ["order_by"
   (vec (for [arg args]
          (if (= 2 (count arg))
            (second arg)
            (vec (rest arg)))))])

(def transform-specification
  {:from               transform-from
   :subquery           transform-subquery
   :extract            transform-extract
   :expr-or            transform-expr-or
   :expr-and           transform-expr-and
   :expr-not           transform-expr-not
   :function           transform-function
   :condexpression     transform-condexpression
   :condexpnull        transform-condexpnull
   :groupedarglist     transform-groupedlist
   :groupedfieldlist   transform-groupedlist
   :groupedregexplist  transform-groupedlist
   :groupedliterallist transform-groupedliterallist
   :sqstring           transform-sqstring
   :dqstring           transform-dqstring
   :boolean            transform-boolean
   :integer            transform-integer
   :real               transform-real
   :exp                transform-exp
   :limit              transform-limit
   :offset             transform-offset
   :orderby            transform-orderby})
