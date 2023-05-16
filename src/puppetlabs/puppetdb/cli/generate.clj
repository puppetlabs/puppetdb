(ns puppetlabs.puppetdb.cli.generate
  "# Data Generation utility

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

   ### Facts

   #### Baseline Facts

   Each fact set begins with a set of baseline facts from:
   [baseline-agent-node.json](./resources/puppetlabs/puppetdb/generate/samples/facts/baseline-agent-node.json).

   These provide some consistency for a common set of baseline fact paths
   present on any puppet node. The generator then mutates half of the values to
   provide variety.

   #### Fact Counts

   The --num-facts parameter controls the number of facts to generate per host.

   There are 376 leaf facts in the baseline file. Setting num-facts less than
   this will remove baseline facts to approach the requested number of facts.
   (Empty maps and arrays are not removed from the factset, so it will never
   pair down to zero.) Setting num-facts to a larger number will add facts of
   random depth based on --max-fact-depth until the requested count is reached.

   #### Total Facts Size

   The --total-fact-size parameter controls the total weight of the fact values
   map in kB. Weight is added after count is reached. So if the weight of the
   adjusted baseline facts already exceeds the total-fact-size, nothing more is
   done. No attempt is made to pair facts back down the requested size, as this
   would likely require removing facts.

   #### Max Fact Depth

   The --max-fact-depth parameter is the maximum nested depth a fact added to
   the baseline facts may reach. For example a max depth of 5, would mean that
   an added fact would at most be a nest of four maps:

     {foo: {bar: {baz: {biff: boz}}}}

   Since depth is picked randomly for each additional fact, this does not
   guarantee facts of a given depth. Nor does it directly affect the average
   depth of facts in the generated factset, although the larger the
   max-fact-depth and num-facts, the more likely that the average depth will
   drift higher.

   #### Package Inventory

   The --num-packages parameter sets the number of packages to generate for the
   factset's package_inventory array. Set to 0 to exclude.

   ### Reports

   TODO

   ### Random Distribution

   The default generation produces relatively uniform structures.

   * for catalogs it generates equal resource and edge counts and similar byte
     counts.
   * for factsets it generates equal fact counts and similar byte counts.

   Example:

      jpartlow@jpartlow-dev-2204:~/work/src/puppetdb$ lein run generate --verbose --output-dir generate-test
      ...
      :catalogs: 5

      |     :certname | :resource-count | :resource-weight | :min-resource | :mean-resource | :max-resource | :edge-count | :edge-weight | :catalog-weight |
      |---------------+-----------------+------------------+---------------+----------------+---------------+-------------+--------------+-----------------|
      | host-sarasu-0 |             101 |           137117 |            90 |           1357 |        110246 |         150 |        16831 |          154248 |
      | host-lukoxo-1 |             101 |           132639 |            98 |           1313 |        104921 |         150 |        16565 |          149504 |
      | host-dykivy-2 |             101 |           120898 |           109 |           1197 |         94013 |         150 |        16909 |          138107 |
      | host-talyla-3 |             101 |           110328 |           128 |           1092 |         82999 |         150 |        16833 |          127461 |
      | host-foropy-4 |             101 |           136271 |           106 |           1349 |        109811 |         150 |        16980 |          153551 |

      :facts: 5

      |     :certname | :fact-count | :avg-depth | :max-depth | :fact-weight | :total-weight |
      |---------------+-------------+------------+------------+--------------+---------------|
      | host-sarasu-0 |         400 |       2.77 |          7 |        10000 |         10118 |
      | host-lukoxo-1 |         400 |        2.8 |          7 |        10000 |         10118 |
      | host-dykivy-2 |         400 |     2.7625 |          7 |        10000 |         10118 |
      | host-talyla-3 |         400 |     2.7825 |          7 |        10000 |         10118 |
      | host-foropy-4 |         400 |     2.7925 |          7 |        10000 |         10118 |
      ...

   This mode is best used when generating several different sample sets with
   distinct weights and counts to provide (when combined) an overall sample set
   for benchmark that includes some fixed number of fairly well described
   catalog, fact and report examples.

   By setting --random-distribution to true, you can instead generate a more random
   sample set, where the exact parameter values used per host will be picked
   from a normal curve based on the set value as mean.

   * for catalogs, this will effect the class, resource, edge and total blob counts

   Blobs will be distributed randomly through the set, so if you
   set --blob-count to 2 over --hosts 10, on averge there will be two per
   catalog, but some may have none, others four, etc...

   * for facts, this will effect the fact and package counts, the total weight and the max fact depth.

   Example:

      jpartlow@jpartlow-dev-2204:~/work/src/puppetdb$ lein run generate --verbose --random-distribution
      :catalogs: 5

      |     :certname | :resource-count | :resource-weight | :min-resource | :mean-resource | :max-resource | :edge-count | :edge-weight | :catalog-weight |
      |---------------+-----------------+------------------+---------------+----------------+---------------+-------------+--------------+-----------------|
      | host-cevani-0 |             122 |            33831 |            93 |            277 |           441 |         193 |        22044 |           56175 |
      | host-firilo-1 |              91 |           115091 |           119 |           1264 |         91478 |         130 |        14466 |          129857 |
      | host-gujudi-2 |             129 |            36080 |           133 |            279 |           465 |         180 |        20230 |           56610 |
      | host-xegyxy-3 |             106 |           120603 |           136 |           1137 |         92278 |         153 |        17482 |          138385 |
      | host-jaqomi-4 |             107 |           211735 |            87 |           1978 |         98354 |         159 |        17792 |          229827 |

      :facts: 5

      |     :certname | :fact-count | :avg-depth | :max-depth | :fact-weight | :total-weight |
      |---------------+-------------+------------+------------+--------------+---------------|
      | host-cevani-0 |         533 |  3.4690433 |          9 |        25339 |         25457 |
      | host-firilo-1 |         355 |  2.7464788 |          7 |        13951 |         14069 |
      | host-gujudi-2 |         380 |       2.75 |          8 |        16111 |         16229 |
      | host-xegyxy-3 |         360 |  2.7305555 |          7 |         5962 |          6080 |
      | host-jaqomi-4 |         269 |  2.7695167 |          7 |        16984 |         17102 |
      ..."
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

(defn vary-param
  "Return the given param, or if random-distribution is true, pick from a
   normal distribution with the given param value as mean and a standard
   deviation of parm-value * stnd-deviation-percent."
  [param-value random-distribution stnd-deviation-percent & safe-standard-normal-options]
  (let [stnd-deviation (* param-value stnd-deviation-percent)]
    (if random-distribution
      (rnd/safe-sample-normal param-value stnd-deviation safe-standard-normal-options)
      param-value)))

(defn leaf-fact-paths
  "Filter for fact-paths that are not collections."
  [fact-paths]
  (filter #(not= 5 (:value_type_id %)) fact-paths))

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
   (let [p-word-fn #(rnd/random-pronouncable-word 6 2)
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
                        (rnd/safe-sample-normal 50 25 {:upperb (max 50 size)}))))
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
                   tags-size (rnd/safe-sample-normal tags-mean (quot tags-mean 2) {:lowerb (min tags-mean 10)})
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
        class-count (vary-param num-classes random-distribution 0.25)
        resource-count (vary-param num-resources random-distribution 0.25)
        edge-percent (vary-param additional-edge-percent random-distribution 0.2)
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
  [num-facts max-fact-depth total-fact-size-in-bytes options]
  (let [baseline-factset (load-baseline-factset)
        baseline-values (get baseline-factset "values")
        baseline-fact-paths (shuffle (facts/facts->pathmaps baseline-values))
        baseline-leaves (leaf-fact-paths baseline-fact-paths)
        num-baseline-leaves-to-vary (quot (count baseline-leaves) 2)
        facts-to-add (- num-facts (count baseline-leaves))]
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
              (println (trs "Warning: the weight of the baseline factset adjusted to {0} facts is already {1} bytes which is greater than the requested total size of {2} bytes." num-facts weight total-fact-size-in-bytes )))
            fact-values)
          (fatten-fact-values fact-values total-fact-size-in-bytes))))))

