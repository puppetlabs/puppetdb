(ns com.puppetlabs.validation
  (:require [com.puppetlabs.utils :as pl-utils]
            [clojure.string :as string]
            [clojure.set :as set]))

(defmacro defmodel
  [model-name fields]
  `(def ~model-name
     {:name (str '~model-name)
      :fields (pl-utils/mapvals (fn [v#]
                                  (if (map? v#)
                                    v#
                                    {:optional? false
                                     :type v#})) ~fields)}))

(defn valid-keys?
  "Returns true if the set of keys in `obj` is exactly the set specified in
  `attrs`, otherwise false."
  [obj attrs]
  {:pre [(map? obj)]
   :post [(= % obj)]}
  (let [present-keys (pl-utils/keyset obj)
        extra-keys (set/difference present-keys attrs)
        missing-keys (set/difference attrs present-keys)]
    (and (empty? extra-keys) (empty? missing-keys))))

(comment (defn validate-key*
  [model obj k]
  {:pre [(map? obj)
         (map? (:fields model))
         (string? (:name model))]
   :post [((some-fn string? nil?) %)]}
  (let [value (obj k)])))

(defn validate-against-model
  [model obj]
  {:pre [(map? obj)
         (map? (:fields model))
         (string? (:name model))]
   :post [(= obj %)]}
  (let [model-name (:name model)
        fields (:fields model)
        field-names (pl-utils/keyset fields)
        present-keys (pl-utils/keyset obj)
        optional-keys (set (map key (filter #(:optional? (val %)) fields)))
        missing-keys (set/difference field-names present-keys optional-keys)
        unknown-keys (set/difference present-keys field-names)
        missing-keys-message (if (seq missing-keys)
                               (format "%s is missing keys: %s" model-name (string/join ", " (sort missing-keys))))
        unknown-keys-message (if (seq unknown-keys)
                               (format "%s has unknown keys: %s" model-name (string/join ", " (sort unknown-keys))))
        type-fns {:string string?
                  :integer integer?
                  :number number?}
        type-errors (for [[field {:keys [optional? type]}] fields
                          :let [value (field obj)]
                          :when (and (contains? obj field)
                                     (not (and optional? (nil? value)))
                                     (not ((type-fns type) value)))]
                      (format "%s key %s should be %s, got %s" model-name field (string/capitalize (name type)) value))
        error-message (string/join "\n" (remove nil? (concat [missing-keys-message unknown-keys-message] type-errors)))]
    (when-not (empty? error-message)
      (throw (IllegalArgumentException. error-message))))
  obj)
