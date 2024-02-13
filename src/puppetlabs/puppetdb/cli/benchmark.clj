(ns puppetlabs.puppetdb.cli.benchmark
  "Benchmark suite

   This command-line utility will simulate catalog submission for a
   population. It requires that a separate, running instance of
   PuppetDB for it to submit catalogs to.

   We attempt to approximate a number of hosts submitting catalogs at
   the specified runinterval with the specified rate-of-churn in
   catalog content.

   ### Running parallel Benchmarks

   If are running up against the upper limit at which Benchmark can
   submit simulated requests, you can run multiple instances of benchmark and
   make use of the --offset flag to shift the cert numbers.

   Example (probably run on completely separate hosts):

   ```
   benchmark --offset 0 --numhosts 100000
   benchmark --offset 100000 --numhosts 100000
   benchmark --offset 200000 --numhosts 100000
   ...
   ```

   ### Preserving host-map data

   By default, each time Benchmark is run, it initializes the host-map catalog,
   factset and report data randomly from the given set of base --catalogs
   --factsets and --reports files. When re-running benchmark, this causes
   excessive load on puppetdb due to the completely changed catalogs/factsets
   that must be processed.

   To avoid this, set --simulation-dir to preserve all of the host map data
   between runs as nippy/frozen files. Benchmark will then load and initialize a
   preserved host matching a particular host-# from these files at startup.
   Missing hosts (if --numhosts exceeds preserved, for example) will be
   initialized randomly as by default.

   ### Mutating Catalogs and Factsets

   The benchmark tool automatically refreshs timestamps and transaction ids
   when submitting catalogs, factsets and reports, but the content does not
   change.

   To simulate system drift, code changes and fact changes, use
   '--rand-catalog=PERCENT_CHANCE:CHANGE_COUNT' and
   '--rand-facts=PERCENT_CHANCE:PERCENT_CHANGE'.

   The former indicates the chance any given catalog will perform CHANGE_COUNT
   resource mutations (additions, modifications or deletions). The later is the
   chance any given factset will mutate PERCENT_CHANGE of its fact values. These
   may be set multiple times, provided that PERCENT_CHANCE does not sum to more
   than 100%.

   By default edges are not included in catalogs. If --include-edges is true,
   then add-resource and del-resource will involve edges as well.

   * adding a resource adds a single 'contains' edge with the source
     being one of the catalog's original (non-added) resources.
   * deleting a resource removes one of the added resources (if there are any)
     and it's related leaf edge.

   By ensuring we only ever delete leaves from the graph, we maintain the graph
   integrity, which is important since PuppetDB validates the edges on injestion.

   This provides only limited exercise of edge mutation, which seemed like a
   reasonable trade-off given that edge submission is deprecated. Running with
   --include-edges also impacts the nature of catalog mutation, since original
   resources will never be removed from the catalog.

   See add-resource, mod-resource and del-resource for details of resource and
   edge changes.

   TODO: Fact addition/removal
   TODO: Mutating reports"
  (:require
   [clojure.core.async :refer [go go-loop <! >! >!! chan] :as async]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [me.raynes.fs :as fs]
   [metrics.timers :as timers :refer [timer time!]]
   [murphy :refer [try!]]
   [puppetlabs.i18n.core :refer [trs]]
   [puppetlabs.kitchensink.core :as kitchensink]
   [puppetlabs.puppetdb.archive :as archive]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.cli.benchmark.query :as bench-query]
   [puppetlabs.puppetdb.cli.generate :as generate]
   [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
   [puppetlabs.puppetdb.client :as client]
   [puppetlabs.puppetdb.lint :refer [ignore-value]]
   [puppetlabs.puppetdb.metrics.core :as metrics]
   [puppetlabs.puppetdb.nio :refer [get-path]]
   [puppetlabs.puppetdb.random :as rnd]
   [puppetlabs.puppetdb.utils :as utils
    :refer [noisy-future println-err schedule]]
   [puppetlabs.puppetdb.time :as time :refer [now]]
   [puppetlabs.trapperkeeper.config :as config]
   [puppetlabs.trapperkeeper.logging :as logutils]
   [taoensso.nippy :as nippy])
  (:import
   (com.puppetlabs.ssl_utils SSLUtils)
   (java.io File IOException)
   (java.net URI)
   (java.net.http HttpClient
                  HttpRequest
                  HttpRequest$Builder
                  HttpRequest$BodyPublishers
                  HttpResponse
                  HttpResponse$BodyHandlers)
   (java.nio.file CopyOption
                  FileAlreadyExistsException
                  Files
                  LinkOption
                  NoSuchFileException
                  OpenOption
                  Path
                  StandardCopyOption)
   (java.nio.file.attribute FileAttribute)
   (java.util.concurrent RejectedExecutionException
                         ScheduledThreadPoolExecutor
                         Semaphore
                         TimeUnit)
   (org.apache.commons.compress.archivers.tar TarArchiveEntry)
   (org.apache.commons.lang3 RandomStringUtils)))

;; FIXME: make sure --queriers respects DISCARD_ALL_QUERIES

(def ^:private warn-on-reflection-orig *warn-on-reflection*)
(set! *warn-on-reflection* true)

(def metrics
  (let [reg (get-in metrics/metrics-registries [:benchmark :registry])]
    {:query-duration (timer reg (metrics/keyword->metric-name [:global] :query-duration))}))

