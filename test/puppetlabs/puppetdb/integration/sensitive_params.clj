(ns puppetlabs.puppetdb.integration.sensitive-params
  (:require
   [clojure.java.shell :refer [sh]]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.integration.fixtures :as int])
  (:import
   [java.net URI]
   [java.nio.file Files]
   [java.nio.file.attribute PosixFilePermissions]))

(defn pg-dump
  "Returns the content of pg as a string via pg_dump or nil on failure."
  [pg]
  (let [{:keys [user password subname]} (:db-config pg)
        attrs (into-array [(-> "rw-------"
                               PosixFilePermissions/fromString
                               PosixFilePermissions/asFileAttribute)])
        pgpass (Files/createTempFile "tmp-pg-dump-passfile" "" attrs)]
    (try
      (spit (.toFile pgpass)
            (str
             "# hostname:port:database:username:password\n"
             "*:*:*:" user ":" password "\n"))
      (let [db-uri (URI. subname)
            cmd ["pg_dump"
                 "--username" user
                 "--host" (.getHost db-uri)
                 "--port" (str (.getPort db-uri))
                 "--dbname" (subs (.getPath db-uri) 1)
                 "--data-only"
                 "--no-owner"
                 "--encoding" "utf-8"
                 "--format" "plain"
                 :out-enc "utf-8"
                 :env (merge {} (System/getenv) {"PGPASSFILE" (str pgpass)})]
            result (apply sh cmd)]
        (when-not (zero? (:exit result))
          (is (= 0 (:exit result)))
          (binding [*out* *err*]
            (pprint cmd)
            (pprint result)))
        (:out result))
      (finally
        (Files/deleteIfExists pgpass)))))

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
      (let [notifications (filter #(= ["Notify" "hi"] [(:type %) (:title %)])
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
      (let [[notify & other-bits] (filter #(= ["Notify" "hi"] [(:type %) (:title %)])
                                    (int/pql-query pdb "resources {}"))]
        (is (empty? other-bits))
        (is (= {} (:parameters notify))))
      (is (not (str/includes? (pg-dump pg) secret))))))
