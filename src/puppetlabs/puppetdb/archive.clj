(ns puppetlabs.puppetdb.archive
  "Compression / Archive Utility library

   This namespace contains functions for reading and writing compressed
   archive files. Currently only supports gzipped tar archives."
  (:import [java.io
            Closeable
            File
            OutputStream
            FileOutputStream
            IOException
            InputStream
            FileInputStream]
           [org.apache.commons.compress.archivers.tar
            TarArchiveEntry
            TarArchiveOutputStream
            TarArchiveInputStream]
           [org.apache.commons.compress.compressors.gzip
            GzipCompressorOutputStream
            GzipCompressorInputStream])
  (:require [clojure.java.io :refer :all]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-date]]
            [puppetlabs.i18n.core :refer [tru]]))

;; A simple type for writing tar/gz streams
(defrecord TarGzWriter [tar-stream tar-writer gzip-stream]
  Closeable
  (close [this]
    (.close tar-stream)
    (.close gzip-stream)))

;; A simple type for reading tar/gz streams
(defrecord TarGzReader [gzip-stream tar-stream tar-reader tar-entry]
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
           (tru "Unable to convert type ''{0}'' to an OutputStream; expected String, File, or OutputStream"
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
           (tru "Unable to convert type ''{0}'' to an InputStream; expected String, File, or InputStream"
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
        tar-stream  (doto
                        (new TarArchiveOutputStream gzip-stream)
                      (.setLongFileMode TarArchiveOutputStream/LONGFILE_POSIX))
        tar-writer  (writer tar-stream)]
    (TarGzWriter. tar-stream tar-writer gzip-stream)))

(defn add-entry
  "Add an entry to a tar/gzip archive.  The arguments are:
  - writer   : a `TarGzWriter` (see `tarball-writer`) to write to
  - encoding : a String specifying the encoding of the data; defaults to UTF-8
  - path     : a String or File defining the path (relative to the root of the archive)
               for this entry
  - data     : a String containing the data to write as the tar entry"
  [^TarGzWriter writer encoding path data]
  {:pre  [(instance? TarGzWriter writer)
          (string? encoding)
          (string? path)
          (string? data) ]}
  (let [tar-stream (:tar-stream writer)
        tar-writer (:tar-writer writer)
        tar-entry  (TarArchiveEntry. path)]
    (.setSize tar-entry (count (.getBytes data encoding)))
    (.setModTime tar-entry (to-date (now)))
    (.putArchiveEntry tar-stream tar-entry)
    (.write tar-writer data)
    (.flush tar-writer)
    (.closeArchiveEntry tar-stream)))

;; Lovingly adapted from fs
(defn tar
  "Creates a tar file called `filename` consisting of the files specified as
  filename/content pairs, with content specified in `encoding`."
  [filename encoding & filename-content-pairs]
  {:pre [(string? filename)
         (string? encoding)]}
  (with-open [tarball (tarball-writer filename)]
    (doseq [[filename content] filename-content-pairs]
      (add-entry tarball encoding filename content))))

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
    (TarGzReader. gzip-stream tar-stream tar-reader tar-entry)))


(defn next-entry
  "Given a `TarGzReader`, get the next `TarArchiveEntry` from the tar archive.
  Returns `nil` if there are no more entries in the archive."
  [^TarGzReader reader]
  {:pre  [(instance? TarGzReader reader)]
   :post [((some-fn nil? #(instance? TarArchiveEntry %)) %)]}
  (let [tar-entry (:tar-entry reader)]
    (reset! tar-entry (.getNextTarEntry (:tar-stream reader)))
    @tar-entry))

(defn all-entries
  "Returns a lazy sequence of all of the entries in the tar-reader.  The stream
  will not be advanced to the next entry until you request the next item from
  the sequence.  Note that this sequence is very much *NOT* thread-safe; if you
  begin to read the data for an entry in one thread and then advance the stream
  to the next entry in another thread, spectacular and confusing failures are
  likely to ensue."
  ([tar-reader] (all-entries tar-reader (next-entry tar-reader)))
  ([tar-reader next-entry]
     (if next-entry
       (cons next-entry (lazy-seq (all-entries tar-reader)))
       '())))

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
