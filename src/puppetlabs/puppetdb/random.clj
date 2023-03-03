(ns puppetlabs.puppetdb.random
  (:require
   [clojure.string :as string]
   [clojure.walk :refer [keywordize-keys]]
   [puppetlabs.kitchensink.core :as kitchensink])
  (:import
   (org.apache.commons.lang3 RandomStringUtils)))

(def ^{:doc "Convenience for java.util.Random"}
  random (java.util.Random.))

(defn random-string
  "Generate a random string of optional length"
  ([] (RandomStringUtils/randomAlphabetic (inc (rand-int 10))))
  ([length]
   (RandomStringUtils/randomAlphabetic length)))

(defn random-string-alpha
  "Generate a random string of optional length, only lower case alphabet chars"
  ([] (random-string (inc (rand-int 10))))
  ([length]
   (.toLowerCase (RandomStringUtils/randomAlphabetic length))))

(defn random-pronouncable-word
  "Generate a random string of optional length that alternates consonants and
   vowels to produce a loosely recognizable wordish thing."
  ([] (random-pronouncable-word 6))
  ([length]
   (let [random-consonant #(RandomStringUtils/random 1 "bcdfghjklmnpqrstvwxz")
         random-vowel #(RandomStringUtils/random 1 "aeiouy")]
     (->> (for [i (range length)]
           (if (even? i)
             (random-consonant)
             (random-vowel)))
         (string/join "")))))

(defn random-bool
  "Generate a random boolean"
  []
  (< (Math/random) 0.5))

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

(defn random-sha1
  "Generate a SHA1 hash of a random-string."
  ([] (random-sha1 100))
  ([str-size]
   (kitchensink/utf8-string->sha1 (random-string str-size))))
