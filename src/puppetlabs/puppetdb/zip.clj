(ns puppetlabs.puppetdb.zip
  (:require [puppetlabs.puppetdb.zip.visit :as zv]
            [fast-zip.core :as z]))

(defprotocol NodeTraversal
  (-branch? [x] "Returns true if it's possible for this type to have children")
  (-children [x] "Returns the children (if any) of this node")
  (-make-node [orig-node children] "Creates a new node from the existing `orig-node` and (potentially new) children"))

(defn transfer-meta [old-node new-node]
  (with-meta new-node (meta old-node)))

(extend-protocol NodeTraversal

  clojure.lang.IPersistentMap
  (-branch? [_] true)
  (-children [x] (seq x))
  (-make-node [orig-map new-map-seq]
    (let [new-map (if (instance? clojure.lang.IRecord orig-map)
                    orig-map
                    (empty orig-map))]
      (transfer-meta orig-map (into new-map (seq new-map-seq)))))

  clojure.lang.IPersistentVector
  (-branch? [_] true)
  (-children [x] (when (seq x) x))
  (-make-node [orig-vec new-children]
    (transfer-meta orig-vec (when (seq new-children)
                              (if (vector? new-children)
                                new-children
                                (vec new-children)))))

  clojure.lang.IPersistentSet
  (-branch? [_] true)
  (-children [x] (when (seq x) x))
  (-make-node [orig-set new-children]
    (transfer-meta orig-set (when (seq new-children)
                              (into (empty orig-set) new-children))))

  clojure.lang.IPersistentList
  (-branch? [_] true)
  (-children [x] x)
  (-make-node [orig-list new-children]
    (transfer-meta orig-list
                   (apply list new-children)))

  clojure.lang.ISeq
  (-branch? [_] true)
  (-children [x] x)
  (-make-node [node new-children]
    (transfer-meta node
                   (when (seq new-children)
                     (into (empty node) (reverse new-children)))))

  clojure.lang.MapEntry
  (-branch? [_] true)
  (-children [x] (seq x))
  (-make-node [node new-kv]
    (if new-kv
      (clojure.lang.MapEntry. (first new-kv) (second new-kv))
      node))

  Object
  (-branch? [_] false)
  (-children [_] nil)
  (-make-node [_ child]
    child)

  nil
  (-branch? [_] false)
  (-children [_] nil)
  (-make-node [_ _]
    nil))

(defn tree-zipper
  "Zipper that knows how to walk clojure data structures.
  Modifications while zipping are replaced with structures of the same
  time"
  [node]
  (z/zipper -branch? -children -make-node node))

(defn post-order-visit
  "Does a post-order travsersal of `zipper`. `visitors` is a seq of
  functions that take two args, node and state"
  [zipper initial-state
   visitors]
  (zv/visit zipper
            initial-state
            (map (fn [f]
                   (fn [dir node state]
                     (when (= :post dir)
                       (f node state)))) visitors)))

(defn post-order-transform
  "Zips through `zipper` applying each visitor function in `visitors`.
  `visitors` is a seq of functions that accepts one argument (the node
  to transform)"
  [zipper visitors]
  (post-order-visit zipper
                    nil
                    (map (fn [f]
                           (fn [node _state]
                             (when-let [new-node (f node)]
                               {:node new-node})))
                         visitors)))

(defn pre-order-visit
  "Does a pre-order travsersal of `zipper`. `visitors` is a seq of
  functions that take two args, node and state"
  [zipper initial-state
   visitors]
  (zv/visit zipper
            initial-state
            (map (fn [f]
                   (fn [dir node state]
                     (when (= :pre dir)
                       (f node state)))) visitors)))
