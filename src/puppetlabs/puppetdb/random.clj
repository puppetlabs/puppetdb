(ns puppetlabs.puppetdb.random
  (:require
   [clojure.pprint :as pp]
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

(defn sample-normal
  "Get a random integer from a normal distribution described by the given mean and
   standard deviation from that mean.

   ~68% of the returned values will fall within mean +/- standard-deviation.
   ~95% within two standard-deviations.
   ~99% within three...
   https://en.wikipedia.org/wiki/68%E2%80%9395%E2%80%9399.7_rule"
  [mean standard-deviation]
  (-> random .nextGaussian (* standard-deviation) (+ mean) int))

(defn safe-sample-normal
  "Get a random integer from the normal distribution guarded by some sane lower
   and upper bound. If not given, they default to 0 and twice the mean."
  [mean standard-deviation & {:keys [lowerb upperb] :or {lowerb 0 upperb (* 2 mean)}}]
   (when (> lowerb mean) (throw (ArithmeticException. (format "Called safe-sample-normal with lowerb of %s which is greater than mean of %s." lowerb mean))))
   (when (< upperb mean) (throw (ArithmeticException. (format "Called safe-sample-normal with upperb of %s which is less than mean of %s." upperb mean))))
   (-> (sample-normal mean standard-deviation) (max lowerb) (min upperb)))

(defn random-pronouncable-word
  "Generate a random string of optional length that alternates consonants and
   vowels to produce a loosely recognizable wordish thing.

   Optionally, supply standard deviation, to return a word of variable length
   from the given size based on the safe-sample-normal function."
  ([] (random-pronouncable-word 6))
  ([length] (random-pronouncable-word length nil))
  ([length sd] (random-pronouncable-word length sd {}))
  ([length sd bounds]
   (let [random-consonant #(RandomStringUtils/random 1 "bcdfghjklmnpqrstvwxz")
         random-vowel #(RandomStringUtils/random 1 "aeiouy")
         actual-length (if (nil? sd) length (safe-sample-normal length sd bounds))]
     (->> (for [i (range actual-length)]
           (if (even? i)
             (random-consonant)
             (random-vowel)))
         (string/join "")))))

(defn distribute
  "Perform f an avg-actions number of times randomly across the elements of the vector.

   The avg-actions may be a float, but if avg-actions is zero, nothing will be done.

   The total number of actions to perform will be plucked from a
   safe-sample-normal curve based on a mean of vect count times
   avg-actions. You can customize this with an options hash supplying
   standard-deviation, upper and lower bounds.

   Returns the updated vector."
  [vect f avg-actions & {:keys [debug] :as options}]
  (let [mean-total-actions (int (* (count vect) avg-actions))
        standard-deviation (or (:standard-deviation options)
                               (max 1 (quot mean-total-actions 5)))
        lowerb (or (:lowerb options)
                   (max 0 (- mean-total-actions (int (* standard-deviation 1.5)))))
        upperb (or (:upperb options)
                   (+ mean-total-actions (int (* standard-deviation 1.5))))
        total-actions (if (= 0 avg-actions)
                        0 ;; do nothing
                        (safe-sample-normal mean-total-actions standard-deviation {:lowerb lowerb :upperb upperb}))]
    (when debug
      (pp/pprint {:avg-actions avg-actions :mean-total-actions mean-total-actions :standard-deviation standard-deviation :lowerb lowerb :upperb upperb :total-actions total-actions :options options}))
    (loop [i total-actions
           v vect]
      (if (> i 0)
        (recur (- i 1)
               (update v (rand-int (count v)) f))
        v))))
