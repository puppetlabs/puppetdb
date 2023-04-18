(ns puppetlabs.puppetdb.cli.generate
  "# Data Generation utility

   This command-line tool can generate a base sampling of catalog files
   suitable for consumption by the PuppetDB benchmark utility. (Fact and report
   files are on the todo list.)

   Note that it is only necessary to generate a small set of initial sample
   data since benchmark will permute per node differences. So even if you want
   to benchmark 1000 nodes, you don't need to generate initial
   catalog/fact/report json for 1000 nodes.

   If you want a representative sample with big differences between catalogs,
   you will need to run the tool multiple times. For example, if you want a set
   of 5 large catalogs and 10 small ones, you will need to run the tool twice
   with the desired parameters to create the two different sets.

   ## Flag Notes

   ### Catalogs

   #### Resource Counts

   The num-resources flag is total and includes num-classes. So if you set
   --num-resources to 100 and --num-classes to 30, you will get a catalog with a
   hundred resources, thirty of which are classes.

   #### Edges

   A containment edge is always generated between the main stage and each
   class. And non-class resources get a containment edge to a random class. So
   there will always be a base set of containment edges equal to the resource
   count. The --additional-edge-percent governs how many non-containment edges
   are added on top of that to simulate some further catalog structure. There is
   no guarantee of relationship depth (as far as, for example Stage(main) ->
   Class(foo) -> Class(bar) -> Resource(biff)), but it does ensure some edges
   between classes, as well as between class and non-class resources.

   #### Large Resource Parameter Blobs

   The --blob-count and --blob-size parameters control inclusion of large
   text blobs in catalog resources. By default one ~100kb blob is
   added per catalog.

   Set --blob-count to 0 to exclude blobs altogether.

   #### Random Distribution

   The default catalog generation produces relatively uniform catalogs with equal
   resource and edge counts and similar byte counts.

   Example:

      jpartlow@jpartlow-dev-2204:~/work/src/puppetdb$ lein run generate --verbose
      ...
      Generate from {:resource-size 200, :silent false, :additional-edge-percent 50, :random-distribution false, :verbose true, :num-hosts 5, :blob-count 1, :help false, :blob-size 100, :num-resources 100, :num-classes 10, :title-size 20} and store at /tmp/pdb-generate-7287481941956485370
      :catalogs: 5

      |     :certname | :catalog-weight | :resource-count | :resource-weight | :min-resource | :mean-resource | :max-resource | :edge-count | :edge-weight |
      |---------------+-----------------+-----------------+------------------+---------------+----------------+---------------+-------------+--------------|
      | host-cuxajo-0 |          170755 |             101 |           153432 |            94 |           1519 |        126759 |         150 |        17023 |
      | host-vudagu-1 |          172902 |             101 |           156028 |           107 |           1544 |        129527 |         150 |        16574 |
      | host-jaxoda-2 |          146566 |             101 |           129521 |            80 |           1282 |        102617 |         150 |        16745 |
      | host-dofuqu-3 |          141793 |             101 |           124675 |           111 |           1234 |         97633 |         150 |        16818 |
      | host-vibocu-4 |          158409 |             101 |           140961 |           108 |           1395 |        114629 |         150 |        17148 |
      ...

   This mode is best used when generating several sets of catalogs of distinct
   weights and counts to provide an overall sample set for benchmark that
   includes some fixed number of fairly well described catalog examples.

   By setting --random-distribution to true, you can generate a more random
   catalog set with class, resource, edge and total blob count randomized. The
   values used will be picked from a normal curve based on the set value as
   mean.

   Blobs will be distributed randomly through the set, so if you
   set --blob-count to 2 over --hosts 10, on averge there will be two per
   catalog, but some may have none, others four, etc...

   Example:

      jpartlow@jpartlow-dev-2204:~/work/src/puppetdb$ lein run generate --verbose --random-distribution
      ...
      Generate from {:resource-size 200, :silent false, :additional-edge-percent 50, :random-distribution true, :verbose true, :num-hosts 5, :blob-count 1, :help false, :blob-size 100, :num-resources 100, :num-classes 10, :title-size 20} and store at /tmp/pdb-generate-5327046227415516789
        :catalogs: 5

        |     :certname | :catalog-weight | :resource-count | :resource-weight | :min-resource | :mean-resource | :max-resource | :edge-count | :edge-weight |
        |---------------+-----------------+-----------------+------------------+---------------+----------------+---------------+-------------+--------------|
        | host-denune-0 |          361113 |              92 |           345788 |           120 |           3758 |        115375 |         132 |        15025 |
        | host-lakezo-1 |           31841 |              76 |            19727 |           119 |            259 |           464 |         106 |        11814 |
        | host-bysoza-2 |          141905 |             107 |           124171 |           111 |           1160 |         95754 |         153 |        17434 |
        | host-nyhege-3 |           52469 |             116 |            31495 |           142 |            271 |           419 |         181 |        20674 |
        | host-suxuto-4 |           63264 |             142 |            39601 |            95 |            278 |           412 |         205 |        23363 |
      ...

   ## TODO

   * facts
   * reports"
  (:require
    [clojure.data.generators :as dgen]
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [clojure.set :as cset]
    [clojure.string :as string]
    [clojure.tools.namespace.dependency :as dep]
    [puppetlabs.i18n.core :refer [trs]]
    [puppetlabs.kitchensink.core :as kitchensink]
    [puppetlabs.puppetdb.cheshire :as json]
    [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
    [puppetlabs.puppetdb.export :as export]
    [puppetlabs.puppetdb.facts :as facts]
    [puppetlabs.puppetdb.nio :refer [get-path]]
    [puppetlabs.puppetdb.random :as rnd]
    [puppetlabs.puppetdb.time :refer [now]]
    [puppetlabs.puppetdb.utils :as utils])
  (:import
    [java.nio.file.attribute FileAttribute]
    [java.nio.file Files]
    [org.apache.commons.lang3 RandomStringUtils]))

(def resource-fact-path "puppetlabs/puppetdb/generate/samples/facts/baseline-agent-node.json")

(defmulti weigh "Loosely weigh up the bytes of a structure." class)
(defmethod weigh clojure.lang.IPersistentMap
  [mp]
  (reduce (fn [size [k v]]
            (+ size (count (str k)) (weigh v)))
          0, mp))
(defn- weigh-vec-set-or-seq
  [a]
  (reduce (fn [size e]
            (+ size (weigh e)))
          0, a))
(defmethod weigh clojure.lang.IPersistentVector [vc] (weigh-vec-set-or-seq vc))
(defmethod weigh clojure.lang.IPersistentSet [st] (weigh-vec-set-or-seq st))
(defmethod weigh clojure.lang.ISeq [sq] (weigh-vec-set-or-seq sq))
(defmethod weigh clojure.lang.Keyword [k] (count (str k)))
(defmethod weigh String [s] (count s))
(defmethod weigh Number [_] 4)
(defmethod weigh Boolean [_] 1)
(defmethod weigh org.joda.time.DateTime [_] 8)
(defmethod weigh :default
  [what] (throw (Exception. (format "Don't know how to weigh a %s of type %s" what (type what)))))

(defn silent?
  "User has requested output silence."
  [{:keys [silent]}]
  silent)

(defn verbose?
  "User has requested verbose output.
   But make sure silent isn't set..."
  [{:keys [silent verbose]}]
  (and verbose (not silent)))

(defn pseudonym
  "Generate a fictitious but somewhat intelligible keyword name."
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
  [certname {:keys [num-classes num-resources resource-size title-size additional-edge-percent random-distribution]}]
  (let [main-stage (rnd/random-resource "Stage" "main")
        class-count (if random-distribution
                      (rnd/safe-sample-normal num-classes (quot num-classes 4))
                      num-classes)
        resource-count (if random-distribution
                         (rnd/safe-sample-normal num-resources (quot num-resources 4))
                         num-resources)
        edge-percent (if random-distribution
                       (rnd/safe-sample-normal additional-edge-percent (quot additional-edge-percent 5))
                       additional-edge-percent)
        classes (generate-classes class-count title-size)
        resources (generate-resources (- resource-count class-count) resource-size title-size)
        catalog-graph (generate-catalog-graph main-stage classes resources edge-percent)
        edges (map generate-edge (:edges catalog-graph))]
    {:resources (reduce into [[main-stage] classes resources])
     :edges edges
     :producer_timestamp (now)
     :transaction_uuid (kitchensink/uuid)
     :certname certname
     :hash (rnd/random-sha1)
     :version (str (quot (System/currentTimeMillis) 1000))
     :producer "puppetmaster1"
     :catalog_uuid (kitchensink/uuid)
     :code_id (rnd/random-sha1)}))

(defn sprinkle-blobs
  "Add large text blobs to catalogs.

   If random-distribution is false, will sprinkle blob-count blobs of an avg
   blob-size in kb into each catalog.

   If random-distribution is true, will sprinkle a total number of blobs, picked
   from a random distribution based on a mean of blob-count * catalog count,
   randomly over the catalogs. The result is likely, but not guaranteed, to be a
   much more uneven catalog set."
  [catalogs {:keys [blob-count blob-size random-distribution]}]
  (let [catalog-vec (vec catalogs)]
    (if random-distribution
      (rnd/distribute catalog-vec #(add-blob % blob-size) blob-count)
      (map (fn [catalog]
             (loop [i blob-count
                    c catalog]
               (if (> i 0)
                 (recur (- i 1)
                        (add-blob c blob-size))
                 c)))
           catalog-vec))))

(defn load-baseline-factset
  "Loads a default set of baseline facts from the classpath resources."
  []
  (json/parse-string (slurp (io/resource resource-fact-path))))

(defn create-new-facts
  [number max-depth]
  (reduce (fn [facts _i]
            (let [depth (+ 1 (rand-int max-depth))
                  fps (shuffle (facts/facts->pathmaps facts))
                  existing-fp-array (some-> (filter
                                              #(= depth (count (get % :path_array)))
                                              fps)
                                            first
                                            (get :path_array))
                  new-fp-array (repeatedly depth
                                           #(parameter-name
                                             (rnd/safe-sample-normal 20 10 {:lowerb 5})))
                  fp-array (dgen/weighted
                              ;; half the time generate a new fact structure
                             {new-fp-array         50
                              ;; the other half of the time insert into an existing
                              ;; structure (if found and not root)
                              #(if (or
                                     (nil? existing-fp-array)
                                     (= (count existing-fp-array) 1))
                                new-fp-array
                                (conj (vec (butlast existing-fp-array))
                                      (parameter-name
                                        (rnd/safe-sample-normal 20 10 {:lowerb 5})))) 50})
                  value (rnd/random-string (rnd/safe-sample-normal 50 25))]
              (assoc-in facts fp-array value)))
          {}
          (range number)))

