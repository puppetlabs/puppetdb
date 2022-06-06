(ns puppetlabs.puppetdb.zip-test
  (:require
   [clojure.test :refer :all]
   [clojure.test.check.clojure-test :as cct]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [puppetlabs.puppetdb.zip :as zip
    :refer [post-order-transform
            post-order-visit
            pre-order-visit
            tree-zipper]]))

(defn tree-generator
  "Returns a generator that creates trees using collections generated
  from `coll-generators`. `coll-generators` is a list of functions
  that accepts `leaf-values` (such as gen/vector, gen/list etc). The
  test-check size feature ensures the trees aren't too large."
  [coll-generators leaf-values]
  (fn [size]
    (if (= size 0)
      leaf-values
      (let [new-size (quot size 2)
            smaller-tree (gen/resize new-size (gen/sized (tree-generator coll-generators leaf-values)))]
        (gen/frequency
         (conj (mapv (fn [coll-generator]
                       [2 (coll-generator smaller-tree)]) coll-generators)
               [1 leaf-values]))))))

(def misc-leaves
  "Used for emulating typical tree contents with strings numbers and keywords"
  (gen/one-of [gen/int
               gen/string-alphanumeric
               gen/keyword]))

(defrecord Foo [a b c d])

(defn foo-gen
  "Generator for the defrecord Foo. Requires a leaves generator for
  the values of the defrecord (i.e. gen/int)"
  [leaves]
  (gen/fmap (partial apply ->Foo)
            (apply gen/tuple (repeat 4 leaves))))

(defn seq-gen
  "Generator for seqs of `leaves`"
  [leaves]
  (gen/fmap seq (gen/list leaves)))

(def sequential-gen
  "Generates sequential? things"
  [gen/vector
   gen/list
   seq-gen])

(def with-maps
  "Generates sequential? values, along with hash-maps and
   defrecords (instances of Foo)"
  (-> sequential-gen
      (conj #(gen/map (gen/one-of [gen/keyword gen/string]) %))
      (conj foo-gen)))


(def simple-reflexively-equal
  (gen/one-of [gen/int gen/large-integer
               (gen/double* {:NaN? false :infinite? false})
               gen/char gen/string gen/ratio gen/boolean gen/keyword
               gen/keyword-ns gen/symbol gen/symbol-ns gen/uuid]))

(def any-reflexively-equal
  (gen/recursive-gen gen/container-type simple-reflexively-equal))

(cct/defspec no-op-zipper
  50
  (prop/for-all [v (gen/sized (tree-generator with-maps any-reflexively-equal))]
                (= v
                   (:node (post-order-transform (tree-zipper v) [identity])))))

(defn flip-sign
  "Zipper function to make positive numbers negative and negative numbers positive"
  [x]
  (when (number? x)
    (- x)))

(cct/defspec flip-sign-zipper
  50
  (prop/for-all [tree (gen/sized (tree-generator with-maps gen/int))]
                (let [flipped (:node (post-order-transform (tree-zipper tree)
                                                           [flip-sign]))
                      nonzero-int? #(and (integer? %) (not= 0 %))]
                  ;; Tree only changes when there are non-zero integers.
                  (if (some nonzero-int? (tree-seq coll? seq tree))
                    (is (not= tree flipped))
                    (is (= tree flipped)))
                  (is (= tree
                         (:node (post-order-transform (tree-zipper flipped)
                                                      [flip-sign])))))))

(defn extract-item
  "Zipper function for putting items matching `pred` into the state of
  the zipper"
  [pred]
  (fn [node state]
    (when (pred node)
      {:state (conj state node)})))

(cct/defspec post-order-collect
  50
  (prop/for-all [w (gen/such-that coll? (gen/sized (tree-generator sequential-gen misc-leaves)) 100)]
                (= (:state (post-order-visit (tree-zipper w) [] [(extract-item number?)]))
                   (filter number? (flatten (seq w))))))

(deftest test-metadata-copying
  (let [tree {:a
              (with-meta
                {:b (with-meta
                      {:c
                       (with-meta
                         [1 2 3 (with-meta
                                  (list 4 5 6)
                                  {:tag "the end"})]
                         {:tag "baz"})}
                      {:tag "bar"})}
                {:tag "foo"})}
        result (:node (post-order-transform (tree-zipper tree) [flip-sign]))
        result2 (:node (post-order-transform (tree-zipper result) [flip-sign]))]

    (is (not= tree result))
    (is (= tree result2))

    (are [expected get-in-args] (= expected
                                   (meta (get-in result get-in-args))
                                   (meta (get-in result2 get-in-args)))

         {:tag "foo"} [:a]
         {:tag "bar"} [:a :b]
         {:tag "baz"} [:a :b :c]
         {:tag "the end"} [:a :b :c 3])))

(deftest test-pre-order-traversal
  (let [tree [1 [2 3 [4 5]] [6 7 8]]
        {:keys [node state]} (pre-order-visit (tree-zipper tree) [] [(extract-item coll?)])]

    (is (= [tree
            [2 3 [4 5]]
            [4 5]
            [6 7 8]]
           state))
    (is (= node tree))))

(deftest test-map-traversal
  (is (= {2 {2 {2 "foo"}}}
         (:node (post-order-transform (tree-zipper {1 {1 {1 "foo"}}})
                                      [(fn [node]
                                         (when (map-entry? node)
                                           (update node 0 inc)))])))))
