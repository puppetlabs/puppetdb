(ns puppetlabs.puppetdb.export
  (:require [clj-time.core :refer [now]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.factsets :as factsets]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-time.format :as time-fmt]
            [clj-time.coerce :as time-coerce]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

(def export-metadata-file-name "export-metadata.json")
(def query-api-version :v4)
(def ^:private command-versions
  ;; This is not ideal that we are hard-coding the command version here, but
  ;;  in our current architecture I don't believe there is any way to introspect
  ;;  on which version of the `replace catalog` matches up with the current
  ;;  version of the `catalog` endpoint... or even to query what the latest
  ;;  version of a command is.  We should improve that.
  {:replace_catalog 6
   :store_report 6
   :replace_facts 4})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utility Functions

(defn query-child-href-internally
  [query-fn child-href]
  (let [[parent-str identifier child-str] (take-last 3 (str/split child-href #"/"))
        entity (case child-str
                 "metrics" :report-metrics
                 "logs" :report-logs
                 (keyword child-str))
        query (case parent-str
                "reports" (case child-str
                            ("metrics" "logs") ["=" "hash" identifier]
                            ["=" "report" identifier])
                ("factsets" "catalogs") ["=" "certname" identifier])]
    (query-fn entity query-api-version query nil doall)))

(defn maybe-expand-href-fn
  "Expand the child-value's href if the data key isn't present in the child-value"
  [query-fn]
  (fn [{:keys [href] :as child-value}]
    (utils/assoc-when child-value :data (query-child-href-internally query-fn href))))

(defn complete-unexpanded-fields
  "Complete the unexpanded data, by retrieving data from the href if data is
  missing for the given list of fields."
  [query-fn fields data]
  (let [expand-field-href-fn (maybe-expand-href-fn query-fn)
        expand-children-fields-fn (fn [datum]
                                    (kitchensink/mapvals expand-field-href-fn fields datum))]
    (map expand-children-fields-fn data)))

(defn export-report-filename
  [{:keys [certname start_time configuration_version] :as report}]
  (let [formatted-start-time (->> start_time
                                  time-coerce/to-date-time
                                  (time-fmt/unparse (time-fmt/formatter "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")))]
    (->> (str formatted-start-time configuration_version)
         kitchensink/utf8-string->sha1
         (format "%s-%s.json" certname))))

(pls/defn-validated export-datum->tar-item :- utils/tar-item
  "Creates a tar-item map for a PuppetDB entity"
  [entity datum]
  (let [file-suffix
        (case entity
          :factsets ["facts" (str (:certname datum) ".json")]
          :catalogs ["catalogs" (str (:certname datum) ".json")]
          :reports ["reports" (export-report-filename datum)])]
    {:file-suffix file-suffix
     :contents (if (= entity :reports)
                 (-> datum (dissoc :hash) json/generate-pretty-string)
                 (json/generate-pretty-string datum))}))

(defn export-data->tar-items
  [entity data]
  (map #(export-datum->tar-item entity %) data))

(def export-info
  {:catalogs {:child-fields [:edges :resources]
              :query->wire-fn catalogs/catalogs-query->wire-v6
              :anonymize-fn anon/anonymize-catalog}
   :reports {:child-fields [:metrics :logs :resource_events]
             :query->wire-fn reports/reports-query->wire-v6
             :anonymize-fn anon/anonymize-report}
   :factsets {:child-fields [:facts]
              :query->wire-fn factsets/factsets-query->wire-v4
              :anonymize-fn anon/anonymize-facts}})

(defn maybe-anonymize [anonymize-fn anon-config data]
  (if (not= anon-config ::not-found)
    (map (comp clojure.walk/keywordize-keys
               #(anonymize-fn anon-config %)
               clojure.walk/stringify-keys) data)
    data))

(defn add-tar-entries [tar-writer entries]
  (doseq [entry entries]
    (utils/add-tar-entry tar-writer entry)))

(defn export!*
  [tar-writer query-fn anonymize-profile]
  (let [anon-config (get anon/anon-profiles anonymize-profile ::not-found)]
    (doseq [[entity {:keys [child-fields query->wire-fn anonymize-fn]}] export-info
            :let [query-callback-fn (fn [rows]
                                      (->> rows
                                           (complete-unexpanded-fields query-fn child-fields)
                                           query->wire-fn
                                           (maybe-anonymize anonymize-fn anon-config)
                                           (export-data->tar-items entity)
                                           (add-tar-entries tar-writer)))]]
      (query-fn entity query-api-version nil nil query-callback-fn))))

(defn export!
  ([outfile query-fn] (export! outfile query-fn nil))
  ([outfile query-fn anonymize-profile]
   (log/info "Export triggered for PuppetDB")
   (with-open [tar-writer (archive/tarball-writer outfile)]
     (utils/add-tar-entry
      tar-writer {:file-suffix [export-metadata-file-name]
                  :contents (json/generate-pretty-string {:timestamp (now) :command_versions command-versions})})
     (export!* tar-writer query-fn anonymize-profile))
   (log/infof "Finished exporting PuppetDB")))
