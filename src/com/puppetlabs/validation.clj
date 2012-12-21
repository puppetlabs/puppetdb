(ns com.puppetlabs.validation
  (:require [com.puppetlabs.utils :as pl-utils]
            [clojure.string :as string]
            [clojure.set :as set])
  (:use [cheshire.custom :only [JSONable]]))

(defmacro defmodel
  "Defines a 'model' which can be used for validating maps of data.  Here's an
  example of what the usage looks like:

      (defmodel Foo
        {:a {:optional? true
             :type :string}
         :b :integer
         :c :number})

  This would create a 'model' for a type called `Foo`, which describes a map
  that must contain keys `:b` and `:c` (whose values must be an integer and
  number, respectively), and may optionally contain a key `:a` (whose value
  must be a string).  Then, given a map instance named `mymap` which should
  conform to the `Foo` model, you can call:

      (validate-against-model! Foo mymap)

  This will validate that the map conforms to the model, and throw an exception
  with a descriptive error message if it does not.
  "
  [model-name fields]
  `(def ~model-name
     {:name (str '~model-name)
      :fields (pl-utils/mapvals (fn [v#]
                                  (if (map? v#)
                                    v#
                                    {:optional? false
                                     :type v#})) ~fields)}))

(defn validate-against-model!
  "Validates a map against a model (see `defmodel` for more information about
  defining a model).  Throws an `IllegalArgumentException` with a descriptive
  error message if the map is not valid according to the model."
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
        ;; TODO: these should probably be dynamic
        type-fns {:string   string?
                  :integer  integer?
                  :number   number?
                  :datetime pl-utils/datetime?
                  :coll     coll?
                  :jsonable #(satisfies? JSONable %)}
        type-errors (for [[field {:keys [optional? type]}] fields
                          :let [value (field obj)
                                type-fn (type-fns type)]]
                      (cond
                        ;; if there is no type function, we can go ahead and error
                        (not type-fn)
                          (format "%s specifies unrecognized type %s for key %s"
                              model-name type field)
                        ;; if there is a type function, then we need to validate
                        ;; the type--assuming that the field is either required,
                        ;; or is optional but provided and non-nil.
                        (and (contains? obj field)
                             (not (and optional? (nil? value)))
                             (not (type-fn value)))
                          (format "%s key %s should be %s, got %s"
                              model-name field (string/capitalize (name type)) value)))

        error-message (string/join "\n" (remove nil? (concat [missing-keys-message unknown-keys-message] type-errors)))]
    (when-not (empty? error-message)
      (throw (IllegalArgumentException. error-message))))
  obj)
