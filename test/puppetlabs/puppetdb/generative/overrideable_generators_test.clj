(ns puppetlabs.puppetdb.generative.overrideable-generators-test
  (:require [clojure.test :refer :all]
            [clojure.test.check.clojure-test :as tc]
            [clojure.test.check.properties :as prop]
            [puppetlabs.puppetdb.generative.overridable-generators :as ogen]))

(ogen/defgen :test/a ogen/boolean)
(ogen/defgen :test/b ogen/uuid)
(ogen/defgen :test/map (ogen/keys :test/a :test/b))

(tc/defspec keys-generator 100
  (prop/for-all [m (ogen/convert :test/map)]
                (is (= [:a :b] (keys m)))))

(tc/defspec overrides 100
  (prop/for-all [m (->> :test/map
                        (ogen/override {:test/a (ogen/return true)})
                        ogen/convert)]
                (is (= [:a :b] (clojure.core/keys m)))
                (is (= true (:a m)))))

(tc/defspec override-presedence-is-outside-in 100
  (prop/for-all [m (->> :test/map
                        (ogen/override {:test/a (ogen/return true)})
                        (ogen/override {:test/a (ogen/return false)})
                        ogen/convert)]
                (is (= [:a :b] (clojure.core/keys m)))
                (is (= false (:a m)))))
