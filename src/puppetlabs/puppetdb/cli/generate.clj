(ns puppetlabs.puppetdb.cli.generate
  "Data Generation utility

   This command-line tool can generate a base sampling of catalog, fact and
   report files suitable for consumption by the PuppetDB benchmark utility.

   Note that it is only necessary to generate a small set of initial sample
   data since benchmark will permute per node differences. So even if you want
   to benchmark 1000 nodes, you don't need to generate initial
   catalog/fact/report json for 1000 nodes.

   TODO:
   * facts
   * reports"
  (:require
    [clojure.string :as string]
    [clojure.data.generators :as dgen]
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
  [number avg-resource-size title-size]
  (let [tag-word-fn #(rnd/random-pronouncable-word 15 7 {:lowerb 5 :upperb 100})]
    (map (fn [i]
           (let [type-name (random-type)
                 title (pseudonym "resource" i title-size)
                 resource-size (rnd/safe-sample-normal avg-resource-size (quot avg-resource-size 4))
                 tags-mean (-> (quot resource-size 5) (min 200))
                 tags-size (rnd/safe-sample-normal tags-mean (quot tags-mean 2) {:lowerb 10})
                 parameters-size (- resource-size tags-size)
                 tags (build-to-size tags-size tag-word-fn)
                 file (rnd/random-pp-path)
                 parameters (build-parameters parameters-size)
                 resource (rnd/random-resource type-name title {"tags" tags "file" file})]
             (assoc resource "parameters" parameters)))
         (range number))))

(defn generate-edge
  ([source target]
   (generate-edge source target "contains"))
  ([source target relation]
   {:source (select-keys source ["type" "title"])
    :target (select-keys target ["type" "title"])
    :relationship relation}))

(defn generate-edges
  "Generate a set of edges associating each class with main-stage and randomly
   distributing remaining resources within the classes."
  [main-stage classes resources]
  (let [class-resources-map
          (reduce
            (fn [cmap r]
              (let [class-key (rand-nth classes)]
                (update cmap class-key conj r)))
            {} resources)]
    (->> class-resources-map
         (map
           (fn [[cls cls-resources]]
             (concat
               (list (generate-edge main-stage cls)) (map #(generate-edge cls %) cls-resources))))
         flatten)))

(defn generate-catalog
  [certname {:keys [num-classes num-resources resource-size title-size]}]
  (let [main-stage (rnd/random-resource "Stage" "main")
        classes (generate-classes num-classes title-size)
        resources (generate-resources (- num-resources num-classes) resource-size title-size)
        edges (generate-edges main-stage classes resources)]
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
  (let [{:keys [num-hosts output-dir]} options
        output-path (-> (if (string/blank? output-dir)
                          (create-temp-dir)
                          (get-path output-dir))
                        .toAbsolutePath
                        .normalize)
        hosts (map (fn [i] (format "host-%s-%s" (rnd/random-pronouncable-word) i)) (range num-hosts))
        catalogs (map (fn [host] (generate-catalog host options)) hosts)
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
               ["-r" "--num-resources NUMRESOURCES" "Number of resources to generate in catalogs."
                :default 100
                :parse-fn #(Integer/parseInt %)]
               ["-t" "--title-size TITLESIZE" "Number of characters in resource titles."
                :default 20
                :parse-fn #(Integer/parseInt %)]
               ["-s" "--resource-size" "The average resource size in kB."
                :default 50
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
