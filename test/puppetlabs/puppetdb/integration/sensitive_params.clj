(ns puppetlabs.puppetdb.integration.sensitive-params
  (:require
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.integration.fixtures :as int])
  (:import
   [java.net URI]))

(defn pg-dump
  "Returns the content of pg as a string via pg_dump."
  [pg]
  (let [db-config (:db-config pg)
        db-uri (URI. (:subname db-config))
        result (sh "pg_dump"
                   "--username" (:user db-config)
                   "--host" (.getHost db-uri)
                   "--port" (str (.getPort db-uri))
                   "--dbname" (subs (.getPath db-uri) 1)
                   "--data-only"
                   "--no-owner"
                   "--encoding" "utf-8"
                   "--format" "plain"
                   :out-enc "utf-8")]
    (when-not (zero? (:exit result))
      (is (= 0 (:exit result)))
      (binding [*out* *err*]
        (print (:err result))))
    (:out result)))

(deftest ^:integration sensitive-parameter-redaction
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server-as "my_puppetserver" [pdb] {})]
    (let [not-secret "friend R6P2BMFD3XD3PA53SNM5U7RNEE"
          secret "xyzzy XKPGMKIB253ZVJHKOKZZXPQSSE"]

      ;; Create an initial parameter, and make sure it's visible
      (int/run-puppet-as "my_agent" ps pdb
                         (format "notify {'hi':  message => '%s'}"
                                 not-secret))
      (let [notifications (filter #(and (= ["Notify" "hi"]
                                           [(:type %) (:title %)]))
                                  (int/pql-query pdb "resources {}"))
            [notify] notifications]
        (is (= 1 (count notifications)))
        (is (= {:message not-secret} (:parameters notify))))
      (let [dump (pg-dump pg)]
        (is (str/includes? dump not-secret))
        (is (not (str/includes? dump secret))))

      ;; Now change the parameter to be a secret, and make sure it's redacted
      (int/run-puppet-as "my_agent" ps pdb
                         (format "notify {'hi':  message => Sensitive('%s')}"
                                 secret))
      (let [[notify & other-bits] (filter #(and (= ["Notify" "hi"]
                                                   [(:type %) (:title %)]))
                                          (int/pql-query pdb "resources {}"))]
        (is (empty? other-bits))
        (is (= {} (:parameters notify))))
      (let [dump (pg-dump pg)
            ;; Once PUP-7417 is resolved, these tests will fail, and
            ;; then everything below here should be replaced with this:
            ;;   (is (not (str/includes? dump secret)))
            ;; but for now, check for the issue and then ignore it.
            dump-lines (str/split-lines dump)
            expected-leak (fn [line]
                            (every? (partial str/includes? line)
                                    ["\"file\": null"
                                     "\"line\": null"
                                     "\"tags\": [\"notice\"]"
                                     "\"level\": \"notice\""
                                     "\"source\": \"Puppet\""
                                     (format "\"message\": \"%s\"" secret)]))]
        (is (some expected-leak dump-lines))
        (is (not-any? #(str/includes? % secret)
                      (remove expected-leak dump-lines)))))))
