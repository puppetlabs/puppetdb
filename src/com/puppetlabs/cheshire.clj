(ns ^{:doc "Cheshire related functions

  This namespace when 'required' will also setup some common JSON encoders
  globally, so you can avoid doing this for each call."}

  com.puppetlabs.cheshire
  (:require [cheshire.generate :as generate]
            [clj-time.coerce :as coerce]))

(defn add-common-json-encoders!*
  "Non-memoize version of add-common-json-encoders!"
  []
  (generate/add-encoder
    org.joda.time.DateTime
    (fn [data jsonGenerator]
      (.writeString jsonGenerator (coerce/to-string data)))))

(def
  ^{:doc "Registers some common encoders for cheshire JSON encoding.
  This is a memoize function, to avoid unnecessary calls to add-encoder.

  Ideally this function should be called once in your apply, for example your
  main class.

  Encoders currently include:

  * org.joda.time.DateTime - handled with to-string"}
  add-common-json-encoders! (memoize add-common-json-encoders!*))

(add-common-json-encoders!)
