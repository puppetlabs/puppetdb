(ns puppetlabs.puppetdb.query-eng.parse-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.query-eng.parse :as parse])
  (:import
   (clojure.lang ExceptionInfo)))

;; FIXME: finish tests

(defn parse-field-ex-info [field]
  (try
    (parse/parse-field field)
    (catch ExceptionInfo ex
      {:msg (.getMessage ex)
       :data (ex-data ex)})))

(deftest field-parsing

  (is (= {:kind ::parse/invalid-field :field ""}
         (-> "" parse-field-ex-info :data)))

  (is (= {:kind ::parse/invalid-field-component :field "nope!" :offset 0}
         (-> "nope!" parse-field-ex-info :data)))

  (is (= {:kind ::parse/invalid-field-component :field "nope!.nope" :offset 0}
         (-> "nope!.nope" parse-field-ex-info :data)))

  (is (= {:kind ::parse/invalid-field-component :field "nope." :offset 5}
         (-> "nope." parse-field-ex-info :data)))

  (is (= {:kind ::parse/invalid-field-component :field "nope..nope" :offset 5}
         (-> "nope..nope" parse-field-ex-info :data)))

  (is (= {:kind ::parse/invalid-field-component :field "nope.\"\".nope" :offset 5}
         (-> "nope.\"\".nope" parse-field-ex-info :data)))

  (is (= [{:kind ::parse/named-field-part :name "foo"}]
         (parse/parse-field "foo")))
  (is (= [{:kind ::parse/named-field-part :name "foo?"}]
         (parse/parse-field "foo?")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar"}]
         (parse/parse-field "foo.bar")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar\nbaz"}]
         (parse/parse-field "foo.bar\nbaz")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar"}
          {:kind ::parse/named-field-part :name "baz"}]
         (parse/parse-field "foo.bar.baz")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/indexed-field-part :name "bar" :index 3}]
         (parse/parse-field "foo.bar[3]")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/indexed-field-part :name "bar baz" :index 3}]
         (parse/parse-field "foo.\"bar baz[3]\"")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/match-field-part :pattern "bar"}]
         (parse/parse-field "foo.match(\"bar\")")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar baz"}]
         (parse/parse-field "foo.\"bar baz\"")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar.baz"}]
         (parse/parse-field "foo.\"bar.baz\"")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar.baz.bax"}
          {:kind ::parse/named-field-part :name "xyz"}]
         (parse/parse-field "foo.\"bar.baz.bax\".xyz")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar.baz"}
          {:kind ::parse/named-field-part :name "bar.bax"}]
         (parse/parse-field "foo.\"bar.baz\".\"bar.bax\"")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar..baz"}]
         (parse/parse-field "foo.\"bar..baz\"")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar\"baz"}
          {:kind ::parse/named-field-part :name "bax"}]
         (parse/parse-field "foo.bar\"baz.bax")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "ba\\\".r"}]
         (parse/parse-field "foo.\"ba\\\".r\"")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar.baz\"bax"}
          {:kind ::parse/named-field-part :name "xyz"}]
         (parse/parse-field "foo.\"bar.baz\"bax\".xyz")))
  (is (= [{:kind ::parse/named-field-part :name "v"}
          {:kind ::parse/named-field-part :name "w x"}
          {:kind ::parse/indexed-field-part :name "y" :index 3}
          {:kind ::parse/match-field-part :pattern "z"}]
         (parse/parse-field "v.\"w x\".y[3].match(\"z\")")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "\""}
          {:kind ::parse/named-field-part :name "bar"}]
         (parse/parse-field "foo.\".bar")))
  (is (= [{:kind ::parse/named-field-part :name "foo"}
          {:kind ::parse/named-field-part :name "bar\""}]
         (parse/parse-field "foo.bar\""))))

(deftest dotted-query-to-path
  (testing "vanilla dotted path"
    (is (= (parse/dotted-query->path "facts.foo.bar")
           ["facts" "foo" "bar"])))
  (testing "dot inside quotes"
    (is (= (parse/dotted-query->path "facts.\"foo.bar\".baz")
           ["facts" "\"foo.bar\"" "baz"]))
    (is (= (parse/dotted-query->path "facts.\"foo.baz.bar\".baz")
           ["facts" "\"foo.baz.bar\"" "baz"]))
    (is (= (parse/dotted-query->path "facts.\"foo.bar\".\"baz.bar\"")
           ["facts" "\"foo.bar\"" "\"baz.bar\""])))
  (testing "consecutive dots"
    (is (= (parse/dotted-query->path "facts.\"foo..bar\"")
           ["facts" "\"foo..bar\""])))
  (testing "path with quote in middle"
    (is (= (parse/dotted-query->path "facts.foo\"bar.baz")
           ["facts" "foo\"bar" "baz"])))
  (testing "path containing escaped quote"
    (is (= (parse/dotted-query->path "\"fo\\\".o\"")
           ["\"fo\\\".o\""])))
  (testing "dotted path with quote"
    (is (= (parse/dotted-query->path "facts.\"foo.bar\"baz\".biz")
           ["facts" "\"foo.bar\"baz\"" "biz"]))))

(deftest expand-array-access-in-path-test
  (are [in out] (= out (parse/expand-array-access-in-path in))
    ["a" "b[0]" "c"] ["a" "b" 0 "c"]
    ["a" "b" "c"] ["a" "b" "c"]
    ["a[0]"] ["a" 0]
    ["a[0]foo"] ["a[0]foo"]))
