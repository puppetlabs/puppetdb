(ns puppetlabs.puppetdb.random
  (:require [clojure.string :as string]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.kitchensink.core :refer [boolean?]]))

(def ^{:doc "Convenience for java.util.Random"}
  random (java.util.Random.))

(defn random-string
  "Generate a random string of optional length"
  ([] (random-string (inc (rand-int 10))))
  ([length]
     {:pre  [(integer? length)
             (pos? length)]
      :post [(string? %)
             (= length (count %))]}
     (let [ascii-codes (concat (range 48 58) (range 65 91) (range 97 123))]
       (apply str (repeatedly length #(char (rand-nth ascii-codes)))))))

(defn random-string-alpha
  "Generate a random string of optional length, only lower case alphabet chars"
  ([] (random-string (inc (rand-int 10))))
  ([length]
     {:pre  [(integer? length)
             (pos? length)]
      :post [(string? %)
             (= length (count %))]}
     (let [ascii-codes (concat (range 97 123))]
       (apply str (repeatedly length #(char (rand-nth ascii-codes)))))))

(defn random-bool
  "Generate a random boolean"
  []
  {:post [(boolean? %)]}
  (rand-nth [true false]))

(defn random-node-name
  "Generate a random node name."
  []
  {:post [(string? %)]}
  (str (random-string-alpha 10) "."
       (random-string-alpha 15) "."
       (random-string-alpha 3)))

(defn random-type-name
  "Generate a random type name."
  []
  {:post [(string? %)]}
  (string/capitalize (random-string-alpha 10)))

(defn random-pp-path
  "Generate a random path to a modules pp file"
  []
  {:post [(string? %)]}
  (str "/etc/puppet/modules/" (random-string-alpha 10) "/manifests/" (random-string-alpha 10) ".pp"))

(defn random-parameters
  "Generate a random set of parameters."
  []
  (into {} (repeatedly (inc (rand-int 10)) #(vector (random-string) (random-string)))))

(defn random-resource
  "Generate a random resource. Can optionally specify type/title, as
  well as any attribute overrides.

  Note that is _parameters_ is given as an override, the supplied
  parameters are merged in with the randomly generated set."
  ([] (random-resource (random-string) (random-string)))
  ([type title] (random-resource type title {}))
  ([type title overrides]
     (let [extra-params (overrides "parameters")
           overrides    (dissoc overrides "parameters")
           r            {"type"       type
                         "title"      title
                         "exported"   (random-bool)
                         "file"       (random-string)
                         "line"       (rand-int 1000)
                         "tags"       (set (repeatedly (inc (rand-int 10)) #(string/lower-case (random-string))))
                         "parameters" (merge (random-parameters) extra-params)}]
       (merge r overrides))))

;; A version of random-resource that returns resources with keyword
;; keys instead of strings
(def random-kw-resource (comp keywordize-keys random-resource))

(defn random-resource-event
  "Generate a random resource event."
  []
  {
   "resource_type"      (random-string)
   "resource_title"     (random-string)
   "property"           (random-string)
   "timestamp"          (random-string)
   "status"             (random-string)
   "old_value"          (random-string)
   "new_value"          (random-string)
   "message"            (random-string)
   })
