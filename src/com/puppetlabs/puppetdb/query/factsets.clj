(ns com.puppetlabs.puppetdb.query.factsets
  (:require [com.puppetlabs.puppetdb.query-eng :as qe]
            [clojure.string :as str]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.zip :as zip]
            [com.puppetlabs.cheshire :as json]))

(defn create-certname-pred [rows]
  (let [certname (:certname (first rows))]
    (fn [row]
      (= certname (:certname row)))))

(defn maybe-num-string? [^String k]
  (case (.charAt k 0)
    (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9) true
    false))

(defn str->num [^String s]
  (when (maybe-num-string? s)
    (try
      (Long/valueOf s)
      (catch Exception e
        nil))))

(defn int-map->vector [node]
  (when (map? node)
    (let [int-keys (keys node)]
      (when (every? integer? int-keys)
        (mapv node (sort int-keys))))))


(defn int-maps->vectors [facts]
  (:node (zip/post-order-transform (zip/tree-zipper facts)
                                   [int-map->vector])))

(defn unescape-string [^String s]
  (if (= \" (.charAt s 0))
    (subs s 1 (dec (.length s)))
    s))

(defn unencode-path-segment [^String s]
  (if-let [num (str->num s)]
    num
    (unescape-string s)))

(defn recreate-fact-path [acc {:keys [path value]}]
  (let [split-path (mapv unencode-path-segment (str/split path #"#~"))]
    (assoc-in acc split-path value)))

(defn collapse-facts [certname-rows]
  (let [first-row (first certname-rows)
        facts (reduce recreate-fact-path {} certname-rows)]
    (assoc (select-keys first-row [:certname :environment :timestamp])
      :facts (int-maps->vectors facts))))

(defn collapsed-fact-seq [rows]
  (when (seq rows)
    (let [[certname-facts more-rows] (split-with (create-certname-pred rows) rows)]
      (cons (collapse-facts certname-facts)
            (lazy-seq (collapsed-fact-seq more-rows))))))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (paging/validate-order-by! (map keyword (keys query/fact-columns)) paging-options)
  (case version
    (:v2 :v3)
    (throw (IllegalArgumentException. "Factset endpoint is only availble for v4"))

    (qe/compile-user-query->sql
     qe/facts-query query paging-options)))
