(ns com.puppetlabs.utils)

(defn symmetric-difference
  "Computes the symmetric difference between 2 sets"
  [s1 s2]
  (clojure.set/union (clojure.set/difference s1 s2) (clojure.set/difference s2 s1)))

(defn as-collection
  "Returns the item wrapped in a collection, if it's not one
already. Returns a list by default, or you can use a constructor func
as the second arg."
  ([item]
     (as-collection item list))
  ([item constructor]
     {:post [(coll? %)]}
     (if (coll? item)
       item
       (constructor item))))

(defn mapvals
  "Return map `m`, with each value transformed by function `f`"
  [f m]
  (into {} (concat (for [[k v] m]
                     [k (f v)]))))

(defn array?
  "Returns true if `x` is an array"
  [x]
  (.isArray (class x)))
