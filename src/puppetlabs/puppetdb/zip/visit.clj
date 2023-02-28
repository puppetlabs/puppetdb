;; The MIT License (MIT)
;;
;; Copyright (c) 2013-2015 Alexander K. Hudek
;;
;; Permission is hereby granted, free of charge, to any person obtaining a copy
;; of this software and associated documentation files (the "Software"), to deal
;; in the Software without restriction, including without limitation the rights
;; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the Software is
;; furnished to do so, subject to the following conditions:
;;
;; The above copyright notice and this permission notice shall be included in
;; all copies or substantial portions of the Software.
;;
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
;; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
;; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
;; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
;; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
;; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
;; THE SOFTWARE.
;;
;; https://github.com/akhudek/zip-visit
(ns puppetlabs.puppetdb.zip.visit
  (:require
    [fast-zip.core :as z]))

(defn- visit-node
  [dir node state visitors]
  (loop [node           node
         state          state
         [v & visitors] visitors]
    (if v
      (let [{node* :node state* :state b :break c :cut} (v dir node state)]
        (if (or b c)
          {:node node* :state state* :break b :cut c}
          (recur (if (nil? node*) node node*)
                 (if (nil? state*) state state*)
                 visitors)))
      {:node node :state state})))

(defn- visit-location
  [dir loc state visitors]
  (let [node (z/node loc)
        context (visit-node dir (z/node loc) state visitors)]
    {:loc   (if (nil? (:node context)) loc (z/replace loc (:node context)))
     :state (if (nil? (:state context)) state (:state context))
     :break (:break context)
     :cut   (:cut context)}))

(defn- break-visit
  [{loc :loc state :state}]
  {:node (z/root loc) :state state})

(defn visit
  "Visit a tree in a stateful manner. Visitor fuctions return maps with optional keys:
   :node  - replacement node (doesn't work in :in state)
   :state - new state
   :cut   - in a :pre state, stop downward walk
   :break - stop entire walk and return"
  [loc state visitors]
  (loop [loc         loc
         next-node   :down
         state       state]
    (case next-node
      :down
      (let [{loc :loc state :state :as r} (visit-location :pre loc state visitors)]
        (if (:break r)
          (break-visit r)
          (if-let [loc* (and (not (:cut r)) (z/down loc))]
            (recur loc* :down state)
            (recur loc :right state))))

      :right
      (let [{loc :loc state :state :as r} (visit-location :post loc state visitors)]
        ;(println (:break r))
        (if (:break r)
          (break-visit r)
          (if-let [loc* (z/right loc)]
            (let [{state* :state :as r} (visit-node :in (z/node (z/up loc)) state visitors)
                  state**               (or state* state)]
              (if (:break r)
                (break-visit {:loc loc* :state state**})
                (recur loc* :down state**)))
            (if-let [loc* (z/up loc)]
              (recur loc* :right state)
              {:node (z/node loc) :state state})))))))

(defmacro visitor
  [type bindings & body]
  `(fn [d# n# s#]
     (when (= ~type d#)
       (loop [n*# n# s*# s#] (let [~bindings [n*# s*#]] ~@body)))))

(defmacro defvisitor
  [sym type bindings & body]
  `(def ~sym (visitor ~type ~bindings ~@body)))

