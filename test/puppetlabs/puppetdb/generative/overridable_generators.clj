(ns puppetlabs.puppetdb.generative.overridable-generators
  (:refer-clojure :exclude [vector boolean double hash-map keys]
                  :rename {map clojure-core-map})
  (:require [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck.generators :as chuck-gen]))

;;; Overrideable generators
;;;
;;; This namespace provides a layer on top of regular test.check generators
;;; which provides two things:
;;; 1. A keyword-based registry for generators, in the spirit of clojure.spec.
;;; 2. An 'override' combinator to locally rebind generators by name. (again,
;;;    similar to the overrides feature in clojure.spec/gen)
;;;
;;; Implementation-wise, this happens by wrapping each generator in a function
;;; of the overrides map; the function is then responsible for passing the
;;; overrides map to its children. This is implemented in a generic way via the
;;; `lookup` and `lift` functions.
;;;
;;; Overridable versions of the most common generators are predefined; others
;;; can be added using the `lift` function or by directly creating
;;; a `(fn [overrides] ...)` which returns a generator.
;;;
;;; To get a regular test.check generator back, suitable for use with
;;; `prop/for-all`, use `convert`.

;;; Generator registry and overridable lookup
(defonce generators (atom {}))

(defmacro defgen [gen-key body]
  `(do
     (swap! generators assoc ~gen-key ~body)
     nil))

(defn lookup
  "given an overridable generator (w/optional overrides), or the keyword name of
  one, return a test.check generator. "
  ([gen] (lookup gen {}))
  ([gen overrides]
   (cond
     (keyword? gen) (if-let [gen-val (get (merge @generators overrides) gen)]
                      (recur gen-val overrides)
                      (throw (ex-info (str "Generator " gen " not registered") {:generator-key gen})))
     (gen/generator? gen) gen
     :else (gen overrides))))

(defn convert [overridable-gen]
  (lookup overridable-gen))

;;; Combinators for the special overridable specs

(defn lift
  "Lift a test.check generator or generator combinator into the overridable generator domain,
  converting arguments at the positions in arg-ids-to-lookup using the (lookup)
  function."
  [f & arg-ids-to-lookup]
  (if (fn? f)
    (let [arg-ids-to-lookup (set arg-ids-to-lookup)]
      (fn lifted-gen-combinator [& args]
        (fn mk-gen [overrides]
          (->> args
               (map-indexed (fn [id arg]
                              (if (arg-ids-to-lookup id)
                                (lookup arg overrides)
                                arg)))
               (apply f)))))
    (fn lifted-mk-literal-gen [overrides]
      (lookup f overrides))))

(def return (lift gen/return))
(def fmap (lift gen/fmap 1))
(def vector (lift gen/vector 0))
(def vector-distinct (lift gen/vector-distinct 0))
(def vector-distinct-by (lift gen/vector-distinct-by 1))
(def map (lift gen/map 0 1))
(def elements (lift gen/elements))
(def choose (lift gen/choose))
(def uuid (lift gen/uuid))
(def boolean (lift gen/boolean))
(def double (lift gen/double))
(def no-shrink (lift gen/no-shrink 0))
(def char-alphanumeric (lift gen/char-alphanumeric))
(def string-from-regex (lift chuck-gen/string-from-regex))
(def nat (lift gen/nat))

(defn hash-map
  "Like gen/hash-map"
  [& kvs]
  (fn [overrides]
    (->> kvs
         (partition 2)
         (mapcat (fn [[k v]] [k (lookup v overrides)]))
         (apply gen/hash-map))))

(defn one-of
  "Like gen/one-of"
  [gens]
  (fn [overrides]
    (gen/one-of (clojure-core-map #(lookup % overrides) gens))))

(defn nilable [g]
  (one-of [(return nil) g]))

(defn recursive-gen
  "Like gen/recursive-gen"
  [container-gen-fn scalar-gen]
  (fn [overrides]
    (gen/recursive-gen
     (fn [inner-gen]
       (lookup (container-gen-fn (lift inner-gen)) overrides))
     (lookup scalar-gen overrides))))

(defn keys
  "Given a bunch of keys, looks up their generators and then generates a map
  with the unqualified version of the keys as the key, and the generated value
  as the value. Like clojure.spec's (keys :un-req [...])."
  [& key-coll]
  (fn [overrides]
    (->> key-coll
         (mapcat (fn [k] [(keyword (name k)) (lookup k overrides)]))
         (apply gen/hash-map))))

(defn override
  "Override bindings from the global generator registry using those in the
  `overrides` map. Precedence is outside-in: any further overrides outside of
  this one will take priority."
  [overrides inner-gen]
  (fn [overrides-2]
    (lookup inner-gen (merge overrides overrides-2))))

(defn sample
  "Like gen/sample"
  [gen n]
  (gen/sample (lookup gen {}) n))