;; Completely ad-hoc...
(def ^:private discard-all-messages?
  (->> (or (System/getenv "PDB_BENCH_DISCARD_ALL_MESSAGES") "")
       (re-matches #"yes|true|1")
       seq))

(defn- ssl-info->context
  [& {:keys [ssl-cert ssl-key ssl-ca-cert]}]
  (SSLUtils/pemsToSSLContext (io/reader ssl-cert)
                             (io/reader ssl-key)
                             (io/reader ssl-ca-cert)))

(defn- build-http-client [& {:keys [ssl-cert] :as opts}]
  (cond-> (HttpClient/newBuilder)
    ;; To follow redirects: (.followRedirects HttpClient$Redirect/NORMAL)
    ssl-cert (.sslContext (ssl-info->context opts))
    true .build))

;; Until we require requests to provide the client (perhaps we should)
(def ^:private http-client (memoize build-http-client))

(defn- json-request-generator
  ([uri] (.uri ^java.net.http.HttpRequest$Builder (json-request-generator) uri))
  ([] (-> (HttpRequest/newBuilder)
          ;; To follow redirects: (.followRedirects HttpClient$Redirect/NORMAL)
          (.header "Content-Type" "application/json; charset=UTF-8")
          (.header "Accept" "application/json"))))

(defn- string-publisher [s] (HttpRequest$BodyPublishers/ofString s))
(defn- string-handler [] (HttpResponse$BodyHandlers/ofString))
(defn- discarding-handler [] (HttpResponse$BodyHandlers/discarding))

(defn- post-body
  [^HttpClient client
   ^HttpRequest$Builder req-generator
   body-publisher
   response-body-handler]
  (let [res (.send client (-> req-generator (.POST body-publisher) .build)
                   response-body-handler)]
    ;; Currently minimal
    {::jdk-response res
     :status (.statusCode res)}))

(defn- post-json-via-jdk [url body opts]
  ;; Unlisted, valid keys: ssl-cert ssl-key ssl-ca-cert
  ;; Intentionally ignores unrecognized keys
  (post-body (http-client (select-keys opts [:ssl-cert :ssl-key :ssl-ca-cert]))
             (json-request-generator (URI. url))
             (string-publisher body)
             (string-handler)))

(defn- discard-json-post [_url _body _opts]
  {:status 200})

(def ^:private show-query-response? false)

(def ^:private query-pdb-discard-response
  (if discard-all-messages?
    (fn query-pdb-discard-response [_url _query _opts]
      nil)
    (fn query-pdb-discard-response [uri query opts]
      ;; Unlisted, valid keys: ssl-cert ssl-key ssl-ca-cert
      ;; Intentionally ignores unrecognized keys
      (let [res (-> (post-body (http-client (select-keys opts [:ssl-cert :ssl-key :ssl-ca-cert]))
                               (json-request-generator uri)
                               (-> {:query query} json/generate-string string-publisher)
                               (if-not show-query-response?
                                 (discarding-handler)
                                 (string-handler)))
                   ::jdk-response)]
        (when show-query-response?
          (binding [*out* *err*]
            (println "Query rsponse:")
            (println (.body ^HttpResponse res))))))))

(defn try-load-file
  "Attempt to read and parse the JSON in `file`. If this failed, an error is
  logged, and nil is returned."
  [file]
  (try
    (json/parse-string (slurp file))
    (catch Exception _
      (println-err (trs "Error parsing {0}; skipping" file)))))

(defn load-sample-data
  "Load all .json files contained in `dir`."
  [dir from-classpath?]
  (let [target-files (if from-classpath?
                       (->> dir io/resource io/file file-seq (remove #(.isDirectory ^File %)))
                       (-> dir (fs/file "*.json") fs/glob))
        data (->> target-files
                  (map try-load-file)
                  (filterv (complement nil?)))]
    (if (seq data)
      data
      (println-err
       (trs "Supplied directory {0} contains no usable data!" dir)))))

(def producers (vec (repeatedly 4 #(rnd/random-string 10))))

(defn random-producer []
  (rand-nth producers))

(defn resource-has-blob?
  "True if the given resource has a BLOB parameter value. Sample catalogs
  created by the PuppetDB Generate command may have 'content_blob_*' parameters
  with large values."
  [resource]
  (let [parameter-keys (keys (resource "parameters"))]
    (some #(str/starts-with? % "content_blob_") parameter-keys)))

(defmulti touch-parameter-value
  "Return a new parameter of about the same size and type.

  TODO: handle arrays and maps."
  class)
(defmethod touch-parameter-value String [p] (RandomStringUtils/randomAscii(count p)))
(defmethod touch-parameter-value Number [_] (rand-int 1000000))
(defmethod touch-parameter-value Boolean [p] (not p))
;; Allow other types to pass through unmutated for now
(defmethod touch-parameter-value :default [p] p)

(defn touch-parameters
  "Return resource parameters with one value changed. Size is the same."
  [parameters]
  (if (empty? parameters)
    parameters
    (let [pkey (rand-nth (keys parameters))]
      (-> parameters
        (assoc pkey (touch-parameter-value (parameters pkey)))))))

(defn rebuild-parameters
  "Return resource parameters with changed keys and values of the same number and
  size.

  In order to avoid key collisions, keys are rebuilt with at least five characters.

  If changing keys still results in a collision, log an error and return the
  original parameters."
  [parameters]
  (let [new-params (map (fn [[k v]]
                             [(generate/parameter-name (max 5 (count k)))
                              (touch-parameter-value v)])
                        parameters)
        rebuilt (into {} new-params)]
    (if (= (count rebuilt) (count parameters))
      rebuilt
      (do
        (println-err
          (format "Warning: failed rebuilt-parameters due to key collision, returning original.\n  Original: %s\n  Rebuilt: %s\n"
                  parameters rebuilt))
        parameters))))

(defn modify-title
  "Regenerate a title of the same size as the one given matching
  cli.generate/pseudonym format. The original ordinal is kept to help with
  debugging, and to avoid the cost of scanning for a next value.

  NOTE: the minimum title-size is 20, but there is still a chance of duplicates
  in long running benchmarks."
  [title prefix]
  (let [title-size (max 20 (count title))
        ordinal (get (str/split title #"-") 2 0)]
    (generate/pseudonym prefix ordinal title-size)))

(defn clone-resource
  "Build a new resource loosely based off the characteristics of the given resource.

  Keeps type, tags, and approximate parameter size (in bytes)."
  [resource]
  (let [rtype (resource "type")
        title (modify-title (resource "title") "clone")
        tags (resource "tags")
        file (rnd/random-pp-path)
        clone (rnd/random-resource rtype title {"tags" tags "file" file})]
    (assoc clone "parameters" (rebuild-parameters (resource "parameters")))))

(defn mod-resource
  "Updates resource by touching parameters."
  [{:keys [resource-hash] :as work-cat} rkey]
  (let [resource (resource-hash rkey)]
    (assoc-in work-cat [:resource-hash rkey "parameters"]
              (touch-parameters (resource "parameters")))))

(defn add-resource
  "Adds a new resource. The new resource is built to be the same type and of a
  similar weight as the given resource. This helps keep the catalog relatively
  stable in overall weight when resources are dropped by del-resource.

  If include-edges is true, a single leaf edge is created with a source
  from the given set of original-keys. This array is passed in and does not
  contain any of the 'clone-*' resources created by add-resource. This prevents
  nested relationships from forming between added resources, and in turn allows
  del-resource in the include-edges case to simply drop a cloned resource and
  its edge without breaking the graph validated by PuppetDB on injestion."
  [{:keys [original-keys include-edges] :as work-cat} resource-to-clone]
  (let [new-resource (clone-resource resource-to-clone)
        new-rkey {"type" (new-resource "type") "title" (new-resource "title")}
        reworked-cat (assoc-in work-cat [:resource-hash new-rkey] new-resource)]
    (if include-edges
      (update reworked-cat :edges #(conj % {"source" (rand-nth original-keys)
                                               "target" new-rkey
                                               "relationship" "contains"}))
      reworked-cat)))

(defn del-resource
  "Return the resource hash with chosen resource removed.

  But if we have edges, instead choose a resource from the list of cloned
  resources (from add-resource actions). This is so we can just drop the single
  leaf edge associated with the cloned resource (we're careful in add-resource
  to only form a contain relation with original uncloned resources).

  If no cloned resources are available to choose from, do nothing, so as not to
  break the graph."
  [{:keys [resource-hash include-edges] :as work-cat} rkey]
  (if-not include-edges
    (update work-cat :resource-hash #(dissoc % rkey))
    (let [cloned-keys (filter #(str/starts-with? (% "title") "clone-")
                              (keys resource-hash))
          safe-key (when (seq cloned-keys)
                     (rand-nth cloned-keys))]
      (if (nil? safe-key)
        ;; no added cloned leaf resource found, do nothing so as to preserve edges.
        work-cat
        (-> work-cat
          (update :resource-hash #(dissoc % safe-key))
          ;; and remove the single contained edge associated with this cloned resource.
          (update :edges (fn [edges]
                           (doall
                             (remove (fn [{:strs [target]}] (= target safe-key))
                                     edges)))))))))

(defn change-resources
  "Dispatches resource change based on operation.

  Makes two judgements:

  1) If the selected resource has large blob parameters it routes an :add or :del
  operation to :mod so as to preserve the overall lumpiness of the catalog.
  Without this, over time, deletes could drop the blob resources, evening out
  catalogs unintentionally.

  2) If there is only one resource a :del becomes a :mod.

  NOTE: regarding uniformity, we probably need to revist this, since overtime,
  depending on the number of original resources, it grows more likely the
  catalog will reach 1 resource and thereafter all resources will be clones of
  that single resource."
  [operation {:keys [resource-hash] :as work-cat}]
  (let [rkey (rand-nth (keys resource-hash))
        resource (resource-hash rkey)
        rcount (count resource-hash)
        has-blob? (resource-has-blob? resource)
        final-op (cond
                   has-blob? :mod ;; do not add/del a resource with blob param
                   (and (= operation :del)
                        (= 1 rcount))
                             :mod ;; do not del the last resource
                   :else operation)]
    (case final-op
      :add (add-resource work-cat resource)
      :del (del-resource work-cat rkey)
      :mod (mod-resource work-cat rkey))))

(defn mod-random-resource
  [work-cat]
  (change-resources :mod work-cat))

(defn add-random-resource
  [work-cat]
  (change-resources :add work-cat))

(defn del-random-resource
  [work-cat]
  (change-resources :del work-cat))

(def mutate-resource-fns
  "Functions that randomly change a catalog's resources."
  [add-random-resource
   mod-random-resource
   del-random-resource])

(defn add-catalog-varying-fields
  "This function adds the fields that change when there is a different
  catalog. code_id and catalog_uuid should be different whenever the
  catalog is different"
  [catalog]
  (assoc catalog
         "catalog_uuid" (kitchensink/uuid)
         "code_id" (rnd/random-sha1)))

(defn rand-catalog-mutation
  "Updates id fields that change with a catalog change, and makes randomize-count
  additions, modifications and/or removals of resources (and edges if
  include-edges is true)."
  [catalog randomize-count include-edges]
  (let [resource-hash (into {} (for [{:strs [type title] :as r} (catalog "resources")]
                                 [{"type" type "title" title} r]))
        original-keys (if-not include-edges
                        '()
                        (remove (fn [k] (str/starts-with? (k "title") "clone-"))
                                (keys resource-hash)))
        work-cat {:resource-hash resource-hash
                  :include-edges include-edges
                  :edges (catalog "edges")
                  :original-keys original-keys}
        mutated (nth (iterate #((rand-nth mutate-resource-fns) %) work-cat)
                     randomize-count)]
    (assoc (add-catalog-varying-fields catalog)
           "resources" (vals (mutated :resource-hash))
           "edges" (mutated :edges))))

(defn- pick-random-count-or-change
  "Randomly chooses element of --rand-catalogs or --rand-facts arguments based on
  PERCENT-CHANCE values and returns its CHANGE-COUNT or PERCENT-CHANGE value,
  or 0 if no arg was selected."
  [rand-array]
  (let [selected-rand-arg (some #(if (<= (rand 1) (first %)) % nil) rand-array)]
    (if (nil? selected-rand-arg) 0 (last selected-rand-arg))))

(defn update-catalog
  "Updates catalog timestamps and transaction UUIDS that vary with every
  catalog run. Depending on settings in the rand-catalogs array, may make
  additional random changes to catalog resources."
  [catalog include-edges rand-catalogs uuid stamp]
  (let [stamped-catalog (assoc catalog
                         "producer_timestamp" stamp
                         "transaction_uuid" uuid
                         "producer" (random-producer))
        randomize-count (pick-random-count-or-change rand-catalogs)]
    (if (> randomize-count 0)
      (rand-catalog-mutation stamped-catalog randomize-count include-edges)
      stamped-catalog)))

(defn jitter
  "jitter a timestamp (rand-int n) seconds in the forward direction"
  [stamp n]
  (time/plus stamp (time/seconds (rand-int n))))

(defn update-report-resources [resources stamp]
  (let [timestamp (jitter stamp 300)
        update-timestamps-fn (fn [resources-or-events]
                               (map #(assoc % "timestamp" timestamp)
                                    resources-or-events))]
    (->> resources
         update-timestamps-fn
         (map #(update % "events" update-timestamps-fn)))))

(defn update-report
  "configuration_version, start_time and end_time should always change
   on subsequent report submittions, this changes those fields to avoid
   computing the same hash again (causing constraint errors in the DB)"
  [report uuid stamp]
  (-> report
      (update "resources" update-report-resources stamp)
      (assoc "configuration_version" (kitchensink/uuid)
             "transaction_uuid" uuid
             "start_time" (time/minus stamp (time/seconds 10))
             "end_time" (time/minus stamp (time/seconds 5))
             "producer_timestamp" stamp
             "producer" (random-producer))))

(defn randomize-map-leaf
  "Randomizes a fact leaf."
  [leaf]
  (cond
    (string? leaf) (rnd/random-string (inc (rand-int 100)))
    (integer? leaf) (rand-int 100000)
    (float? leaf) (* (rand) (rand-int 100000))
    (boolean? leaf) (rnd/random-bool)))

(defn randomize-map-leaves
  "Runs through a map and randomizes a random percentage of leaves."
  [rand-perc value]
  (cond
    (map? value)
    (kitchensink/mapvals (partial randomize-map-leaves rand-perc) value)

    (coll? value)
    (map (partial randomize-map-leaves rand-perc) value)

    :else
    (if (< (rand 100) rand-perc)
      (randomize-map-leaf value)
      value)))

(defn update-factset
  "Updates the producer_timestamp to be current, and randomly updates the leaves
   of the factset based on a percentage provided in `rand-percentage`."
  [factset rand-facts stamp]
  (let [rand-percentage (pick-random-count-or-change rand-facts)]
    (-> factset
        (assoc "producer_timestamp" stamp
               "producer" (random-producer))
        (update "values" (partial randomize-map-leaves rand-percentage)))))

(defn update-host
  "Perform a simulation step on host-map. Always update timestamps and uuids;
  randomly mutate other data depending on rand-catalogs and rand-facts "
  [{:keys [_host catalog report factset] :as state} include-edges rand-catalogs rand-facts get-timestamp]
  (let [stamp (get-timestamp)
        uuid (kitchensink/uuid)]
    (assoc state
           :catalog (some-> catalog (update-catalog include-edges rand-catalogs uuid stamp))
           :factset (some-> factset (update-factset rand-facts stamp))
           :report (some-> report (update-report uuid stamp)))))

(defn validate-options
  [options]
  (cond
    (and (seq (:querier options))
         (or (contains? options :runinterval) (contains? options :nummsgs)))
    (utils/throw-sink-cli-error "Error: --nummsgs, --runinterval, and --querier are mutually exclusive.")

    (and (kitchensink/missing? options :runinterval :nummsgs)
         (empty? (:querier options)))
    (utils/throw-sink-cli-error "Error: must specify --nummsgs, --runinterval, or --querier.")

    (and (contains? options :archive)
         (not (kitchensink/missing? options :reports :catalogs :facts)))
    (utils/throw-sink-cli-error
     "Error: -A/--archive is incompatible with -F/--facts, -C/--catalogs, -R/--reports")

    (:rand-perc options)
    (utils/throw-sink-cli-error "Error: --rand-perc is deprecated; use --rand-catalogs and/or --rand-facts instead.")

    (and (not-empty (:rand-catalogs options))
         (> (some-> options :rand-catalogs last first) 1.0))
    (utils/throw-sink-cli-error "Error: --rand-catalogs percentages cannot sum to more than 100%.")

    (and (not-empty (:rand-facts options))
         (> (some-> options :rand-facts last first) 1.0))
    (utils/throw-sink-cli-error "Error: --rand-facts percentages cannot sum to more than 100%.")

    ;; NOTE: this should be the last test before :else, lest it short circuit
    ;; other validations.
    (and (contains? options :runinterval) (contains? options :nummsgs))
    (do
      (println-err "Warning: -N/--nummsgs and -i/--runinterval provided. Running in --nummsgs mode.")
      options)

    :else options))

(defn- parse-querier
  [s]
  (let [split (str/split s #":")]
    (case (count split)
      0 false
      1 [1 (first split)]
      2 [(Integer/parseInt (first split)) (second split)]
      false)))

(defn- parse-rand-opt
  "Parses --rand-catalogs and --rand-facts arguments of the form
  PERCENT-CHANCE:CHANGE-COUNT or PERCENT-CHANCE:PERCENT-CHANGE, where
  PERCENT-CHANCE may be an integer or float percentage, CHANGE-COUNT must
  be a postive integer, and PERCENT-CHANGE may be an integer or float percentage."
  ([s]
   (parse-rand-opt s true))
  ([s change-count?]
   (let [split (str/split s #":")
         per-parser #(/ (Double/parseDouble %) 100)
         int-parser #(Integer/parseInt %)]
     (case (count split)
       0 false
       1 false
       2 [(per-parser (first split))
          (if change-count?
            (int-parser (second split))
            (per-parser (second split)))]
       false))))

(defn- parse-rand-facts-opt
  [s]
  (parse-rand-opt s false))

(defn- update-rand-opt
  "Conjoins multiple --rand-catalogs or --rand-facts options into an array,
  summing successive percentages to provide a continuum for later testing."
  [rand-opts next-opt]
  (let [updated-opt (if (empty? rand-opts)
                      next-opt
                      (update-in next-opt [0] + (first (last rand-opts))))]
    (conj rand-opts updated-opt)))

(defn- validate-cli!
  [args]
  (let [threads (.availableProcessors (Runtime/getRuntime)) ;; actually hyperthreads
        pre "usage: puppetdb benchmark (-n HOST_COUNT | --querier [N:]CATEGORY) ...\n\n"
        specs [["-c" "--config CONFIG" "Path to config or conf.d directory (required)"
                :parse-fn config/load-config]
               [nil "--protocol (http|https)" "Network protocol (default via CONFIG)"
                :validate [#(#{"http" "https"} %) "--protocol not http or https"]]
               ["-F" "--facts FACTS" "Directory of *.json sample factsets"]
               ["-C" "--catalogs CATALOGS" "Directory of *.json sample catalogs"]
               ["-R" "--reports REPORTS" "Directory of *.json sample reports"]
               ["-A" "--archive ARCHIVE" "PuppetDB export tarball (conflicts with -C, -F or -R)"]
               ["-i" "--runinterval RUNINTERVAL"
                "Simulation interval (minutes); uses TMPDIR (or java.io.tmpdir)"
                :parse-fn #(Integer/parseInt %)]
               ["-n" "--numhosts N" "Simulated host count (required)"
                :parse-fn #(Integer/parseInt %)]
               ["-r" "--rand-perc PERCENT"
                "DEPRECATED chance each command will be altered. (See --rand-catalogs and --rand-facts.)"]
               [nil "--rand-catalogs PERCENT-CHANCE:CHANGE-COUNT"
                "PERCENT-CHANCE each catalog will have CHANGE-COUNT resources altered."
                :multi true :default []
                :update-fn update-rand-opt
                :parse-fn parse-rand-opt
                :validate [vector? "must be PERCENT-CHANCE:CHANGE-COUNT"
                           #(> (first %) 0) "PERCENT-CHANCE must be positive"
                           #(> (second %) 0) "CHANGE-COUNT must be postive"]]
               [nil "--rand-facts PERCENT-CHANCE:PERCENT-CHANGE"
                "PERCENT-CHANCE each factset will have PERCENT-CHANGE facts altered."
                :multi true :default []
                :update-fn update-rand-opt
                :parse-fn parse-rand-facts-opt
                :validate [vector? "must be PERCENT-CHANCE:PERCENT-CHANGE"
                           #(> (first %) 0) "PERCENT-CHANCE must be positive"
                           #(> (second %) 0) "PERCENT-CHANGE must be postive"]]
               ["-N" "--nummsgs N" "Command sets to send per host (set depends on -F -C -R)"
                :parse-fn #(Long/valueOf ^String %)]
               ["-e" "--end-commands-in PERIOD" "End date for a command set"
                :default-desc "0d"
                :default (time/parse-period "0d")
                :parse-fn #(time/parse-period %)]
               [nil "--senders N" "Message submitters (default: host threads / 2, min 2)"
                :default (max 2 (long (/ threads 2)))
                :parse-fn #(Integer/parseInt %)]
               ["-t" "--threads N" "Deprecated alias for --senders"
                :parse-fn #(Integer/parseInt %)
                :id :senders]
               [nil "--simulators N" "Command simulators (default: host threads / 2, min 2)"
                :default (max 2 (long (/ threads 2)))
                :parse-fn #(Integer/parseInt %)]
               [nil "--concurrent-hosts N"
                "Hosts simultaneously submitting commands (default: heap MB / 3)"
                ;; Slightly conservative default selected by
                ;; observation (i.e. affect on heap in visualvm using
                ;; current sample-data).
                :default (long (/ (.maxMemory (Runtime/getRuntime)) 1024 1024 3))
                :parse-fn #(Integer/parseInt %)]
               [nil "--simulation-dir DIR" "Persistent host state directory (allows resume)"]
               ["-o" "--offset N" "Host cert number offset (start with host-N)"
                :default 0
                :parse-fn #(Integer/parseInt %)]
               [nil "--include-catalog-edges" "Include catalog edges in the data submitted to PuppetDB"]
               [nil "--catalog-query-pct PERCENT" "Chance catalog will query before send"
                :default 0
                :parse-fn #(/ (Double/parseDouble %) 100)
                :validate [#(<= 0 % 100) "Must be a percentage"]
                :id :catalog-query-prob]
               [nil "--catalog-query-n N" "Query count before send"
                :default 0
                :parse-fn #(Integer/parseInt %)
                :validate [#(>= % 0) "Must not be negative"]]
               [nil "--querier [N:]CATEGORY" "Run N random queriers for CATEGORY in queries.edn"
                :multi true :default [] :update-fn conj
                :parse-fn parse-querier
                :validate [vector? "must be CATEGORY or N:CATEGORY"]]]
        post ["\n"
              "The PERIOD (e.g. '3d') will typically be slightly in the future to account for\n"
              "the time it takes to finish processing a set of historical records (so node-ttl\n"
              "will be further away)\n"
              "\n"
              "Currently, queries.edn is in resources.puppetlabs.puppetdb.sample.\n"]
        required [:config]]
    (utils/try-process-cli
     (fn []
       (-> args
           (kitchensink/cli! specs required)
           first
           validate-options))
     {:preamble pre :postamble post})))

(defn process-tar-entry
  [tar-reader]
  (fn [acc ^TarArchiveEntry entry]
    (let [parsed-entry (-> tar-reader
                           archive/read-entry-content
                           json/parse-string)]
      (condp re-find (.getName entry)
        #"catalogs.*\.json$" (update acc :catalogs conj parsed-entry)
        #"reports.*\.json$" (update acc :reports conj parsed-entry)
        #"facts.*\.json$" (update acc :facts conj parsed-entry)
        acc))))

(def default-data-paths
  {:facts "puppetlabs/puppetdb/benchmark/samples/facts"
   :reports "puppetlabs/puppetdb/benchmark/samples/reports"
   :catalogs "puppetlabs/puppetdb/benchmark/samples/catalogs"})

(defn load-data-from-options
  [{:keys [archive] :as options}]
  (if archive
    (let [tar-reader (archive/tarball-reader archive)]
      (->> tar-reader
           archive/all-entries
           (reduce (process-tar-entry tar-reader) {})))
    (let [data-paths (select-keys options [:reports :catalogs :facts])
          [data-paths from-cp?] (if (empty? data-paths)
                                  [default-data-paths true]
                                  [data-paths false])]
      (kitchensink/mapvals #(some-> % (load-sample-data from-cp?)) data-paths))))

(def random-cmd-delay rnd/safe-sample-normal)

(def ^:private post-command
  (if-not discard-all-messages?
    post-json-via-jdk
    discard-json-post))

(defn send-facts [url certname version catalog opts]
  (client/submit-facts url certname version catalog (assoc opts :post post-command)))

(defn send-catalog [url certname version catalog opts]
  (client/submit-catalog url certname version catalog (assoc opts :post post-command)))

(defn send-report [url certname version catalog opts]
  (client/submit-report url certname version catalog (assoc opts :post post-command)))

;;; All command submissions are handled as an event sequence that
;;; proceeds from factset submission to catalog submission (with
;;; optional preceding queries) to report submission.  Any command
;;; types that are unavailable are skipped.
;;;
;;; The next pending event for each host is represented as a
;;; SenderEvent, which will be inserted into the event channel by the
;;; scheduler at the appropriate time, and then the "director" will
;;; handle it via a sender thread.
;;;
;;; The full event sequence, when all command types are available,
;;; looks like this:
;;;
;;;   -> :what :send-facts - never delayed, since first
;;;   -> :what :catalog-queries :queries [q-1 q-2 ...]
;;;   -> :what :catalog-queries :queries [q-2]
;;;   -> :what :send-catalog
;;;   -> :what :send-report
;;;
;;; When a sequence is finished, its :result is updated and the event
;;; is sent on to the rate monitor.

;; This is overloaded to handle both what should be done, and what
;; happened to reduce garbage.
(defrecord SenderEvent
    [what
     start ;; ephemeral start of the whole sequence
     host-info
     result]) ;; for now just true or an exception

(defn- sender-event [what start host-info]
  (assert (contains? #{nil :send-facts :catalog-queries :send-catalog :send-report} what))
  (->SenderEvent what start host-info nil))

(defn- random-fact
  "Returns a random [path value] for a path with a scalar
  value (i.e. not a map or vector), with a \"dot notation\" path,
  e.g. \"major.release.os\" or \"processors.models[2]\". Throws an
  exception if one isn't found after 100 attempts."
  [facts]
  (when-not (map? facts)
    (throw (ex-info (str "Argument is " (class facts) " not factset") {})))
  (letfn [(trace [x result]
            ;; Returns [path-part ... val] (as long as initial result
            ;; is []) where val is [] or {} for empty leaf
            ;; collections.
            (cond
              (map? x)
              (if (empty? x)
                (conj result x)
                (let [[k v] (rand-nth (seq x))]
                  (trace v (conj result (str "." (name k))))))
              (vector? x)
              (if (empty? x)
                (conj result x)
                (let [i (rand-int (count x))]
                  (trace (nth x i) (conj result (str "[" i "]")))))
              :else (conj result x)))]
    (if-let [[candidate] (->> (repeatedly 100 #(trace (facts "values") []))
                               (remove #(coll? (last %))) ;; should be empty
                               (take 1)
                               seq)]
      (let [path (butlast candidate)
            val (last candidate)
            res [(apply str "facts" path) val]]
        res)
      (throw (ex-info "Can't find random scalar fact" {})))))

(def ^:private catalog-query-generators
  ;; Currently at least one of these must return a query for the host,
  ;; otherwise choose-catalog-queries won't return.
  [(fn gen-exported-resource-query [_factset catalog]
     (when-let [exported (seq (->> (catalog "resources") (filterv #(% "exported"))))]
       (let [res (rand-nth exported)]
         ["from" "resources"
          ["and"
           ["=" "exported" true]
           ["=" "type" (res "type")]]])))
   (fn gen-exported-tagged-resource-query [_factset catalog]
     (when-let [exported (seq (->> (catalog "resources") (filterv #(% "exported"))))]
       (let [res (rand-nth exported)]
         ["from" "resources"
          ["and"
           ["=" "exported" true]
           ["=" "type" (res "type")]
           ["=" "tag" (-> (res "tags") vec rand-nth)]]])))
   (fn gen-random-fact-query [factset _catalog]
     (let [[path val] (random-fact factset)]
       ["from" "inventory"
        ["extract" ["certname"]
         ["=" path val]]]))])

(def ^:private show-catalog-query-selection? false)

(defn- choose-catalog-queries
  "Returns n randomly chosen, possibly duplicate, queries."
  [{:keys [factset catalog] :as _host-info} probability n]
  (let [limit (* n 10) ;; for now, only allow 10 attempts per query
        qs (if (or (zero? n) (zero? probability) (> (rand) probability))
             []
             (->> (repeatedly limit #((rand-nth catalog-query-generators) factset catalog))
                  (remove nil?)
                  (take n)))]
    (when (not= n (count qs))
      ;; FIXME: currently this just causes benchmark to hang
      (throw
       (ex-info (trs "Unable to generate requested {0} queries in {1} attempts"
                     n limit)
                {})))
    (when show-catalog-query-selection?
      (binding [*out* *err*]
        (println "Catalog queries:")
        (pprint qs)))
    qs))

(defn- schedule-event
  [scheduler event delay-ms event-ch]
  (try
    (schedule scheduler (fn request-event [] (>!! event-ch event)) delay-ms)
    (catch RejectedExecutionException _)))

(defn- first-catalog-event
  [base queries]
  (if (seq queries)
    (assoc base :what :catalog-queries :queries queries)
    (-> base (dissoc :queries) (assoc :what :send-catalog))))

(defn- cmd-url [base]
  (assoc base :prefix "/pdb/cmd" :version :v1))

(defn- build-query-uri [base]
  (URI. (:protocol base) nil (:host base) (:port base) "/pdb/query/v4" nil nil))

(def ^:private query-uri (memoize build-query-uri))

;;; The handle-* functions handle the various types of events found on
;;; the event-ch via the director.  In general, we re-use the event
;;; where possible (e.g. just change :what, :result, etc.) to reduce
;;; garbage.

(defn- report-sender-ex [ex]
  (binding [*out* *err*]
    (println "Send failed:" (str ex))
    (println ex)))

(defn- handle-send-facts
  [{:keys [host-info what] :as event} base-url ssl-opts scheduler event-delay event-ch seq-end
   {:keys [catalog-query-prob catalog-query-n] :as _cmd-opts}]
  (assert (= :send-facts what))
  (let [{:keys [host factset catalog report]} host-info
        result (try!
                 (send-facts (cmd-url base-url) host 5 factset ssl-opts)
                 (assoc event :result true)
                 (catch IOException ex
                   (report-sender-ex ex)
                   (assoc event :result ex)))]
    (or (cond
          catalog (let [qs (choose-catalog-queries host-info catalog-query-prob catalog-query-n)]
                    (schedule-event scheduler
                                    (first-catalog-event event qs)
                                    (event-delay :send-facts what)
                                    event-ch))
          report (schedule-event scheduler
                                 (assoc event :what :send-report)
                                 (event-delay :send-facts :send-report)
                                 event-ch))
        (seq-end host-info))
    result))

(defn- handle-catalog-queries
  [{:keys [what queries] :as event} base-url ssl-opts scheduler event-delay event-ch seq-end]
  (assert (= :catalog-queries what))
  (assert (seq queries))
  (let [[query & more] queries
        result (try!
                 (time!
                  (:query-duration metrics)
                  (query-pdb-discard-response (query-uri base-url) query ssl-opts))
                 (assoc event :result true)
                 (catch IOException ex
                   (report-sender-ex ex)
                   (assoc event :result ex)))]
    ;; Either move on to the next query, or send a catalog
    (or (if (seq more)
          (schedule-event scheduler (assoc event :queries more)
                          (event-delay :catalog-queries :catalog-queries)
                          event-ch)
          (schedule-event scheduler
                          (-> event (dissoc :queries) (assoc :what :send-catalog))
                          (event-delay :catalog-queries :send-catalog)
                          event-ch))
        (seq-end (:host-info event)))
    result))

(defn- handle-send-catalog
  [event base-url ssl-opts scheduler event-delay event-ch seq-end]
  (assert (= :send-catalog (:what event)))
  (let [host-info (:host-info event)
        {:keys [host catalog report]} host-info
        result (try!
                 (send-catalog (cmd-url base-url) host 9 catalog ssl-opts)
                 (assoc event :result true)
                 (catch IOException ex
                   (report-sender-ex ex)
                   (assoc event :result ex)))]
    (or (cond
          report (schedule-event scheduler (assoc event :what :send-report)
                                 (event-delay :send-catalog :send-report)
                                 event-ch))
        (seq-end host-info))
    result))

(defn- handle-send-report
  [{:keys [what host-info] :as event} base-url ssl-opts seq-end]
  (assert (= :send-report what))
  (let [{:keys [host report]} host-info
        result (try!
                 (send-report (cmd-url base-url) host 8 report ssl-opts)
                 (assoc event :result true)
                 (catch IOException ex
                   (report-sender-ex ex)
                   (assoc event :result ex)))]
    (seq-end host-info)
    result))

;;; The start-with-* functions initiate a sequence of delayed events for
;;; the given host-info.

(defn- start-with-facts
  [host-info start base-url ssl-opts scheduler event-delay event-ch seq-end cmd-opts]
  (handle-send-facts (sender-event :send-facts start host-info)
                     base-url ssl-opts scheduler event-delay event-ch seq-end
                     cmd-opts))

(defn- start-with-catalog
  [host-info start base-url ssl-opts scheduler event-delay event-ch seq-end
   {:keys [catalog-query-prob catalog-query-n] :as _cmd-opts}]
  (let [event (first-catalog-event (sender-event nil start host-info)
                                   (choose-catalog-queries host-info
                                                           catalog-query-prob
                                                           catalog-query-n))]
    (case (:what event)
      :catalog-queries
      (handle-catalog-queries event base-url ssl-opts scheduler event-delay event-ch seq-end)
      :send-catalog
      (handle-send-catalog event base-url ssl-opts scheduler event-delay event-ch seq-end))))

(defn- start-with-report
  [host-info start base-url ssl-opts seq-end]
  (handle-send-report (sender-event :send-report start host-info)
                      base-url ssl-opts seq-end))

(defn director
  [base-url ssl-opts scheduler
   {:keys [max-command-delay-ms] :as cmd-opts}
   event-ch seq-end]
  (fn stage-event [event]
    ;; For now, no delay between the catalog queries and the catalog
    ;; submission.  Everything else gets the same delay.
    (let [event-delay (fn event-delay [prev next]
                        (assert (keyword? prev))
                        (assert (keyword? next))
                        (case prev
                          :catalog-queries 0
                          (random-cmd-delay 5000 3000
                                            {:lowerb 500
                                             :upperb max-command-delay-ms})))]
      (if-not (instance? SenderEvent event)
        ;; New host-info - start the sequence
        (let [now (time/ephemeral-now-ns)]
          (cond
            (:factset event)
            (start-with-facts event now base-url ssl-opts scheduler event-delay event-ch seq-end cmd-opts)
            (:catalog event)
            (start-with-catalog event now base-url ssl-opts scheduler event-delay event-ch seq-end cmd-opts)
            (:report event)
            (start-with-report event now base-url ssl-opts seq-end)
            :else (throw (ex-info "unexpected host info" event))))

        (case (:what event)
          :catalog-queries
          (handle-catalog-queries event base-url ssl-opts scheduler event-delay event-ch seq-end)
          :send-catalog
          (handle-send-catalog event base-url ssl-opts scheduler event-delay event-ch seq-end)
          :send-report
          (handle-send-report event base-url ssl-opts seq-end)
          (throw (ex-info "unexpected event" event)))))))

(defn- semaphore-permit-dispenser
  "Returns a channel that returns a permit (true) whenever one is
  available from the given semaphore.  The quit? atom should be set to
  true when the dispenser is no longer needed, to reclaim the thread,
  etc., and cause the channel to close."
  [^Semaphore sem quit?]
  (let [now-serving (chan)]
    (noisy-future
     (try!
       (loop []
         (let [permit (.tryAcquire sem 300 TimeUnit/MILLISECONDS)]
           (if @quit?
             (when permit (.release sem))
             (if-not permit
               (recur)
               (if (>!! now-serving true)
                 (recur)
                 (.release sem))))))
       (finally
         (async/close! now-serving))))
    now-serving))

(defn- start-command-sender
  "Start a command sending process in the background. Reads host-state maps from
  host-info-ch and sends commands to the puppetdb at base-url. Writes
  ::submitted to rate-monitor-ch for every command sent, or ::error if there was
  a problem. Close host-info-ch to stop the background process."
  [base-url host-info-ch sim-ch rate-monitor-ch ssl-opts scheduler cmd-opts
   {:keys [concurrent-hosts senders]}]
  (let [stop-ch (chan)
        host-throttle-ch (chan)
        event-ch (chan)
        sender-ch (chan)
        state (atom {:more-hosts? true :pending-sequences 0
                     :validator #(-> % :pending-sequences (>= 0))})

        quit? (atom false)
        host-throttle (Semaphore. concurrent-hosts)

        seq-end (fn seq-ended [host-info]
                  (let [state (swap! state update :pending-sequences dec)]
                    (if (and (zero? (:pending-sequences state))
                             (not (:more-hosts? state)))
                      (async/close! event-ch)
                      (do
                        (>!! sim-ch (:host-path host-info))
                        (.release host-throttle)))))
        stage-event (director base-url ssl-opts scheduler cmd-opts event-ch seq-end)]

    ;; Send host-info and events to the senders, with events having
    ;; priority.  Divert the host-info through the host-throttle-ch to
    ;; enforce the --concurrent-hosts limit (i.e. maxmumum number of
    ;; hosts that can have sequences "in flight").
    ;;
    ;; Giving the event channel priority ensuares we never generate
    ;; new delayed work (events) from a new host-info until we've
    ;; dealt with any previously generated work that's ready.

    ;; Host throttle: shovel host-infos from the simulators
    ;; host-info-ch to the host-throttle-ch (which feeds in to the
    ;; main "prioritizing" shovel below), Assumes the other shovel
    ;; will set quit? and close the host-throttle-ch when it's time to
    ;; stop.  The permit dispenser (via semaphore) limits the number of
    ;; concurrently active hosts since we acquire a permit here for
    ;; each host-info we pass along, and the permits are only released
    ;; at the end of the host sequence in seq-end.
    (go
      (try!
        (loop [info (<! host-info-ch)
               now-serving (semaphore-permit-dispenser host-throttle quit?)]
          (when (and info (<! now-serving) (>! host-throttle-ch info))
            (recur (<! host-info-ch) now-serving)))
        (finally
          (reset! quit? true)
          (async/close! host-throttle-ch))))

    ;; Main "prioritizing" host/event shovel: pull host-infos and
    ;; events from the host throttle and the scheduler event channel
    ;; respectively (prioritizing events), and pass them to the
    ;; senders.  Critical that this be serialized wrt more-hosts? vs
    ;; pending-sequences state updates.  Currently, that's arranged by
    ;; having this single loop be the one that checks the pending
    ;; count, since it's also the only thing that can generate new
    ;; pending work (incrementing the count), via the director.
    (go
      (try!
        (loop [srcs [stop-ch event-ch host-throttle-ch]]
          (let [[event-or-info c] (async/alts! srcs :priority true)]
            (if-not (nil? event-or-info)
              ;; Forward event
              (do
                (assert (not (= c stop-ch))) ;; only allow close
                (when (= c host-throttle-ch)
                  (swap! state update :pending-sequences inc))
                (>! sender-ch event-or-info)
                (recur srcs))
              ;; Something closed
              (when-not (= c stop-ch)
                ;; Keep going unless we're down to just the close channel,
                ;; or the incoming host channel has closed and there's
                ;; nothing in-flight (i.e. delayed).
                (let [{:keys [more-hosts? pending-sequences]}
                      (if (= c host-throttle-ch)
                        (swap! state assoc :more-hosts? false)
                        @state)
                      srcs (filterv #(not= % c) srcs)]
                  (when (and (not= srcs [stop-ch])
                             (or more-hosts? (pos? pending-sequences)))
                    (recur srcs)))))))
        (finally
          (reset! quit? true)
          (async/close! host-throttle-ch)
          (async/close! event-ch) ;; remaining events won't block
          (async/close! sender-ch))))

    ;; Start the senders
    [state
     stop-ch
     (async/pipeline-blocking senders rate-monitor-ch (map stage-event) sender-ch)]))

(defn start-rate-monitor
  "Start a task which monitors the rate of messages on rate-monitor-ch and
  prints it to the console every 5 seconds. Uses run-interval to compute the
  number of nodes that would produce that load."
  [rate-monitor-ch run-interval commands-per-puppet-run _state]
  (let [run-interval-seconds (time/in-seconds run-interval)
        expected-node-message-rate (/ commands-per-puppet-run run-interval-seconds)]
    (println-err
     (str "q/s - queries per second\n"
          "s/q - seconds per query (average completion time)\n"
          "err - command or query errors during interval"))
    (go-loop [cmds 0
              queries 0
              errors 0
              last-report-time (System/currentTimeMillis)]
      (when-let [event (<! rate-monitor-ch)]
        (let [t (System/currentTimeMillis)
              time-diff (- t last-report-time)
              [cmds queries errors]
              (case (:what event)
                (:send-facts :send-catalog :send-report)
                (if (instance? Exception (:result event))
                  [cmds queries (inc errors)]
                  [(inc cmds) queries errors])
                :catalog-queries
                (if (instance? Exception (:result event))
                  [cmds queries (inc errors)]
                  [cmds (inc queries) errors]))]
          (if (> time-diff 5000)
            (let [time-diff-seconds (/ time-diff 1000)
                  cmd-per-sec (float (/ cmds time-diff-seconds))
                  query-per-sec (float (/ queries time-diff-seconds))]
              (println-err
               (trs "{0} cmd/s (~{1} nodes @ {2}m) | {3} q/s {4} s/q | {5} err"
                    cmd-per-sec
                    (int (/ cmd-per-sec expected-node-message-rate))
                    (time/in-minutes run-interval)
                    (format "%.2f" query-per-sec)
                    (format "%.2f" (-> metrics :query-duration timers/mean (/ 1000000000)))
                    errors))
              (recur 0 0 0 t))
            (recur cmds queries errors last-report-time)))))))

(def benchmark-shutdown-timeout 5000)

(def ^"[Ljava.nio.file.OpenOption;" no-open-options (into-array OpenOption []))

(defn write-host-info [info ^Path path]
  (let [host (:host info)
        tmp (Files/createTempFile (.getParent path) host "-tmp"
                                  (into-array FileAttribute []))]
    (try!
      (assert host)
      (ignore-value (Files/write tmp ^"[B" (nippy/freeze info) no-open-options))
      (Files/move tmp path (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                   StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (Files/deleteIfExists tmp)))))

(defn- host->host-path [host storage-dir]
  (.resolve ^Path storage-dir ^String host))

(defn populate-hosts
  "Returns a lazy sequence of host info maps, reading the data from
  storage-dir when a suitable file exists, and deriving the data from
  catalogs, reports, and facts otherwise."
  [n offset pdb-host include-edges? catalogs reports facts storage-dir]
  (for [i (range n)]
    (let [random-entity (fn random-entity [host entities]
                          (some-> entities
                                  rand-nth
                                  (assoc "certname" host)))
          random-catalog (fn random-catalog [host pdb-host catalogs]
                           (when-let [cat (if include-edges?
                                            (random-entity host catalogs)
                                            (some-> (random-entity host catalogs)
                                                    (assoc "edges" [])))]
                             (update cat "resources"
                                     (partial map #(update % "tags"
                                                           conj
                                                           pdb-host)))))
          augment-host (fn augment-host [info]
                         ;; Adjust the preserved host-map to match current flags.
                         (let [host (:host info)
                               _ (assert host)
                               new (cond-> info
                                     (and facts (nil? (:factset info)))
                                     (assoc :factset (random-entity host facts))
                                     (and catalogs (nil? (:catalog info)))
                                     (assoc :catalog (random-catalog host pdb-host catalogs))
                                     (and reports (nil? (:report info)))
                                     (assoc :report (random-entity host reports)))]
                           (if (or include-edges? (nil? (:catalog new)))
                             new
                             (assoc-in new [:catalog "edges"] []))))
          host (str "host-" (+ offset i))
          host-path (host->host-path host storage-dir)]
      (if-let [data (try
                      (Files/readAllBytes host-path)
                      (catch NoSuchFileException _))]
        (-> data nippy/thaw augment-host)
        {:host host
         :catalog (random-catalog host pdb-host catalogs)
         :report (random-entity host reports)
         :factset (random-entity host facts)}))))

(defn progressing-timestamp
  "Return a function that will return a timestamp that progresses forward in time."
  [num-hosts num-msgs run-interval-minutes end-commands-in]
  (if num-msgs
    (let [msg-interval (* num-msgs run-interval-minutes)
          ;; Does not need to be multiplied by 3 (for reports/catalogs/factsets) because
          ;; each set of commands for a node use the same timestamp
          timestamp-increment-ms (/ (* 60 1000 msg-interval) (* num-hosts num-msgs))
          timestamp (atom (-> (time/from-now end-commands-in)
                              (time/minus (time/minutes msg-interval))))]
      ;; Return a function that will backdate previous messages
      ;; helpful for submiting reports that will populate old partition
      (fn []
        (swap! timestamp time/plus (time/millis timestamp-increment-ms))))
    ;; When running in the continuous runinterval mode, provide a bit
    ;; of random variation from now. The timestamps are spread out over
    ;; the course of the run by the thread sleeps in the channel read.
    (fn []
      (jitter (now) run-interval-minutes))))

(defn prune-host-info
  "Adjusts the info to match the current run, i.e. if the current run
  didn't specify --catalogs, then prune it.  We might have extra data
  when using a simulation dir from a previous run with different
  arguments."
  [info factsets catalogs reports]
  (cond-> info
    (not factsets) (dissoc :factset)
    (not catalogs) (dissoc :catalog)
    (not reports) (dissoc :report)))

(defn start-simulation-loop
  "Run a background process which takes host-state maps from read-ch, updates
  them with update-host, and puts them on write-ch. If num-msgs is not given,
  uses numhosts and run-interval to run the simulation at a reasonable rate.
  Close read-ch to terminate the background process."
  [numhosts run-interval num-msgs end-commands-in rand-catalogs rand-facts
   simulation-threads sim-ch host-info-ch read-ch
   & {:keys [facts catalogs reports include-edges? storage-dir]}]
  (let [run-interval-minutes (time/in-minutes run-interval)
        hosts-per-second (/ numhosts (* run-interval-minutes 60))
        ms-per-message (/ 1000 hosts-per-second)
        ms-per-thread (* ms-per-message simulation-threads)
        progressing-timestamp-fn (progressing-timestamp numhosts num-msgs run-interval-minutes
                                                        end-commands-in)
        stop-ch (chan)]
    [stop-ch
     (async/pipeline-blocking
      simulation-threads
      host-info-ch
      ;; As currently arranged, the initial host state will never
      ;; reach the senders because we advance it before sending.
      ;; Alternately, we could prune/send host-state, but that'd be
      ;; sending potentially arbitrarily stale data (from existing
      ;; simulation dir), and even ignoring that, it'd be sending
      ;; commands after the sleep that have timestamps that were
      ;; chosen in the last iteration.
      (map (fn advance-host [host-path-or-info]
             (let [deadline (+ (time/ephemeral-now-ns) (* ms-per-thread 1000000))
                   [host-path host-state]
                   (if (map? host-path-or-info)
                     [(-> host-path-or-info :host (host->host-path storage-dir))
                      host-path-or-info]
                     [host-path-or-info
                      (-> host-path-or-info Files/readAllBytes nippy/thaw)])
                   new-state (update-host host-state include-edges?
                                          rand-catalogs rand-facts
                                          progressing-timestamp-fn)]
               (write-host-info new-state host-path)
               (when (and (not num-msgs) (> deadline (time/ephemeral-now-ns)))
                 (async/alt!!
                   stop-ch (doseq [c [read-ch sim-ch host-info-ch]] (async/close! c))
                   (async/timeout (int (/  (- deadline (time/ephemeral-now-ns)) 1000000))) nil))
               (assoc (prune-host-info new-state facts catalogs reports)
                      :host-path host-path))))
      read-ch)]))

(defn warn-missing-data [catalogs reports facts]
  (when-not catalogs
    (println-err (trs "No catalogs specified; skipping catalog submission")))
  (when-not reports
    (println-err (trs "No reports specified; skipping report submission")))
  (when-not facts
    (println-err (trs "No facts specified; skipping fact submission"))))

(defn register-shutdown-hook! [^Runnable f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

(defn create-storage-dir
  "Returns a Path to the directory where simulation host-maps are stored.

  If simulation-dir is set, then the path will be the absolute-path to
  simulation-dir. Otherwise a temporary directory will be created in tmpdir.

  The directory is created as a side effect of calling this method if it does
  not already exist. Parent directories are not created."
  [simulation-dir]
  (if (nil? simulation-dir)
    ;; Create a tmp directory and return that path.
    (let [temp-dir (get-path (or (System/getenv "TMPDIR")
                                 (System/getProperty "java.io.tmpdir")))]
      (Files/createTempDirectory temp-dir
                                 "pdb-bench-"
                                 (into-array FileAttribute [])))
    ;; Otherwise, ensure that the given simulation-dir is created
    (let [absolute-path (.toAbsolutePath (get-path simulation-dir))]
      (try
        (Files/createDirectory absolute-path (make-array FileAttribute 0))
        (catch FileAlreadyExistsException _
          (when-not (Files/isDirectory absolute-path (make-array LinkOption 0))
            (throw (Exception. (trs "The --simulation-dir path {0} is not a directory" absolute-path))))))
      ;; and return the Path
      absolute-path)))

;; The core.async processes and channels fit together like
;; this (square brackets indicate data being sent, parens channels):
;;
;; (initial-hosts-ch)
;;    |
;;  [host-map]
;;    |
;;    v
;; (mix: sim-input-ch sim-next-ch) < --- [host-path] ------\
;;    |                                                    |
;;    v                                                    |
;; simulators                                              |
;;    |                                                    |
;; (host-info-ch)                                          |
;;    |                                                    |
;;  [host-info incl host-path]                             |
;;    |                                                    |
;;    v                                                    |
;; (host-throttle-ch)                                      |
;;    |                                                    |
;;    v                                                    |
;; event-prioritizer <-- [event (SenderEvent)]----\        |
;;    |                                           |        |
;;    v                                           |        |
;;  [event | host-info]                       scheduler    |
;;    |                                           |        |
;;    v                                           |        |
;; sender ---------------[event (SenderEvent)] ---/        |
;;    |                                                    |
;;    |------[host-path] ----------------------------------/
;;    |
;;    v
;;  [event]
;;    |
;;    v
;; rate-monitor
;;
;; In general, closing an upstream channel cascades downstream,
;; cleaning everything up, except for the sender, because even after
;; the host-info-ch has closed, it may still have events pending via
;; the scheduler.  To shut down properly, just close the "stop"
;; channels provided by the simulator and the sender.  When the
;; channel returned by the rate monitor closes, everything should be
;; finished.
;;
;; The host-path sim-next-ch loop ensures that we can't issue more
;; than one command sequence for a given host at a time.
;;
;; The host-throttle-ch restricts the number of hosts that have active
;; command/query sequences to the --concurrent-hosts limit.

(defn pdb-connection-info [{:keys [config] :as options}]
  (let [{:keys [host port ssl-host ssl-port]} (:jetty config)
        [protocol pdb-host pdb-port]
        (case (:protocol options)
          "http" ["http" (or host "localhost") (or port 8080)]
          "https" ["https" (or ssl-host "localhost") (or ssl-port 8081)]
          (cond
            ssl-port ["https" (or ssl-host "localhost") ssl-port]
            ssl-host ["https" ssl-host (or ssl-port 8081)]
            port ["http" (or host "localhost") port]
            host ["http" host (or port 8080)]
            :else ["http" "localhost" 8080]))
        ssl-opts (select-keys (:jetty config) [:ssl-cert :ssl-key :ssl-ca-cert])]
    [protocol pdb-host pdb-port ssl-opts]))

(defn send-commands
  "Feeds commands to PDB as requested by args. Returns a map of :join, a
  function to wait for the benchmark process to terminate (only happens when you
  pass nummsgs), and :stop, function to request termination of the benchmark
  process and wait for it to stop cleanly. These functions return true if
  shutdown happened cleanly, or false if there was a timeout."
  [options]
  (let [{:keys [config rand-catalogs rand-facts numhosts nummsgs
                senders simulators end-commands-in offset
                include-catalog-edges simulation-dir]} options
        _ (logutils/configure-logging! (get-in config [:global :logging-config]))
        {:keys [catalogs reports facts]} (load-data-from-options options)
        _ (warn-missing-data catalogs reports facts)
        [protocol pdb-host pdb-port ssl-opts] (pdb-connection-info options)
        _ (println-err (format "Connecting to %s://%s:%s" protocol pdb-host pdb-port))
        base-url {:protocol protocol :host pdb-host :port pdb-port}
        run-interval (get options :runinterval 30)
        run-interval-minutes (-> run-interval time/minutes)

        cmd-opts (merge {:max-command-delay-ms 15000}
                        (select-keys options [:catalog-query-prob :catalog-query-n]))

        commands-per-puppet-run (+ (if catalogs 1 0)
                                   (if reports 1 0)
                                   (if facts 1 0))
        storage-dir (create-storage-dir simulation-dir)

        ;; channels
        initial-hosts-ch (async/to-chan!!
                          (populate-hosts numhosts offset pdb-host include-catalog-edges
                                          catalogs reports facts storage-dir))
        sim-next-ch (chan numhosts)
        _ (register-shutdown-hook! #(async/close! sim-next-ch))

        host-info-ch (chan)
        rate-monitor-ch (chan)

        sim-input-ch (let [ch (chan)
                           mixer (async/mix ch)]
                       (async/solo-mode mixer :pause)
                       (async/toggle mixer {initial-hosts-ch {:solo true}})
                       (async/admix mixer sim-next-ch)
                       (if nummsgs (async/take (* numhosts nummsgs) ch) ch))

        ^ScheduledThreadPoolExecutor
        event-scheduler (utils/scheduler 1)

        ;; ensures we submit all commands after they are scheduled
        ;; before we tear down the output channel
        _ (.setExecuteExistingDelayedTasksAfterShutdownPolicy event-scheduler true)

        [state send-stop _send-wait]
        (start-command-sender base-url host-info-ch sim-next-ch rate-monitor-ch
                              ssl-opts event-scheduler cmd-opts
                              {:senders senders
                               :concurrent-hosts (:concurrent-hosts options)})

        rate-wait (start-rate-monitor rate-monitor-ch
                                      (-> 30 time/minutes)
                                      commands-per-puppet-run
                                      state)

        [sim-stop _sim-wait]
        (start-simulation-loop numhosts run-interval-minutes nummsgs
                               end-commands-in rand-catalogs rand-facts
                               simulators
                               sim-next-ch
                               host-info-ch
                               sim-input-ch
                               {:facts facts
                                :catalogs catalogs
                                :reports reports
                                :include-catalog-edges? include-catalog-edges
                                :storage-dir storage-dir})

        join-fn (fn join-benchmark
                  ;; Waits for all requested events to finish.
                  ;; Returns true if the operation finished without
                  ;; timing out
                  ([] (join-benchmark nil))
                  ([timeout-ms]
                   (let [t-ch (if timeout-ms (async/timeout timeout-ms) (chan))]
                     (async/alt!! t-ch false rate-wait true))))
        stop-fn (fn stop-benchmark []
                  ;; Requests an immediate halt, abandoning any events
                  ;; in progress.  Returns true if the operation
                  ;; finished without timing out
                  (async/close! sim-stop)
                  ;; Have to stop the sender too since it's not solely
                  ;; dependent on the host-info channel.
                  (async/close! send-stop)
                  (utils/request-scheduler-shutdown event-scheduler true)
                  (or (join-fn benchmark-shutdown-timeout)
                      (do
                        (println-err (trs "Timed out while waiting for benchmark to stop."))
                        false)))
        _ (register-shutdown-hook! stop-fn)]

    {:stop stop-fn
     :join join-fn}))

(defn send-commands-wrapper [args]
  (-> args validate-cli! send-commands))

(defn send-queries
  [options]
  (let [{:keys [config querier]} options
        _ (logutils/configure-logging! (get-in config [:global :logging-config]))
        [protocol pdb-host pdb-port ssl-opts] (pdb-connection-info options)
        _ (println-err (format "Querying %s://%s:%s" protocol pdb-host pdb-port))
        base-url {:protocol protocol :host pdb-host :port pdb-port}
        stop-ch (chan)]
    (register-shutdown-hook! #(async/close! stop-ch))
    (bench-query/cli base-url ssl-opts querier stop-ch)))

(defn cli
  "Runs the benchmark command as directed by the command line args and
  returns an appropriate exit status."
  [args]
  (run-cli-cmd
   #(let [opts (validate-cli! args)]
      (println-err (trs "Press ctrl-c to stop"))
      (if (seq (:querier opts))
        (send-queries opts)
        (let [{:keys [join]} (send-commands opts)]
          (join)
          0)))))

(defn -main [& args]
  (exit (try! (cli args) (finally (shutdown-agents)))))

(set! *warn-on-reflection* warn-on-reflection-orig)
