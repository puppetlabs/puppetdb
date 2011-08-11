(ns com.puppetlabs.utils)

(defn symmetric-difference
  "Computes the symmetric difference between 2 sets"
  [s1 s2]
  (clojure.set/union (clojure.set/difference s1 s2) (clojure.set/difference s2 s1)))
