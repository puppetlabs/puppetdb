(ns puppetlabs.puppetdb.query-eng.parse
  "AST parsing"
  (:require
   [clojure.string :as str]))

(defn maybe-strip-escaped-quotes
  [s]
  (if (and (> (count s) 1)
           (str/starts-with? s "\"")
           (str/ends-with? s "\""))
    (subs s 1 (dec (count s)))
    s))

(defn parse-matchfields
  [s]
  (str/replace s #"match\((\".*\")\)" "$1"))

(defn split-indexing
  [path]
  (flatten
    (for [s path]
      (if (re-find #"\[\d+\]$" s)
        (-> s
            (str/split #"(?=\[\d+\]$)")
            (update 1 #(Integer/parseInt (subs % 1 (dec (count %))))))
        s))))

(defn- handle-quoted-path-segment
  [v]
  (loop [result []
         [s & splits] v]
    (let [s-len (count s)]
      (if (and (str/ends-with? s "\"")
               (not (= s-len 1))
               (or (<= s-len 2) (not (= (nth s (- s-len 2)) \\))))
        [(str/join "." (conj result s)) splits]
        (recur (conj result s) splits)))))

(defn dotted-query->path
  [string]
  (loop [[s & splits :as v] (str/split string #"\.")
         result []]
    (if (nil? s)
      result
      (let [s-len (count s)]
        (if (and (str/starts-with? s "\"")
                 (or (= s-len 1)
                     (or (not (str/ends-with? s "\""))
                         (and (str/ends-with? s "\"")
                              (>= s-len 2)
                              (= (nth s (- s-len 2)) \\)))))
          (let [[x xs] (handle-quoted-path-segment v)]
            (recur xs (conj result x)))
          (recur splits (conj result s)))))))

(defn expand-array-access-in-path
  "Given a path like [\"a\" \"b[0]\" \"c\"], expand the [0] to get
   [\"a\" \"b\" 0 \"c\"]"
  [path]
  (mapcat (fn [el]
            (let [[[_ field index-str]] (re-seq #"^(.*)\[(\d+)\]$" el)]
              (if index-str
                [field (Integer/parseInt index-str)]
                [el])))
          path))
