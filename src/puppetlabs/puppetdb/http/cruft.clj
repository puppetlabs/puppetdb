(ns puppetlabs.puppetdb.http.cruft
  (:require [puppetlabs.puppetdb.query :as query]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]))

(def query-b
  "WITH nodes AS ( SELECT certnames.name as certname, reports.end_time AS report_timestamp,
  reports.noop AS report_noop, report_statuses.status AS report_status FROM certnames
  LEFT OUTER JOIN reports ON certnames.name = reports.certname AND reports.hash IN (SELECT report FROM latest_reports)
  LEFT OUTER JOIN report_statuses ON report_statuses.id = reports.status_id WHERE certnames.deactivated IS NULL),
  unresponsives AS ( SELECT certname FROM nodes WHERE report_timestamp < NOW() - interval '1 hour'),
  responsives AS ( SELECT * FROM nodes WHERE certname NOT IN (select * from unresponsives))
  SELECT 'unresponsive' as status, COUNT(*) FROM unresponsives UNION
  SELECT 'noop' as status, COUNT(*) FROM responsives WHERE report_noop = TRUE UNION
  SELECT COALESCE(report_status, 'unreported'), COUNT(*) FROM responsives WHERE
  report_noop = FALSE OR report_noop IS NULL GROUP BY report_status")

(defn query-a
  [query-num]
  ({:statuses "SELECT status, COUNT(*) FROM (SELECT reports.noop, reports.certname, reports.configuration_version, reports.containment_path, reports.end_time, reports.environment, reports.event_status, reports.file, reports.hash, reports.line, reports.message, reports.new_value, reports.old_value, reports.property, reports.puppet_version, reports.receive_time, reports.report_format, reports.resource_title, reports.resource_type, reports.start_time, reports.status, reports.timestamp, reports.transaction_uuid FROM ( select reports.hash,
     reports.certname, reports.puppet_version, reports.report_format, reports.configuration_version, reports.start_time,
     reports.end_time, reports.receive_time, reports.transaction_uuid, reports.noop, environments.name as environment,
     report_statuses.status as status, re.report, re.status as event_status, re.timestamp, re.resource_type,
     re.resource_title, re.property, re.new_value, re.old_value, re.message, re.file, re.line, re.containment_path,
     re.containing_class FROM reports INNER JOIN resource_events re on reports.hash=re.report
     LEFT OUTER JOIN environments on reports.environment_id = environments.id
     LEFT OUTER JOIN report_statuses on reports.status_id =
     report_statuses.id ) AS reports WHERE ( end_time > NOW() - interval '1 hour' AND noop = FALSE AND (report) in  ( SELECT latest_report.latest_report_hash FROM ( SELECT latest_reports.report as latest_report_hash
     FROM latest_reports ) AS latest_report  )  )) reports GROUP BY status"
   :noops "SELECT COUNT(*) FROM (SELECT reports.noop, reports.certname, reports.configuration_version, reports.containment_path, reports.end_time, reports.environment, reports.event_status, reports.file, reports.hash, reports.line, reports.message, reports.new_value, reports.old_value, reports.property, reports.puppet_version, reports.receive_time, reports.report_format, reports.resource_title, reports.resource_type, reports.start_time, reports.status, reports.timestamp, reports.transaction_uuid FROM ( select reports.hash,
     reports.certname, reports.puppet_version, reports.report_format, reports.configuration_version, reports.start_time,
     reports.end_time, reports.receive_time, reports.transaction_uuid, reports.noop, environments.name as environment,
     report_statuses.status as status, re.report, re.status as event_status, re.timestamp, re.resource_type, re.resource_title,
     re.property, re.new_value, re.old_value, re.message, re.file, re.line, re.containment_path, re.containing_class FROM reports
     INNER JOIN resource_events re on reports.hash=re.report LEFT OUTER JOIN environments on reports.environment_id = environments.id
     LEFT OUTER JOIN report_statuses on reports.status_id =
     report_statuses.id ) AS reports WHERE ( end_time > NOW() - interval '1 hour' AND noop = TRUE AND (report) in  ( SELECT latest_report.latest_report_hash FROM ( SELECT latest_reports.report as latest_report_hash
     FROM latest_reports ) AS latest_report  )  )) reports"
   :unreporteds "SELECT COUNT(*) FROM ( SELECT certnames.name as certname, certnames.deactivated, catalogs.timestamp AS catalog_timestamp,
     fs.timestamp AS facts_timestamp, reports.end_time AS report_timestamp, catalog_environment.name AS catalog_environment,
     facts_environment.name AS facts_environment, reports_environment.name AS report_environment FROM certnames
     LEFT OUTER JOIN catalogs ON certnames.name = catalogs.certname LEFT OUTER JOIN factsets as fs ON certnames.name = fs.certname
     LEFT OUTER JOIN reports ON certnames.name = reports.certname AND reports.hash IN (SELECT report FROM latest_reports)
     LEFT OUTER JOIN environments AS catalog_environment ON catalog_environment.id = catalogs.environment_id
     LEFT OUTER JOIN environments AS facts_environment ON facts_environment.id = fs.environment_id
     LEFT OUTER JOIN environments AS reports_environment ON reports_environment.id = reports.environment_id ) AS nodes WHERE  ( report_timestamp IS NULL AND report_environment IS NULL )"
   :unresponsives "SELECT COUNT(*) FROM ( SELECT certnames.name as certname, certnames.deactivated, catalogs.timestamp AS catalog_timestamp,
     fs.timestamp AS facts_timestamp, reports.end_time AS report_timestamp, catalog_environment.name AS catalog_environment,
     facts_environment.name AS facts_environment, reports_environment.name AS report_environment FROM certnames
     LEFT OUTER JOIN catalogs ON certnames.name = catalogs.certname LEFT OUTER JOIN factsets as fs ON certnames.name = fs.certname
     LEFT OUTER JOIN reports ON certnames.name = reports.certname AND reports.hash IN (SELECT report FROM latest_reports)
     LEFT OUTER JOIN environments AS catalog_environment ON catalog_environment.id = catalogs.environment_id
     LEFT OUTER JOIN environments AS facts_environment ON facts_environment.id = fs.environment_id
     LEFT OUTER JOIN environments AS reports_environment ON reports_environment.id = reports.environment_id ) AS nodes WHERE  ( report_timestamp < NOW() - interval '1 hour' )"} query-num))

