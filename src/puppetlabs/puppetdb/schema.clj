(ns puppetlabs.puppetdb.schema
  (:require [puppetlabs.puppetdb.time :as time]
            [schema.core :as s]
            [puppetlabs.i18n.core :refer [trs]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.coerce :as sc]
            [clojure.string :as str]
            [schema.utils :as su]
            [cheshire.custom :as json]
            [clojure.tools.logging :as log]
            [schema.spec.core]
            [schema.spec.variant]))

(defrecord DefaultedMaybe [schema default]
  s/Schema
  (s/spec [this]
        (schema.spec.variant/variant-spec
         schema.spec.core/+no-precondition+
         [{:guard nil? :schema (s/eq nil)}
          {:schema schema}]))
  (s/explain [this] (list 'defaulted-maybe (s/explain schema))))

(defmacro defn-validated
  [fn-name & forms]
  (let [fn-name (vary-meta fn-name assoc :always-validate true)]
    `(s/defn ~fn-name ~@forms)))

(defn defaulted-maybe
  "Create a new defaulted with `default` being used instead of `schema`. Defaulted
   maybe will be validated as it's wrapped schema specifies."
  [schema default]
  (map->DefaultedMaybe {:schema schema :default default}))

(defn defaulted-maybe?
  "True when `x` is a DefaultedMaybe"
  [x]
  (instance? puppetlabs.puppetdb.schema.DefaultedMaybe x))

(defn maybe? [x]
  (instance? schema.core.Maybe x))

(def coerce-to-int
  "Attempts to convert `x` to an integer. Failures will just return
   the original value (intending to fail on validation)"
  (sc/safe
   (fn [x]
     (if (integer? x)
       x
       (Integer/valueOf x)))))

(defn blocklist->vector
  "Take a facts list as either a comma seperated string
   or a sequence and return a vector of those facts"
  [fl]
  (cond
    (= "" fl) []
    (string? fl) (->> (str/split fl #",")
                      (map str/trim)
                      (apply vector))
    (and (coll? fl) (every? string? fl)) (vec fl)
    :else (throw (Exception. "Invalid facts blocklist format"))))

(defn period?
  "True if `x` is a JodaTime Period"
  [x]
  (instance? org.joda.time.Period x))

(def Blocklist
  "Schema type for facts-blocklist"
  (s/if coll?
    (s/if #(-> % first string?) [s/Str] [s/Regex])
    s/Str))

(def Timestamp
  "Schema type for JodaTime timestamps"
  (s/pred kitchensink/datetime? 'datetime?))

(def WireTimestamp
  "Schema type for wire format datetime strings"
  ;; FIXME: drop the classes once we fix the wire-format conversions
  ;; to always produce strings.
  (s/cond-pre java.sql.Timestamp
              org.joda.time.DateTime
              (s/pred time/wire-datetime? 'wire-datetime?)))

(def Function
  "Schema type for fn objects"
  (s/pred fn? 'fn?))

(def JSONable
  "Schema type for JSONable objects"
  (s/protocol json/JSONable))

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

(defn-validated convert-if-needed
  "Wraps each coercion function (value in the map) with a check
   to only covert the type if it's not already of that type."
  [m :- {Class (s/make-fn-schema s/Any s/Any)}]
  (reduce-kv (fn [acc clazz f]
               (assoc acc
                 clazz
                 (fn [obj]
                   (if (instance? clazz obj)
                     obj
                     (f obj))) ))
             {} m))

(def conversion-fns
  "Basic conversion functions for use by Schema"
  (convert-if-needed
   {org.joda.time.Minutes (comp time/minutes coerce-to-int)
    org.joda.time.Period (comp time/parse-period str)
    org.joda.time.Days (comp time/days coerce-to-int)
    org.joda.time.Seconds (comp time/seconds coerce-to-int)
    Boolean (comp #(Boolean/valueOf %) str)
    Long long
    clojure.lang.PersistentVector blocklist->vector}))

(defn convert-to-schema
  "Convert `data` to the format specified by `schema`"
  [schema data]
  (let [result ((sc/coercer schema conversion-fns) data)]
    (when (su/error? result)
      ;; Intended to roughly match plumatic's validator behavior
      (let [error (su/error-val result)]
        (throw (ex-info (trs "schema coercion error: {0}" (pr-str error))
                        {:schema schema
                         :value data
                         :error error}))))
    result))

(defn unknown-keys
  "Returns all the keys in `data` not specified by `schema`"
  [schema data]
  (keys (apply dissoc data (map schema-key->data-key (keys schema)))))

(defn strip-unknown-keys
  "Remove all keys from `data` not specified by `schema`"
  [schema data]
  (select-keys data (map schema-key->data-key (keys schema))))

(defn convert-blacklist-settings-to-blocklist [config]
  (let [{:keys [facts-blocklist
                facts-blocklist-type
                facts-blacklist
                facts-blacklist-type]} config
        blocklist-value (or facts-blocklist facts-blacklist)
        bloclist-type (or facts-blocklist-type facts-blacklist-type)]
    (when (and facts-blacklist facts-blocklist)
      (let [msg (trs "Confusing configuration settings found! Both the deprecated facts-blacklist and replacement facts-blocklist are set. These settings are mutually exclusive, please prefer facts-blocklist.")]
        (throw (ex-info msg {:type ::cli-error :message msg}))))
    (when facts-blacklist
      (log/warn (trs "The facts-blacklist and facts-blacklist-type settings have been deprecated and will be removed in a future release. Please use facts-blocklist and facts-blocklist-type instead.")))
    (cond-> config
      true (dissoc :facts-blacklist :facts-blacklist-type)
      blocklist-value (assoc :facts-blocklist blocklist-value)
      bloclist-type (assoc :facts-blocklist-type bloclist-type))))

;; FIXME - see db uses for testing, not right for multidb now.

(defn transform-data
  "Given an `in-schema` and `out-schema`, default missing values
   and convert to the `out-schema` format."
  [in-schema out-schema data]
  (->> data
       (defaulted-data in-schema)
       convert-blacklist-settings-to-blocklist
       (convert-to-schema out-schema)))
