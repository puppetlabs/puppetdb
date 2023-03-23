(ns puppetlabs.puppetdb.cli.generate
  "Data Generation utility

   This command-line tool can generate a base sampling of catalog, fact and
   report files suitable for consumption by the PuppetDB benchmark utility.

   Note that it is only necessary to generate a small set of initial sample
   data since benchmark will permute per node differences. So even if you want
   to benchmark 1000 nodes, you don't need to generate initial
   catalog/fact/report json for 1000 nodes.

   If you want a representative sample with big differences between catalogs,
   you will need to run the tool multiple times. For example, if you want a set
   of 5 large catalogs and 10 small ones, you will need to run the tool twice
   with the desired parameters to create the two different sets.

   ### Flag Notes

   The num-resources flag is total and includes num-classes. So if you set
   --num-resources to 100 and --num-classes to 30, you will get a catalog with a
   hundred resources, thirty of which are classes.

   A containment edge is always generated between the main stage and each
   class. And non-class resources get a containment edge to a random class. So
   there will always be a base set of containment edges equal to the resource
   count. The --additional-edge-percent governs how many non-containment edges
   are added on top of that to simulate some further catalog structure. There is
   no guarantee of relationship depth (as far as, for example Stage(main) ->
   Class(foo) -> Class(bar) -> Resource(biff)), but it does ensure some edges
   between classes, as well as between class and non-class resources.

   The --avg-blob-count and --blob-size parameters control inclusion of large
   text blobs in catalog resources. By default an average of 1 ~100kb blob is
   added per catalog. Blobs are distributed randomly through the set, so if you
   set --avg-blob-count to 20 over --hosts 10, on averge there will be two per
   catalog, but some may have none, others four, etc...

   Set --avg-blob-count to 0 to exclude blobs altogether.

   TODO:
   * facts
   * reports"
  (:require
    [clojure.set :as cset]
    [clojure.string :as string]
    [clojure.data.generators :as dgen]
    [clojure.tools.namespace.dependency :as dep]
    [puppetlabs.i18n.core :refer [trs]]
    [puppetlabs.kitchensink.core :as kitchensink]
    [puppetlabs.puppetdb.cheshire :as json]
    [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
    [puppetlabs.puppetdb.export :as export]
    [puppetlabs.puppetdb.nio :refer [get-path]]
    [puppetlabs.puppetdb.random :as rnd]
    [puppetlabs.puppetdb.time :refer [now]]
    [puppetlabs.puppetdb.utils :as utils])
  (:import
    [java.nio.file.attribute FileAttribute]
    [java.nio.file Files]
    [org.apache.commons.lang3 RandomStringUtils]))

(defn pseudonym
  "Generate a fictious but somewhat intelligible keyword name."
  ([prefix ordinal]
   (pseudonym prefix ordinal 6))
  ([prefix ordinal length]
   (let [mid-length (max (- length (count prefix) (count (str ordinal))) 1)]
     (format "%s-%s-%s" prefix (rnd/random-pronouncable-word mid-length) ordinal))))

(defn build-to-size
  "Build up list of strings to approximately size bytes."
  [size generate-word-fn]
  (loop [strings []]
    (if (< (reduce + (map count strings)) size)
      (recur (conj strings (generate-word-fn)))
      strings)))

(defn parameter-name
  "Generate a fictious but somewhat intelligible snake case parameter name (as
   used in puppet modules).

   NOTE: size is not exact."
  ([]
   (parameter-name 6))
  ([size]
   (let [p-word-fn #(rnd/random-pronouncable-word 6 2 {:lowerb 1})
         words (build-to-size size p-word-fn)]
     (string/join "_" words))))

(defn build-parameters
  "Build a random map of resource parameter keyword and string values to
   approximately size number of bytes."
  [size]
  (loop [parameters {}]
    (let [current-size (->> (into [] cat parameters)
                            (map count)
                            (reduce +))]
      (if (< current-size size)
        (recur (assoc parameters
                      (parameter-name
                        (rnd/safe-sample-normal 20 5 {:lowerb 5}))
                      (RandomStringUtils/randomAscii
                        (rnd/safe-sample-normal 50 25 {:upperb size}))))
        parameters))))

(defn generate-classes
  [number title-size]
  (map (fn [i] (rnd/random-resource "Class" (pseudonym "class" i title-size))) (range number)))

(def builtin-puppet-types
  "List of some built in puppet types for randomized resources."
  ["Exec"
   "File"
   "Package"
   "Service"
   "User"])

(defn random-type
  "Return a built-in type 80% of the time, or a random defined type name."
  []
  (dgen/weighted
    {;; 80%
     #(rand-nth builtin-puppet-types), 4
     ;; 20%
     (fn [] (string/join "::" (repeatedly
                               (inc (rand-int 4))
                               #(string/capitalize
                                  (rnd/random-pronouncable-word 10 4 {:lowerb 2}))))), 1}))