(defn get-results
  [sql]
  (fn [db]
    (jdbc/with-transacted-connection db
      (query/streamed-query-result nil sql nil doall))))

(defn cruft-wrapper
  [results-fn]
  (fn [{:keys [globals]}]
    (let [db (:scf-read-db globals)]
      (-> (results-fn db)
          http/json-response))))

(def extract-count-sql (comp :count first))

(defn get-all-results-b
  [db]
  (let [statuses ((get-results query-b) db)
        results (into {} (for [{:keys [status count]} statuses] {status count}))]
    (merge {:failed 0 :changed 0 :unchanged 0 :unreported 0} results)))

(defn get-all-results-a
  [db]
  (let [wrapped-get (fn [k] ((-> (query-a k)
                                 get-results) db))
        extract-wrapped (comp extract-count-sql wrapped-get)
        statuses (wrapped-get :statuses)
        noops (extract-wrapped :noops)
        unreporteds (extract-wrapped :unreporteds)
        unresponsives (extract-wrapped :unresponsives)
        results (into {} (for [{:keys [status count]} statuses] {status count}))]
    (merge {:failed 0 :changed 0 :unchanged 0
            :noop noops :unreported unreporteds :unresponsive unresponsives} results)))

(def plan-a-app
  (let [extract-results (fn [k db] {k (->> ((get-results (query-a k)) db)
                                           extract-count-sql)})]
    (app
      ["statuses"] {:get (cruft-wrapper (partial extract-results :statuses))}
      ["noops"] {:get (cruft-wrapper (partial extract-results :noops))}
      ["unreporteds"] {:get (cruft-wrapper (partial extract-results :unreporteds))}
      ["unresponsives"] {:get (cruft-wrapper (partial extract-results :unresponsives))}
      [] {:get (cruft-wrapper (partial get-all-results-a))})))

(def cruft-app
  (app
    ["a" &] {:get plan-a-app}
    ["b"] {:get (cruft-wrapper (partial get-all-results-b))}))
