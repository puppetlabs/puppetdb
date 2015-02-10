(ns puppetlabs.puppetdb.anonymizer
  (:require [puppetlabs.puppetdb.reports :as report]
            [puppetlabs.puppetdb.utils :as utils]
            [clojure.string :as str]
            [puppetlabs.kitchensink.core :refer [regexp? boolean? uuid string-contains?]]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.random :refer :all]))

;; Validation functions, for use within pre/post conditions

(defn edge?
  "Returns true if it looks like an edge"
  [edge]
  (and
   (map? edge)
   (contains? edge "source")
   (contains? edge "target")
   (contains? edge "relationship")))

(defn resource-event?
  "Returns true if it looks like a resource event"
  [event]
  (and
   (map? event)
   (contains? event "status")
   (contains? event "timestamp")
   (contains? event "resource_title")
   (contains? event "property")
   (contains? event "message")
   (contains? event "new_value")
   (contains? event "old_value")
   (contains? event "resource_type")))

(defn report?
  "Returns true if it looks like a report"
  [report version]
  ;; Utilise our existing report validation
  (report/validate! version (keywordize-keys report)))

(defn resource?
  "Returns true if it looks like a resource"
  [resource]
  (and
   (map? resource)
   (contains? resource "parameters")
   (contains? resource "exported")
   (contains? resource "tags")
   (contains? resource "title")
   (contains? resource "type")))

(defn catalog?
  "Returns true if it looks like a catalog"
  [catalog]
  ;; I would have liked to have used the catalog/validate validation, however
  ;; it isn't designed for the post-processed format, only the original format
  ;; that the master gives us.
  (and
   (map? catalog)
   (contains? catalog "name")
   (contains? catalog "version")))

;; Rules engine functions