(defn generate-resources
  [number avg-resource-size title-size ]
  (let [tag-word-fn #(rnd/random-pronouncable-word 15 7 {:lowerb 5 :upperb 100})]
    (vec
      (map (fn [i]
             (let [line-size 4 ;; stored as an int in the database...
                   type-name (random-type)
                   title (pseudonym "resource" i title-size)
                   file (rnd/random-pp-path)
                   resource-size (rnd/safe-sample-normal avg-resource-size (quot avg-resource-size 4))
                   tags-mean (-> (quot resource-size 5) (min 200))
                   tags-size (rnd/safe-sample-normal tags-mean (quot tags-mean 2) {:lowerb 10})
                   parameters-size (max 0
                                     (- resource-size
                                        tags-size
                                        (count type-name)
                                        (count title)
                                        (count file)
                                        line-size))
                   tags (build-to-size tags-size tag-word-fn)
                   parameters (build-parameters parameters-size)
                   resource (rnd/random-resource type-name title {"tags" tags "file" file})]
               (assoc resource "parameters" parameters)))
           (range number)))))

(defprotocol NamedEdges
  (add [catalog r1 r2 relation] "Create an edge of relation between resource 1 and 2 in catalog."))

(defrecord Catalog [graph edges]
  ;; graph is a clojure.tools.namespace.dependency MapDependencyGraph of catalog resources.
  ;; edges is a set tracking the relation type of each edge.
  NamedEdges
    (add [catalog r1 r2 relation]
      (let [[source target] (case relation
                               (:before :contains :notifies) [r1 r2]
                               (:required-by :subscription-of) [r2 r1])]
        (Catalog.
          (dep/depend (:graph catalog) source target)
          (conj (:edges catalog) {:source source :target target :relation relation})))))

(defn new-catalog-graph
  "Return a new initialized empty Catalog."
  []
  (->Catalog (dep/graph) #{}))

(defn generate-catalog-graph
  "Generate a set of containment edges associating each class with main-stage
   and randomly distributing remaining resources within the classes. Returns
   a Catalog instance.

   Randomly generates additional required-by, notifies, before and
   subscription-of between a given percentage of both class and non-class resources.

   Makes use of Catalog to ensure we're still building a directed acyclic graph, and to
   track the relation type between edges."
  [main-stage classes resources additional-edge-percent]
  (let [additional-class-edges (quot (* additional-edge-percent (count classes)) 100)
        additional-resource-edges (quot (* additional-edge-percent (count resources)) 100)
        shuffled-classes (shuffle classes)
        shuffled-resources (shuffle resources)
        shuffled-all-resources (shuffle (into classes resources))
        get-weighted-relation-fn #(dgen/weighted
                                    ;; relation       % frequency
                                    {:before          10
                                     :notifies        40
                                     :required-by     45
                                     :subscription-of  5})
        create-edge-fn (fn [source-resources c target]
                         (let [relation (get-weighted-relation-fn)
                               target-dependencies
                                 (dep/transitive-dependencies (:graph c) target)
                               target-dependents
                                 (dep/transitive-dependents (:graph c) target)
                               source
                                 (-> (set source-resources)
                                     (cset/difference target-dependents target-dependencies #{target})
                                     vec
                                     shuffle
                                     first)]
                           (if (nil? source)
                             c ;; nothing left
                             (add c source target relation))))]
        (as-> (new-catalog-graph) cat
          ;; add classes contained by main
          (reduce #(add %1 main-stage %2 :contains) cat classes)
          ;; contain all other resources within random classes
          (reduce #(let [cls (rand-nth classes)] (add %1 cls %2 :contains)) cat resources)
          ;; add additional edges between some subset of classes
          (reduce (partial create-edge-fn shuffled-classes) cat (take additional-class-edges shuffled-classes))
          ;; add additional edges of some subset of other resources to all resources
          (reduce (partial create-edge-fn shuffled-all-resources) cat (take additional-resource-edges shuffled-resources)))))

(defn generate-edge
  [{:keys [source target relation]}]
  {:source (select-keys source ["type" "title"])
   :target (select-keys target ["type" "title"])
   :relationship relation})

(defn add-blob
  "Add a large parameter string blob to one of the given catalog's resource parameters.

   The blob will be of mean blob-size picked from a normal distribution with a standard
   deviation of one tenth the mean and an upper and lower bound of +/- 50% of the mean.

   So given a blob-size of 100kb, a random resource will get an additional
   content parameter sized roughly between 90-110kb but with an absolute
   lower bound of 50kb and an upper bound of 150kb.

   Returns the updated catalog."
  [{:keys [resources] :as catalog} blob-size-in-kb]
  (let [avg-blob-size-in-bytes (* blob-size-in-kb 1000)
        standard-deviation (quot avg-blob-size-in-bytes 10)
        lowerb (quot avg-blob-size-in-bytes 2)
        upperb (+ avg-blob-size-in-bytes lowerb)
        bsize (rnd/safe-sample-normal avg-blob-size-in-bytes standard-deviation {:lowerb lowerb :upperb upperb})
        pname (format "content_blob_%s" (rnd/random-pronouncable-word))]
    (update-in catalog [:resources (rand-int (count resources)) "parameters"]
               #(merge % {pname (RandomStringUtils/randomAscii bsize)}))))

(defn generate-catalog
  [certname {:keys [num-classes num-resources resource-size title-size additional-edge-percent]}]
  (let [main-stage (rnd/random-resource "Stage" "main")
        classes (generate-classes num-classes title-size)
        resources (generate-resources (- num-resources num-classes) resource-size title-size)
        catalog-graph (generate-catalog-graph main-stage classes resources additional-edge-percent)
        edges (map generate-edge (:edges catalog-graph))]
    {:resources (reduce into [[main-stage] classes resources])
     :edges edges
     :producer_timestamp (now)
     :transaction_uuid (kitchensink/uuid)
     :certname certname
     :hash (rnd/random-sha1)
     :version (quot (System/currentTimeMillis) 1000)
     :producer "puppetmaster1"
     :catalog_uuid (kitchensink/uuid)
     :code_id (rnd/random-sha1)}))

(defn create-temp-dir
  "Generate a temp directory and return the Path object pointing to it."
  []
  (let [temp-dir (get-path (or (System/getenv "TMPDIR")
                               (System/getProperty "java.io.tmpdir")))]
    (Files/createTempDirectory temp-dir
                               "pdb-generate-"
                               (into-array FileAttribute []))))

(defn generate-files-from-wireformat-collection
  "Store each PuppetDB object as a JSON blob.
   Creates dir and generates filenames using the given namer fn."
  [{:keys [dir namer col]}]
  (.mkdir (.toFile dir))
  (doseq [i col]
    (let [file-name (namer i)
          f (.toFile (.resolve dir file-name))]
      (json/spit-json f i))))

(defn generate
  "Build up a dataset of sample PuppetDB facts, catalogs and reports based on
   given options and store them in the given output directory."
  [options]
  (let [{:keys [num-hosts output-dir avg-blob-count blob-size]} options
        output-path (-> (if (string/blank? output-dir)
                          (create-temp-dir)
                          (get-path output-dir))
                        .toAbsolutePath
                        .normalize)
        hosts (map (fn [i] (format "host-%s-%s" (rnd/random-pronouncable-word) i)) (range num-hosts))
        catalogs (-> (map (fn [host] (generate-catalog host options)) hosts)
                     vec
                     (rnd/distribute #(add-blob % blob-size) avg-blob-count))
        facts []
        reports []
        data {:catalogs
               {:dir (.resolve output-path "catalogs")
                :namer export/export-filename
                :col catalogs}
              :facts
               {:dir (.resolve output-path "facts")
                :namer #(str (:certname %) export/export-file-ext)
                :col facts}
              :reports
               {:dir (.resolve output-path "reports")
                :namer export/export-filename
                :col reports}}]
    (when-not (.exists (.toFile output-path))
      (utils/throw-sink-cli-error (trs "Error: output path does not exist: {0}" output-path)))
    (println (format "Generate from %s and store at %s" options output-path))
    (doseq [[_kind d] data]
      (generate-files-from-wireformat-collection d))))

(defn- validate-cli!
  [args]
  (let [specs [["-c" "--num-classes NUMCLASSES" "Number of class resources to generate in catalogs."
                :default 10
                :parse-fn #(Integer/parseInt %)]
               ["-r" "--num-resources NUMRESOURCES" "Number of resources to generate in catalogs. (Includes num-classes.)"
                :default 100
                :parse-fn #(Integer/parseInt %)]
               ["-t" "--title-size TITLESIZE" "Avergae number of characters in resource titles."
                :default 20
                :parse-fn #(Integer/parseInt %)]
               ["-s" "--resource-size" "The average resource size in bytes."
                :default 200
                :parse-fn #(Integer/parseInt %)]
               ["-e" "--additional-edge-percent" "The percent of generated classes and resources for which to create additional edges."
                :default 50
                :parse-fn #(Integer/parseInt %)]
               ["-b" "--avg-blob-count" "Average number of larger resource parameter blobs to add per catalog. Set to 0 to ensure none."
                :default 1
                :parse-fn #(Integer/parseInt %)]
               ["-B" "--blob-size" "Average size of a large resource parameter blob in kB."
                :default 100
                :parse-fn #(Integer/parseInt %)]
               ["-n" "--num-hosts NUMHOSTS" "The number of sample hosts to generate data for."
                :default 5
                :parse-fn #(Integer/parseInt %)]
               ["-o" "--output-dir OUTPUTDIR" "Directory to write output files to. Will allocate in TMPDIR (if set in the environment) or java.io.tmpdir if not given."]]
        required []]
    (utils/try-process-cli
      (fn []
        (-> args
            (kitchensink/cli! specs required)
            first)))))

(defn generate-wrapper
  "Generates a set of fact, catalog and report json files based on the given args."
  [args]
  (-> args
      validate-cli!
      generate))

(defn cli
  "Runs the generate command as directed by the command line args and
  returns an appropriate exit status."
  [args]
  (run-cli-cmd #(generate-wrapper args))
  0)

(defn -main [& args]
  (exit (cli args)))