(defn mutate-fact-values
  "Mutates num-to-change leaf facts (string, number, boolean) in the given
   set of fact-values, using the given leaf-fact-paths to find them."
  [fact-values num-to-change leaf-fact-paths]
  (reduce (fn [facts fact-path]
            (let [path-array (get fact-path :path_array)
                  value-type-id (get fact-path :value_type_id)
                  mutator-fn (case value-type-id
                               (0 4) (fn [_s] (rnd/random-pronouncable-word))
                               (1 2) (fn [_i] (rand-int 1000))
                               3 not
                               ;; shouldn't have a 5 in leaves, but do nothing by default
                               (fn [e] e))]
              (update-in facts path-array mutator-fn)))
          fact-values
          (take num-to-change leaf-fact-paths)))

(defn delete-facts
  "Trim facts down to num-facts.
   NOTE: this does not elliminate empty arrays/maps, so you will
   still have 50 or so empty facts, even if num-facts is 0."
  [fact-values number-to-delete leaf-fact-paths]
  (reduce (fn [facts fact-path]
            (let [path-array (get fact-path :path_array)]
              (if (> (count path-array) 1)
                (let [parent-path (butlast path-array)
                      last-key (last path-array)]
                  (update-in facts parent-path
                             #(if (integer? last-key)
                                ;; drop last from list
                                (vec (drop-last %))
                                ;; dissociate from map
                                (dissoc % last-key))))
                (dissoc facts (first path-array)))))
          fact-values
          (take number-to-delete leaf-fact-paths)))