(defn generate-package-inventory
  "Build a list of package [name, provider, version] vectors per the wire format."
  [num-packages]
  (let [providers ;; with 3-5 fake providers
         (map (fn [_] (rnd/random-pronouncable-word)) (range (+ 3 (rand-int 3))))]
    (map (fn [_]
           (let [package-name (parameter-name (rnd/safe-sample-normal 12 7 {:lowerb 5}))
                 provider (rand-nth providers)
                 version (format "%s.%s.%s" (rand-int 11) (rand-int 50) (rand-int 100))]
             [package-name, provider, version]))
         (range num-packages))))

(defn generate-factset
  [certname
   {:keys [num-facts max-fact-depth total-fact-size num-packages random-distribution] :as options}]
  (let [fact-count (vary-param num-facts random-distribution 0.25)
        total-fact-size-in-bytes (* total-fact-size 1000)
        facts-weight (vary-param total-fact-size-in-bytes random-distribution 0.5)
        max-depth (vary-param max-fact-depth random-distribution 0.5)
        package-count (vary-param num-packages random-distribution 0.25)
        factset {:certname certname
                 :timestamp (now)
                 :environment "production"
                 :producer_timestamp (now)
                 :producer "puppetmaster1"
                 :values (generate-fact-values fact-count max-depth facts-weight options)}]
    (if (> num-packages 0)
      (merge factset {:package_inventory (generate-package-inventory package-count)})
      factset)))

