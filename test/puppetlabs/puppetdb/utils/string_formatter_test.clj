(ns puppetlabs.puppetdb.utils.string-formatter-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [clojure.math.combinatorics :refer [selections]]
            [puppetlabs.puppetdb.utils.string-formatter
             :refer [dash->underscore-keys
                     pprint-json-parse-exception
                     re-quote
                     underscore->dash-keys]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :as cct]))

(deftest re-quote-behavior
  (doseq [x (map #(apply str %) (selections ["x" "\\Q" "\\E"] 6))]
    (is (= x (re-matches (re-pattern (str (re-quote x) "$")) x)))))

(def dash-keyword-generator
  (gen/fmap (comp keyword #(string/join "-" %))
            (gen/not-empty (gen/vector gen/string-alphanumeric))))

(def underscore-keyword-generator
  (gen/fmap (comp keyword #(string/join "_" %))
            (gen/not-empty (gen/vector gen/string-alphanumeric))))

(cct/defspec test-dash-conversions
             50
             (prop/for-all [w (gen/map dash-keyword-generator gen/any)]
                           (= w
                              (underscore->dash-keys (dash->underscore-keys w)))))

(cct/defspec test-underscore-conversions
             50
             (prop/for-all [w (gen/map underscore-keyword-generator gen/any)]
                           (= w
                              (dash->underscore-keys (underscore->dash-keys w)))))

(deftest test-pprint-json-parse-exception
  (let [query "[\"from\", \"nodes\"] lk"
        error-message (str "Unrecognized token random: was expecting "
                           "(JSON String, Number, Array, Object or token null, true or false)\n"
                           " at [String.Reader line: 1, column: 21]")
        expected-message (str "Json parse error at line 1, column 21:\n\n"
                              "[\"from\", \"nodes\"] lk\n"
                              "                   ^\n\n"
                              "Unrecognized token random: was expecting "
                              "(JSON String, Number, Array, Object or token null, true or false)")
        mock-exception (ex-info error-message {})]
    (is (= expected-message (pprint-json-parse-exception  mock-exception query)))))