(defn pattern-string?
  "Returns true if the string looks like a pattern"
  [string]
  {:pre [(string? string)]
   :post [(boolean? %)]}
  (boolean (re-find #"^\/.+\/" string)))

(defn pattern->regexp
  "Converts a string pattern of the form: /myregexp/ to a proper regexp"
  [pattern]
  {:pre [(string? pattern) (pattern-string? pattern)]
   :post [(regexp? %)]}
  (re-pattern (.substring pattern 1 (dec (count pattern)))))

(defn matcher-match?
  "Compares a rule matcher against a value, returning true if it's a match."
  [test value]
  {:post [(boolean? %)]}
  (cond
   (string? test) (if (pattern-string? test)
                    (let [pattern (pattern->regexp test)]
                      (boolean (and (not (nil? value)) (re-find pattern value))))
                    (= test value))
   (vector? test) (boolean (some true? (map #(matcher-match? % value) test)))))

(defn rule-match?
  "Given a single rule map, and a context map returns true if the rule matches.

  We perform this test by iterating across all the keys defined in the rule, and
  seeing if the matchers in the value match against the data in the context.

  We'll only return true if single defined matcher is matched."
  [rule context]
  {:pre [(map? rule)
         (map? context)]
   :post [(boolean? %)]}
  (let [rule-context (get rule "context")
        context-keys (keys context)
        rule-keys    (keys rule-context)]
    (every? true?
            (for [k    rule-keys
                  :let [test  (get rule-context k)
                        value (get context k)]]
              (if (and (coll? value) (empty? value))
                false
                (matcher-match? test value))))))

(defn rules-match
  "Cycles through a set of rules, return the value of the :anonymize parameter
  if there is a match"
  [rules context]
  {:pre [(or (coll? rules) (nil? rules))
         (map? context)]
   :post [(boolean? %)]}
  (loop [x rules]
    (if (empty? x)
      ;; Default to returning true if there is no match
      true
      (let [rule (first x)]
        (if (rule-match? rule context)
          (get rule "anonymize")
          (recur (rest x)))))))

;; Functions for anonymizing the final leaf data
(defn anonymize-leaf-value
  "Based on the input value, return an appropriate random replacement"
  [value]
  (cond
   (string? value)            (random-string 30)
   (integer? value)           (rand-int 300)
   (float? value)             (rand)
   (boolean? value)           (random-bool)
   (vector? value)            (vec (map anonymize-leaf-value value))
   (seq? value)               (seq (map anonymize-leaf-value value))
   (map? value)               (zipmap (take (count value)
                                            (repeatedly #(random-string 10)))
                                      (vals (utils/update-vals value (keys value)
                                                               anonymize-leaf-value)))
   (nil? value)               nil
   :else (random-string 30)))

(def anonymize-leaf-memoize
  (memoize
   (fn [ltype value]
     (case ltype
       :node (random-node-name)
       :type (random-type-name)
       :title (random-string 15)
       :parameter-name (random-string-alpha 10)
       :parameter-value (anonymize-leaf-value value)
       :message (random-string 50)
       :file (random-pp-path)
       :line (rand-int 300)
       :transaction_uuid (uuid)
       :fact-name (random-string 15)
       :fact-value (anonymize-leaf-value value)
       :environment (random-string 15)))))

(defn anonymize-leaf
  "Anonymize leaf data, if the context matches a rule"
  [value ltype context config]
  (let [rules      (get config "rules")
        type-rules (get rules (name ltype))]
    ;; Preserve nils and booleans
    (if (or (nil? value) (boolean? value))
      value
      (if (rules-match type-rules context)
        (anonymize-leaf-memoize ltype value)
        value))))

;; Functions for anonymizing data structures

(defn anonymize-reference
  "This anonymizes a reference entry, conditionally anonymizing based on rule"
  [rel context config]
  {:pre  [(string? rel)]
   :post [(= (type %) (type rel))]}
  (let [[_ rel-type rel-title] (re-matches #"(.+)\[(.+)\]" rel)
        ;; here we modify the context, as the anonymization of a reference
        ;; is not about where it appears
        newcontext             {"node"  (get context "node")
                                "title" rel-title
                                "type"  rel-type}
        ;; optionally anonymize each part
        new-type               (anonymize-leaf rel-type :type newcontext config)
        new-title              (anonymize-leaf rel-title :title newcontext config)]
    (str new-type "[" new-title "]")))

(defn anonymize-references
  "Anonymize a collection of references"
  [rels context config]
  {:pre  [(or (coll? rels) (string? rels))]
   :post [(= (type %) (type rels))]}
  (if (coll? rels)
    (vec (map #(anonymize-reference % context config) rels))
    (anonymize-reference rels context config)))

(defn anonymize-aliases
  "Anonymize a collection of aliases"
  [aliases context config]
  {:pre  [(or (coll? aliases) (string? aliases) (nil? aliases))]
   :post [(= (type %) (type aliases))]}
  (if (coll? aliases)
    (vec (map #(anonymize-leaf % :title context config) aliases))
    (if (string? aliases)
      (anonymize-leaf aliases :title context config)
      aliases)))

(defn anonymize-parameter
  "Anonymize a parameter/value pair"
  [parameter context config]
  {:pre  [(coll? parameter)]
   :post [(coll? %)]}
  (let [[key val]  parameter
        newcontext (assoc-in context ["parameter-value"] val)]
    (case key
      ;; Metaparameters are special
      ("stage" "tag")        [key (anonymize-leaf val :title newcontext config)]
      "alias"                [key (anonymize-aliases val newcontext config)]
      ;; References get randomized in a special way
      ("require" "before"
       "notify" "subscribe") [key (anonymize-references val newcontext config)]
       ;; Everything else gets anonymized as per normal
       [(anonymize-leaf key :parameter-name newcontext config)
        (anonymize-leaf val :parameter-value newcontext config)])))

(defn anonymize-parameters
  "Anonymize the parameters keys and values for a resource"
  [parameters context config]
  {:pre  [(map? parameters)]
   :post [(map? %)]}
  (into {} (map #(anonymize-parameter % context config) parameters)))

(defn capitalize-resource-type
  "Converts a downcase resource type to an upcase version such as Foo::Bar"
  [type]
  {:pre  [(string? type)]
   :post [(string? %)]}
  (str/join "::" (map str/capitalize (str/split type #"::"))))

(defn anonymize-tag
  "Anonymize a tag"
  [tag context config]
  {:pre  [(string? tag)]
   :post [(string? %)]}
  (let [newtag     (capitalize-resource-type tag)
        newcontext {"node" (get context "node")
                    "type" newtag}]
    (str/lower-case (anonymize-leaf newtag :type newcontext config))))

(defn anonymize-tags
  "Anonymize a collection of tags"
  [tags context config]
  {:pre  [(coll? tags)]
   :post [(coll? %)]}
  (map #(anonymize-tag % context config) tags))

(defn anonymize-edge
  "Anonymize an edge reference from a catalog"
  [edge context config]
  {:pre  [(edge? edge)]
   :post [(edge? %)]}
  (let [sourcecontext {"node"  (get context "node")
                       "type"  (get-in edge ["source" "type"])
                       "title" (get-in edge ["source" "title"])}
        targetcontext {"node"  (get context "node")
                       "type"  (get-in edge ["target" "type"])
                       "title" (get-in edge ["target" "title"])}]
    (-> edge
        (update-in ["source" "title"] anonymize-leaf :title sourcecontext config)
        (update-in ["source" "type"]  anonymize-leaf :type sourcecontext config)
        (update-in ["target" "title"] anonymize-leaf :title targetcontext config)
        (update-in ["target" "type"]  anonymize-leaf :type targetcontext config))))

(defn anonymize-edges
  "Anonymize a collection of edge references from a catalog"
  [edges context config]
  {:pre  [(coll? edges)]
   :post [(coll? %)]}
  (map #(anonymize-edge % context config) edges))

(defn anonymize-containment-path-element
  "Anonymize a containment path resource reference"
  [element context config]
  {:pre  [(string? element)]
   :post [(string? %)]}
  (cond
   (.isEmpty element) ""
   (string-contains? "[" element) (anonymize-reference element context config)
   :else (anonymize-leaf element :title (assoc context "type" "Class") config)))

(defn anonymize-containment-path
  "Anonymize a collection of containment path resource references from an event"
  [path context config]
  {:pre  [(coll? path)]
   :post [(coll? %)]}
  (map #(anonymize-containment-path-element % context config) path))

(defn update-in-nil
  "Wrapper around update-in that ignores keys with nil"
  [m [k] f & args]
  (if (nil? (get m k))
    m
    (if args
      (apply update-in m [k] f args)
      (apply update-in m [k] f))))

(defn anonymize-resource
  "Anonymize a resource"
  [resource context config]
  {:pre  [(resource? resource)]
   :post [(resource? %)]}
  (let [newcontext {"node"  (get context "node")
                    "title" (get resource "title")
                    "tags"  (get resource "tags")
                    "file"  (get resource "file")
                    "line"  (get resource "line")
                    "type"  (get resource "type")}]
    (-> resource
        (update-in-nil ["file"]       anonymize-leaf :file newcontext config)
        (update-in-nil ["line"]       anonymize-leaf :line newcontext config)
        (update-in     ["parameters"] anonymize-parameters newcontext config)
        (update-in     ["tags"]       anonymize-tags newcontext config)
        (update-in     ["title"]      anonymize-leaf :title newcontext config)
        (update-in     ["type"]       anonymize-leaf :type newcontext config))))

(defn anonymize-resources
  "Anonymize a collection of resources"
  [resources context config]
  {:pre  [(coll? resources)]
   :post [(coll? %)]}
  (map #(anonymize-resource % context config) resources))

(defn anonymize-resource-event
  "Anonymize a resource event from a report"
  [event context config]
  {:pre  [(resource-event? event)]
   :post [(resource-event? %)]}
  (let [newcontext {"node"          (get context "node")
                    "title"         (get event "resource_title")
                    "message"       (get event "message")
                    "property_name" (get event "property")
                    "type"          (get event "resource_type")
                    "file"          (get event "file")
                    "line"          (get event "line")}]
    (-> event
        (update-in ["resource_title"] anonymize-leaf :title newcontext config)
        (update-in ["message"] anonymize-leaf :message newcontext config)
        (update-in ["property"] anonymize-leaf :parameter-name newcontext config)
        (update-in ["new_value"]
                   anonymize-leaf :parameter-value
                   (assoc newcontext :parameter-value (get event "new_value")) config)
        (update-in ["old_value"]
                   anonymize-leaf :parameter-value
                   (assoc newcontext :parameter-value (get event "old_value")) config)
        (update-in ["resource_type"] anonymize-leaf :type newcontext config)
        (update-in-nil ["file"] anonymize-leaf :file newcontext config)
        (update-in-nil ["line"] anonymize-leaf :line newcontext config)
        (update-in-nil ["containment_path"]
                       anonymize-containment-path newcontext config))))

(defn anonymize-resource-events
  "Anonymize a collection of resource events from a report"
  [events context config]
  {:pre  [(coll? events)]
   :post [(coll? %)]}
  (sort-by
   #(mapv % ["timestamp" "resource_type" "resource_title" "property"])
   (map #(anonymize-resource-event % context config) events)))

;; Primary entry points, for anonymizing catalogs and reports

(defn anonymize-catalog
  "Returns an anonymized catalog from an existing catalog"
  [config catalog]
  {:pre  [(catalog? catalog)]
   :post [(catalog? %)]}
  (let [context {"node" (get catalog ["name"])}]
    (-> catalog
        (update-in ["resources"]        anonymize-resources context config)
        (update-in ["edges"]            anonymize-edges context config)
        (update-in ["name"]             anonymize-leaf :node context config)
        (update-in ["transaction_uuid"] anonymize-leaf :transaction_uuid context config)
        (update-in ["environment"] anonymize-leaf :environment context config))))

(defn anonymize-report
  "Anonymize a report"
  [config version report]
  {:pre  [(report? report version)]
   :post [(report? % version)]}
  (let [context {"node" (get report "certname")}]
    (-> report
        (update-in ["certname"]         anonymize-leaf :node context config)
        (update-in ["resource_events"]  anonymize-resource-events context config)
        (update-in ["transaction_uuid"] anonymize-leaf :transaction_uuid context config)
        (update-in ["environment"] anonymize-leaf :environment context config))))

(defn anonymize-fact-values
  "Anonymizes fact names and values"
  [facts context config]
  (reduce-kv (fn [acc k v]
               (assoc acc
                 (anonymize-leaf k :fact-name (assoc context "fact-name" k) config)
                 (anonymize-leaf v :fact-value (assoc context
                                                 "fact-name" k
                                                 "fact-value" v) config)))
             {} facts))

(defn anonymize-facts
  "Anonymize a fact set"
  [config wire-facts]
  (let [context {"node" (get wire-facts "name")}]
    (-> wire-facts
        (update-in ["name"] anonymize-leaf :node context config)
        (update-in ["values"] anonymize-fact-values context config)
        (update-in ["environment"] anonymize-leaf :environment context config))))
