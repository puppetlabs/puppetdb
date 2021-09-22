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

(defn reconstruction-ex-info [path]
  (try
    (parse/path-names->field-str path)
    (catch ExceptionInfo ex
      {:msg (.getMessage ex)
       :data (ex-data ex)})))

(deftest field-reconstruction-from-path
  (is (= {:kind ::parse/unquotable-field-segment :name ".nope\\"}
         (-> ["yep" ".nope\\"] reconstruction-ex-info :data)))
  (is (= {:kind ::parse/unquotable-field-segment :name "nope.\\"}
         (-> ["yep" "yep" "nope.\\"] reconstruction-ex-info :data)))
  (is (= "x" (parse/path-names->field-str ["x"])))
  (is (= "x.y" (parse/path-names->field-str ["x" "y"])))
  (is (= "x.y z" (parse/path-names->field-str ["x" "y z"])))
  (is (= "x.\"y.z\"" (parse/path-names->field-str ["x" "y.z"]))))
