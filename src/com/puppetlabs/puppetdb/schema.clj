(ns com.puppetlabs.puppetdb.schema
  (:require [com.puppetlabs.time :as pl-time]
            [clj-time.core :as time]
            [schema.core :as s]
            [schema.macros :as sm]
            [puppetlabs.kitchensink.core :as kitchensink]))

(defrecord DefaultedMaybe [schema default]
  s/Schema
  (s/walker [this]
    (let [sub-walker (s/subschema-walker schema)]
      (fn [x]
        (when-not (nil? x)
          (sub-walker x)))))
  (s/explain [this] (list 'defaulted-maybe (s/explain schema))))

(defmacro defn-validated
  [fn-name & forms]
  (let [fn-name (vary-meta fn-name assoc :always-validate true)]
    `(sm/defn ~fn-name ~@forms)))

(defn defaulted-maybe
  "Create a new defaulted with `default` being used instead of `schema`. Defaulted
   maybe will be validated as it's wrapped schema specifies."
  [schema default]
  (map->DefaultedMaybe {:schema schema :default default}))

(defn defaulted-maybe?
  "True when `x` is a DefaultedMaybe"
  [x]
  (instance? com.puppetlabs.puppetdb.schema.DefaultedMaybe x))

(defprotocol PredConstructFn
  (get-construct-fn [pred]
    "Returns the construct-fn for a given predicate schema, when more
     than one construct is present, pick the first."))

(defrecord ConstructedPred [predicate-rec construct-fn]

  PredConstructFn
  (get-construct-fn [_]
    construct-fn)

  s/Schema
  (s/walker [this]
    (s/walker predicate-rec))
  (s/explain [this]
    (s/explain predicate-rec)))

(defn constructed-pred
  "Create a predicate with `construct-fn` that can create a new instance
   of a type that will return true when passed into `pred`"
  ([pred construct-fn]
     (ConstructedPred. pred construct-fn))
  ([p? pred-name construct-fn]
     (ConstructedPred. (s/pred p? pred-name) construct-fn)))

(defn maybe? [x]
  (instance? schema.core.Maybe x))

(defn constructed-pred?
  "True if `x` is a ConstructedPred and thus able to be converted to a new type
   from an existing value"
  [x]
  (cond
   (instance? ConstructedPred x) true
   (maybe? x) (instance? ConstructedPred (:schema x))
   :else false))

(defn pred-name
  "Grabs the pred-name, typically a symbol representing the predicate
   function, from schema the `constructed-pred` is wrapping."
  [constructed-pred]
  (get-in constructed-pred [:schema :pred-name]))

(defn create-minutes
  "Create a JodaTime Minutes instance given the constructed predicate and a value"
  [pred val]
  (case (pred-name pred)
    'integer? (time/minutes val)
    (time/minutes (Integer/valueOf (str val)))))

(def Minutes
  "Schema type for a JodaTime Minutes instance"
  (constructed-pred time/minutes? 'minutes? create-minutes))

(defn create-period
  "Create a JodaTime Period instance given the constructed predicate and a
   value (something like 10d for 10 days)"
  [pred val]
  (pl-time/parse-period (str val)))

(defn create-days
  "Create a JodaTime Days instance given the constructed predicate and a value"
  [pred val]
  (case (pred-name pred)
    'integer? (time/days val)
    (time/days (Integer/valueOf (str val)))))

(def Days
  "Schema type for a JodaTime Days instance"
  (constructed-pred time/days? 'days? create-days))

(defn create-seconds
  "Creates a JodaTime Seconds instance given the constructed predicate `pred` and a value"
  [pred val]
  (case (pred-name pred)
    'integer? (time/secs val)
    (time/secs (Integer/valueOf (str val)))))

(def Seconds
  "Schema type for a JodaTime Seconds instance"
  (constructed-pred time/secs? 'secs? create-seconds))

(defn period?
  "True if `x` is a JodaTime Period"
  [x]
  (instance? org.joda.time.Period x))

(def Period
  "Schema type for JodaTime Period instances"
  (constructed-pred period? 'period? create-period))

(def Timestamp
  "Schema type for JodaTime timestamps"
  (s/pred kitchensink/datetime? 'datetime?))

(def Function
  (s/pred fn? 'fn?))

(defn convert-boolean
  "Converts stringified boolean values to booleans, ignores the first
   (ConstructedPred) argument as it always uses the stringified version."
  [_ s]
  (Boolean/valueOf (str s)))

(defn boolean?
  "Predicate for finding true and false values, not
   truthy or falsey values but real true/false values."
  [x]
  (or (true? x)
      (false? x)))

(def SchemaBoolean
  "Schema type for a boolean"
  (constructed-pred boolean? 'boolean? convert-boolean))

(defn schema-key->data-key
  "Returns the key from the `schema` map used for retrieving
   that schemas value from it's data map."
  [schema]
  (if (keyword? schema)
    schema
    (:k schema)))

(defn defaulted-maybe-keys
  "Returns all the defaulted keys of `schema`"
  [schema]
  (for [[k v] schema
        :when (defaulted-maybe? v)]
    k))

(defn construct-fn?
  "Returns true if x supports the PredConstructFn protocol"
  [x]
  (satisfies? PredConstructFn x))

(defn defaulted-data
  "Default missing values in the `data` map with values specified in `schema`"
  [schema data]
  (reduce (fn [acc schema-key]
            (let [data-key (schema-key->data-key schema-key)
                  schema-value (get schema schema-key)]
              (if (or (get acc data-key)
                      (not (defaulted-maybe? schema-value)))
                acc
                (assoc acc data-key (:default schema-value)))))
          data (defaulted-maybe-keys schema)))

(extend-protocol PredConstructFn
  schema.core.Either
  (get-construct-fn [pred]
    (get-construct-fn (first (:schemas pred))))
  schema.core.Both
  (get-construct-fn [pred]
    (get-construct-fn (first (:schemas pred))))
  schema.core.Maybe
  (get-construct-fn [pred]
    (get-construct-fn (:schema pred)))
  schema.core.Predicate
  (get-construct-fn [pred]
    (fn [pred val] val))
  DefaultedMaybe
  (get-construct-fn [pred]
    (get-construct-fn (:schema pred))))

(defn convert-to-schema
  "Convert `data` to the format specified by `schema`"
  [schema data]
  (reduce-kv (fn [acc k pred]
               (let [data-key (schema-key->data-key k)]
                 (if-let [d (and (construct-fn? pred)
                                 (get data data-key))]
                   (assoc acc data-key ( (get-construct-fn pred) (get schema k) d))
                   acc)))
             data schema))

(defn strip-unknown-keys
  "Remove all keys from `data` not specified by `schema`"
  [schema data]
  (select-keys data (map schema-key->data-key (keys schema))))

(defn transform-data
  "Given an `in-schema` and `out-schema`, default missing values
   and convert to the `out-schema` format."
  [in-schema out-schema data]
  (->> data
       (defaulted-data in-schema)
       (convert-to-schema out-schema)))

