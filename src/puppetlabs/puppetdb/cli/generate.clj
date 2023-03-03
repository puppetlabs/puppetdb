(ns puppetlabs.puppetdb.cli.generate
  "Data Generation utility

   This command-line tool can generate a base sampling of catalog, fact and
   report files suitable for consumption by the PuppetDB benchmark utility.

   Note that it is only necessary to generate a small set of initial sample
   data since benchmark will permute per node differences. So even if you want
   to benchmark 1000 nodes, you don't need to generate initial
   catalog/fact/report json for 1000 nodes."
  (:require
    [clojure.java.io :as io]
    [puppetlabs.i18n.core :refer [trs]]
    [puppetlabs.kitchensink.core :as kitchensink]
    [puppetlabs.puppetdb.cheshire :as json]
    [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
    [puppetlabs.puppetdb.export :as export]
    [puppetlabs.puppetdb.nio :refer [get-path]]
    [puppetlabs.puppetdb.time :refer [now]]
    [puppetlabs.puppetdb.utils :as utils :refer [println-err]])
  (:import
    [java.nio.file.attribute FileAttribute]
    [java.nio.file Files]))

(defn generate-catalog
  [certname num-classes, num-resources, title-size, _total-catalog-size, _depth]
  {})

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
  (let [{:keys [num-hosts num-classes num-resources title-size output-dir]} options
        output-path (-> (if (clojure.string/blank? output-dir)
                          (create-temp-dir)
                          (get-path output-dir))
                        .toAbsolutePath
                        .normalize)
        hosts (map (fn [i] (format "host-%s-%s" (random-pronouncable-word) i)) (range num-hosts))
        catalogs (map (fn [host] (generate-catalog host num-classes num-resources title-size nil nil)) hosts)
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