(defn fatten-fact-values
  "Randomly increase size of string facts until we reach total-fact-size-in-bytes."
  [fact-values total-fact-size-in-bytes]
  (let [fact-paths (facts/facts->pathmaps fact-values)
        string-fact-paths (filter #(= 0 (get % :value_type_id)) fact-paths)]
    (loop [facts fact-values
           weight (weigh facts)]
      (if (< weight total-fact-size-in-bytes)
        (let [path (-> (rand-nth string-fact-paths)
                       (get :path_array))
              remaining-weight (max 0 (- total-fact-size-in-bytes weight))
              string-size-to-add (min (rnd/safe-sample-normal 100 50) remaining-weight)
              additional-string (rnd/random-string string-size-to-add)
              fattened-facts (update-in facts path str additional-string)]
          (recur fattened-facts (weigh fattened-facts)))
        facts))))

(defn generate-fact-values
  "Generates a set of fact values.

   * starts by loading the same set of baseline facts from resources
   * mutates half the leaves
   * then either
     * removes facts down to num-facts if less than the fact count from baseline
     * or uses create-facts to reach num-facts of max-depth
   * then fattens up remaining fact strings to reach total-fact-size-in-kb

   NOTE: fact removal won't reach 0 as empty arrays and maps in the fact paths
   are left behind, and won't change the depth of the baseline facts.

   NOTE: if total-fact-size is less than the weight of adjusted baseline facts,
   nothing more is done. So count trumps size."
  [num-facts max-fact-depth total-fact-size-in-kb options]
  (let [baseline-factset (load-baseline-factset)
        baseline-values (get baseline-factset "values")
        baseline-fact-paths (shuffle (facts/facts->pathmaps baseline-values))
        baseline-leaves (filter #(not= 5 (get % :value_type_id)) baseline-fact-paths)
        num-baseline-leaves-to-vary (quot (count baseline-leaves) 2)
        facts-to-add (- num-facts (count baseline-leaves))
        total-fact-size-in-bytes (* total-fact-size-in-kb 1000)]
    (as-> baseline-values fact-values
      (mutate-fact-values fact-values num-baseline-leaves-to-vary baseline-leaves)
      (if (< facts-to-add 0)
        (delete-facts fact-values (abs facts-to-add) baseline-leaves)
        ;; or add additional facts to bring us up to num-facts count.
        (let [new-facts (create-new-facts facts-to-add max-fact-depth)]
          (merge fact-values new-facts)))
      (let [weight (weigh fact-values)]
        (if (> weight total-fact-size-in-bytes)
          (do
            (when-not (silent? options)
              (println (format "Warning: the weight of the baseline factset adjusted to %s facts is already %s bytes which is greater than the requested total size of %s bytes. To preserve the fact count, nothing else was done." num-facts weight total-fact-size-in-bytes )))
            fact-values)
          (fatten-fact-values fact-values total-fact-size-in-bytes))))))

(defn generate-facts
  [certname
   {:keys [num-facts max-fact-depth total-fact-size avg-package-inventory-count] :as options}]
  {:certname certname
   :timestamp (now)
   :environment "production"
   :producer_timestamp (now)
   :producer "puppetmaster1"
   :values (generate-fact-values num-facts max-fact-depth total-fact-size options)})

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

(defn print-summary-table
  [stat-map]
  (let [col-headers (keys (first stat-map))]
    (pp/print-table col-headers stat-map)
    (prn)))

(defn summarize
  "Print out a verbose summary of the generated data."
  [data]
  (doseq [[kind {:keys [col]}] data]
    (println (format "%s: %s" kind (count col)))
    (let [stats
           (case kind
             :catalogs
               (map (fn [{:keys [certname resources edges] :as c}]
                      (let [resource-weights (sort (map weigh resources))]
                        (array-map :certname certname
                                   :resource-count (count resources)
                                   :resource-weight (weigh resources)
                                   :min-resource (first resource-weights)
                                   :mean-resource (quot (reduce + resource-weights) (count resource-weights))
                                   :max-resource (last resource-weights)
                                   :edge-count (count edges)
                                   :edge-weight (weigh edges)
                                   :catalog-weight (weigh c))))
                      col)
             :facts
               (map (fn [{:keys [certname values] :as f}]
                      (let [fact-paths (facts/facts->pathmaps values)
                            leaves (filter #(not= 5 (:value_type_id %)) fact-paths)
                            depths (into (sorted-map)
                                         (group-by #(count (:path_array %)) leaves))
                            max-depth-keys (as-> depths s
                                                 (get s (last (keys s)))
                                                 (map :path_array s))]
                        (array-map :certname certname
                                   :fact-count (count leaves)
                                   :avg-depth (quot
                                                (reduce (fn [sum [d fps]]
                                                          (+ sum (* d (count fps))))
                                                        0, depths)
                                                (count leaves))
                                   :max-depth (apply max (keys depths))
                                   :fact-weight (weigh values)
                                   :total-weight (weigh f)
                                   :ex-max-depth-keys (first max-depth-keys))))
                    col)
             [{:not-implemented nil}])]
       (print-summary-table stats))))

(defn generate-data
  "Generate and return a map of facts, catalogs and reports."
  [options output-path]
  (let [{:keys [num-hosts]} options
        hosts (map (fn [i] (format "host-%s-%s" (rnd/random-pronouncable-word) i)) (range num-hosts))
        catalogs (-> (map (fn [host] (generate-catalog host options)) hosts)
                     (sprinkle-blobs options))
        facts (map (fn [host] (generate-facts host options)) hosts)
        reports []]
    {:catalogs
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
       :col reports}}))

(defn analyze-facts
  "Dev utility function to get some data about a JSON factset file."
  [file]
  (let [facts (json/parse-string (slurp file))
        values (get facts "values")
        fact-paths (facts/facts->pathmaps values)]
    (pp/print-table [(update-vals
                             (group-by #(format "Depth %s" (count (:path_array %))) fact-paths)
                             count)])
    (prn)
    (pp/pprint {:total-fact-value-weight (weigh values)
                :total-weight (weigh facts)})))

(defn generate
  "Build up a dataset of sample PuppetDB facts, catalogs and reports based on
   given options and store them in the given output directory."
  [{:keys [output-dir] :as options}]
  (let [output-path (-> (if (string/blank? output-dir)
                        (create-temp-dir)
                        (get-path output-dir))
                        .toAbsolutePath
                        .normalize)]
    (when-not (.exists (.toFile output-path))
      (utils/throw-sink-cli-error (trs "Error: output path does not exist: {0}" output-path)))
    (when-not (silent? options)
      (pp/pprint {:generated-from options
                  :stored-at (format "%s" output-path)})
      (prn))
    (let [data (generate-data options output-path)]
      (doseq [[_kind d] data]
        (generate-files-from-wireformat-collection d))
      (when (verbose? options)
        (summarize data)))))

(defn- validate-cli!
  [args]
  (let [specs [;; Catalog generation options
               ["-c" "--num-classes NUMCLASSES" "Number of class resources to generate in catalogs."
                :default 10
                :parse-fn #(Integer/parseInt %)]
               ["-r" "--num-resources NUMRESOURCES" "Number of resources to generate in catalogs. (Includes num-classes.)"
                :default 100
                :parse-fn #(Integer/parseInt %)]
               ["-t" "--title-size TITLESIZE" "Average number of characters in resource titles."
                :default 20
                :parse-fn #(Integer/parseInt %)]
               ["-s" "--resource-size RESOURCESIZE" "The average resource size in bytes."
                :default 200
                :parse-fn #(Integer/parseInt %)]
               ["-e" "--additional-edge-percent ADDITIONALEDGEPERCENT" "The percent of generated classes and resources for which to create additional edges."
                :default 50
                :parse-fn #(Integer/parseInt %)]
               ["-b" "--blob-count BLOBCOUNT" "Number of larger resource parameter blobs to add per catalog. Set to 0 to ensure none."
                :default 1
                :parse-fn #(Integer/parseInt %)]
               ["-B" "--blob-size BLOBSIZE" "Average size of a large resource parameter blob in kB."
                :default 100
                :parse-fn #(Integer/parseInt %)]
               [nil "--random-distribution" "Pick from a random distribution of resources, edge percent and blobs to provide a less even catalog set."
                :default false]

               ;; Facts options
               ["-f" "--num-facts NUMFACTS" "Number of facts to generate in a factset"
                :default 400
                :parse-fn #(Integer/parseInt %)]
               ["-F" "--total-fact-size TOTALFACTSIZE" "Average total weight of the collected facts in kB."
                :default 10
                :parse-fn #(Integer/parseInt %)]
               [nil "--max-fact-depth FACTDEPTH" "Maximum depth of the nested structure of additional facts."
                :default 5
                :parse-fn #(Integer/parseInt %)]

               ;; General options
               ["-n" "--num-hosts NUMHOSTS" "The number of sample hosts to generate data for."
                :default 5
                :parse-fn #(Integer/parseInt %)]
               ["-o" "--output-dir OUTPUTDIR" "Directory to write output files to. Will allocate in TMPDIR (if set in the environment) or java.io.tmpdir if not given."]
               ["-v" "--verbose" "Whether to provide verbose output."
                :default false]
               [nil "--silent" "Whether to suppress non-error output."
                :default false]]
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