(defn generate-report
  "Generate a report based on the given catalog.

   Ensure that percent-resource-change resources have events.

   Exclude or keep unchanged resources based on exclude-unchanged-resources."
  [catalog percent-resource-change exclude-unchanged-resources]
  {})

(defn add-logs-to-reports
  ""
  [reports num-additional-logs percent-of-reports-with-additional-logs]
  reports)

(defn generate-reports
  "Generate a set of reports for the given catalog based on options."
  [catalog
   {:keys [num-reports
           high-change-reports-percent
           high-change-resources-percent
           low-change-reports-percent
           low-change-resources-percent
           exclude-unchanged-resources
           num-additional-logs
           percent-of-reports-with-additional-logs] :as options}]
  (let [high-change-count (int (* (/ high-change-reports-percent 100) num-reports))
        low-change-count (int (* (/ low-change-reports-percent 100) num-reports))
        no-change-count (- num-reports high-change-count low-change-count)
        reports-spread [[high-change-count, high-change-resources-percent]
                        [low-change-count, low-change-resources-percent]
                        [no-change-count, 0]]
        reports (reduce
                  (fn [reports [report-count percent-resource-change]]
                    (conj reports
                          (repeatedly report-count
                                      #(generate-report catalog
                                                        percent-resource-change
                                                        exclude-unchanged-resources))))
                  [], reports-spread)]
    (add-logs-to-reports reports num-additional-logs percent-of-reports-with-additional-logs)))

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
               (map (fn [{:keys [certname values package_inventory] :or {package_inventory []} :as f}]
                      (let [fact-paths (facts/facts->pathmaps values)
                            leaves (leaf-fact-paths fact-paths)
                            depths (into (sorted-map)
                                         (group-by #(count (:path_array %)) leaves))]
                        (array-map :certname certname
                                   :fact-count (count leaves)
                                   :avg-depth (float (/
                                                       (reduce (fn [sum [d fps]]
                                                                 (+ sum (* d (count fps))))
                                                               0, depths)
                                                       (count leaves)))
                                   :max-depth (apply max (keys depths))
                                   :fact-weight (weigh values)
                                   :package-count (count package_inventory)
                                   :package-weight (weigh package_inventory)
                                   :total-weight (weigh f))))
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
        facts (map (fn [host] (generate-factset host options)) hosts)
        reports (-> (map (fn [catalog] (generate-reports catalog options)) catalogs)
                    flatten)]
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
        fact-paths (facts/facts->pathmaps values)
        leaves (leaf-fact-paths fact-paths)]
    (println "fact-paths depths")
    (pp/print-table [(update-vals
                             (group-by #(format "Depth %s" (count (:path_array %))) fact-paths)
                             count)])
    (prn)
    (println "leaf fact-paths depths")
    (pp/print-table [(update-vals
                             (group-by #(format "Depth %s" (count (:path_array %))) leaves)
                             count)])
    (prn)
    (pp/pprint {:total-fact-value-weight (weigh values)
                :total-weight (weigh facts)
                :total-leaves (count leaves)})))

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
  (let [validate-options (fn [options]
                           (cond
                             (<= (+ (:high-change-reports-percent options)
                                    (:low-change-reports-percent options)) 100)
                             (utils/throw-sink-cli-error
                               (trs "Error: the sum of -h and -l must be less than or equal to 100%"))
                             :else options))

        specs [;; Catalog generation options
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

               ;; Fact generation options
               ["-f" "--num-facts NUMFACTS" "Number of facts to generate in a factset"
                :default 400
                :parse-fn #(Integer/parseInt %)]
               ["-F" "--total-fact-size TOTALFACTSIZE" "Average total weight of the collected facts in kB."
                :default 10
                :parse-fn #(Integer/parseInt %)]
               [nil "--max-fact-depth FACTDEPTH" "Maximum depth of the nested structure of additional facts."
                :default 7
                :parse-fn #(Integer/parseInt %)]
               ["-p" "--num-packages NUMPACKAGES" "Number of packages to include in package inventory."
                :default 1000
                :parse-fn #(Integer/parseInt %)]

               ;; Report generation options
               ["-R" "--num-reports NUMREPORTS" "Number of reports to generate per catalog"
                :default 10
                :parse-fn #(Integer/parseInt %)]
               ["-h" "--high-change-reports-percent PERCENTHIGHCHANGEREPORTS" "Percentage of reports per catalog that generate a high number of change events."
                :default 5
                :parse-fn #(Float/parseFloat %)
                :validate [#(< 0 % 100) "Must be an integer percent between 0 and 100."]]
               ["-H" "--high-change-resources-percent PERCENTHIGHCHANGERESOURCES" "Percentage of resources with resource events in a high change report."
                :default 80
                :parse-fn #(Integer/parseInt %)
                :validate [#(< 0 % 100) "Must be an integer percent between 0 and 100."]]
               ["-l" "--low-change-reports-percent PERCENTLOWCHANGEREPORTS" "Percentage of reports per catalog that generate a low number of change events."
                :default 20
                :parse-fn #(Float/parseFloat %)
                :validate [#(< 0 % 100) "Must be an integer percent between 0 and 100."]]
               ["-L" "--low-change-resources-percent PERCENTLOWCHANGERESOURCES" "Percentage of resources with resource events in a low change report."
                :default 5
                :parse-fn #(Integer/parseInt %)
                :validate [#(< 0 % 100) "Must be an integer percent between 0 and 100."]]
               [nil "--[no-]exclude-unchanged-resources" "Whether to exclude unchanged resources from reports."
                :default true]
               [nil "--num-additional-logs NUMADDITIONALLOGS" "Number of additional logs to include in reports (can simulate --debug output if desired)."
                :default 0
                :parse-fn #(Integer/parseInt %)]
               ;; Sigh. If we do another round, might want to switch to a
               ;; config file where these sorts of values can be fiddled rather
               ;; than --very-long-flags-of-lengthiness
               [nil "--percent-of-reports-with-additional-logs PERCENTREPORTSADDITIONALLOGS" "Percentage of reports to add the additional logs to, if any additional logs have been set."
                :default 1
                :parse-fn #(Integer/parseInt %)
                :validate [#(< 0 % 100) "Must be an integer percent between 0 and 100."]]

               ;; General options
               ["-n" "--num-hosts NUMHOSTS" "The number of sample hosts to generate data for."
                :default 5
                :parse-fn #(Integer/parseInt %)]
               [nil "--random-distribution" "Pick from a random distribution around the mean of key settings such as num of resources, facts, etc, to provide for less even catalogs, fact sets and reports."
                :default false]
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
            first
            validate-options)))))

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
