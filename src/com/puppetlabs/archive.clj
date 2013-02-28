;; ## Compression / Archive Utility library
;;
;; This namespace contains functions for reading and writing compressed
;; archive files.  Currently only supports gzipped tar archives.

(ns com.puppetlabs.archive
  (:import [java.io Closeable File OutputStream FileOutputStream IOException InputStream FileInputStream]
           [org.apache.commons.compress.archivers.tar TarArchiveEntry TarArchiveOutputStream TarArchiveInputStream]
           [org.apache.commons.compress.compressors.gzip GzipCompressorOutputStream GzipCompressorInputStream])
  (:use    [clojure.java.io]
           [clj-time.core :only [now]]
           [clj-time.coerce :only [to-date]]))

;; A simple type for writing tar/gz streams
(defrecord TarGzWriter [tar-stream tar-writer gzip-stream]
  Closeable
  (close [this]
    (.close tar-stream)
    (.close gzip-stream)))

;; A simple type for reading tar/gz streams
(defrecord TarGzReader [in-stream gzip-stream tar-stream tar-reader tar-entry]
  Closeable
  (close [this]
    (.close tar-stream)
    (.close gzip-stream)))


(defn- get-outstream
  "Private helper function for coercing an object into an `OutputStream`.  Currently
  supports `String` and `File` objects (which will be wrapped in a `FileOutputStream`),
  or objects that already extend `OutputStream` (which will simply be returned,
  unmodified)."
  [out]
  (cond
    ((some-fn string? #(instance? File %)) out)
      (FileOutputStream. out)
    (instance? OutputStream out)
      out
    :else
      (throw (IOException.
               (format (str "Unable to convert type '%s' to an OutputStream; "
                            "expected String, File, or OutputStream")
                 (type out))))))

(defn- get-instream
  "Private helper function for coercing an object into an `InputStream`.  Currently
  supports `String` and `File` objects (which will be wrapped in a `FileInputStream`),
  or objects that already extend `InputStream` (which will simply be returned,
  unmodified)."
  [in]
  (cond
    ((some-fn string? #(instance? File %)) in)
    (FileInputStream. in)
    (instance? InputStream in)
    in
    :else
    (throw (IOException.
             (format (str "Unable to convert type '%s' to an InputStream; "
                       "expected String, File, or InputStream")
               (type in))))))



(defn tarball-writer
  "Returns a `TarGzWriter` object, which can be used to write entries to a
  tar/gzip archive.  The input to this function is either a filename, a File
  object, or an `OutputStream`; the writer will write to the file or stream
  accordingly."
  [out]
  {:post [(instance? TarGzWriter %)]}
  (let [out-stream  (get-outstream out)
        gzip-stream (GzipCompressorOutputStream. out-stream)
        tar-stream  (TarArchiveOutputStream. gzip-stream)
        tar-writer  (writer tar-stream)]
    (TarGzWriter. tar-stream tar-writer gzip-stream)))

(defn add-entry
  "Add an entry to a tar/gzip archive.  The arguments are:
  - writer : a `TarGzWriter` (see `tarball-writer`) to write to
  - path   : a String or File defining the path (relative to the root of the archive)
             for this entry
  - data   : a String containing the data to write as the tar entry"
  [^TarGzWriter writer path data]
  {:pre  [(instance? TarGzWriter writer)
          (string? path)
          (string? data)]}
  (let [tar-stream (:tar-stream writer)
        tar-writer (:tar-writer writer)
        tar-entry  (TarArchiveEntry. path)]
    (.setSize tar-entry (count data))
    (.setModTime tar-entry (to-date (now)))
    (.putArchiveEntry tar-stream tar-entry)
    (.write tar-writer data)
    (.flush tar-writer)
    (.closeArchiveEntry tar-stream)))

(defn tarball-reader
  "Returns a `TarGzReader` object, which can be used to read entries from a
  tar/gzip archive.  The input to this function is either a filename, a File
  object, or an `InputStream`; the reader will read from the file or stream
  accordingly."
  [in]
  {:post [(instance? TarGzReader %)]}
  (let [in-stream   (get-instream in)
        gzip-stream (GzipCompressorInputStream. in-stream)
        tar-stream  (TarArchiveInputStream. gzip-stream)
        tar-reader  (reader tar-stream)
        tar-entry   (atom nil)]
    (TarGzReader. in-stream gzip-stream tar-stream tar-reader tar-entry)))


(defn next-entry
  "Given a `TarGzReader`, get the next `TarArchiveEntry` from the tar archive.
  Returns `nil` if there are no more entries in the archive."
  [^TarGzReader reader]
  {:pre  [(instance? TarGzReader reader)]
   :post [((some-fn nil? #(instance? TarArchiveEntry %)) %)]}
  (let [tar-entry (:tar-entry reader)]
    (reset! tar-entry (.getNextTarEntry (:tar-stream reader)))
    @tar-entry))

(defn find-entry
  "Given a `TarGzReader` and a relative file path, returns the `TarArchiveEntry`
  from the archive corresponding to the specified path.  Returns `nil` if no
  matching entry is found."
  [^TarGzReader reader path]
  {:pre  [(instance? TarGzReader reader)
          (string? path)]
   :post [((some-fn nil? #(instance? TarArchiveEntry %)) %)]}
  (loop [tar-entry (next-entry reader)]
    (cond
      (nil? tar-entry)
        nil
      (= path (.getName tar-entry))
        tar-entry
      :else
        (recur (next-entry reader)))))

(defn read-entry-content
  "Given a `TarGzReader`, reads and returns the contents of the current `TarArchiveEntry`
  as a String."
  [^TarGzReader reader]
  {:pre  [(instance? TarGzReader reader)]
   :post [(string? %)]}
  (let [tar-entry    @(:tar-entry  reader)
        tar-reader   (:tar-reader reader)
        entry-length (.getSize tar-entry)
        buffer       (char-array entry-length)]
    (.read tar-reader buffer 0 entry-length)
    (String. buffer)))




