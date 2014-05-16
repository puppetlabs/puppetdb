(ns com.puppetlabs.puppetdb.test.zip
  (:require [com.puppetlabs.puppetdb.zip :refer :all]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :as cct]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

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
                       [3 (coll-generator smaller-tree)]) coll-generators)
               [5 leaf-values]))))))

(def misc-leaves
  "Used for emulating typical tree contents with strings numbers and keywords"
  (gen/one-of [gen/int
               gen/string-alpha-numeric
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

(cct/defspec no-op-zipper
  50
  (prop/for-all [v (gen/sized (tree-generator with-maps gen/any))]
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
                                                           [flip-sign]))]
                  (not= tree flipped)
                  (= tree
                     (:node (post-order-transform (tree-zipper flipped)
                                                  [flip-sign]))))))

(defn extract-item
  "Zipper function for putting items matching `pred` into the state of
  the zipper"
  [pred]
  (fn [node state]
    (when (pred node)
      {:state (conj state node)})))

(cct/defspec post-order-collect
  50
  (prop/for-all [w (gen/such-that coll? (gen/sized (tree-generator sequential-gen misc-leaves)))]
                (= (:state (post-order-visit (tree-zipper w) [] [(extract-item number?)]))
                   (filter number? (flatten (seq w))))))
