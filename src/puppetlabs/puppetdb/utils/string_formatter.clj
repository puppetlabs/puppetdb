(ns puppetlabs.puppetdb.utils.string-formatter
  (:require [clojure.string :as string]
            [com.rpl.specter :as spector]
            [schema.core :as schema]
            [puppetlabs.i18n.core :refer [tru]]
            [puppetlabs.kitchensink.core :refer [parse-int]]
            [puppetlabs.puppetdb.utils :refer [optional-key?]]))

(defn re-quote
  "Quotes s so that all of its characters will be matched literally."
  [s]
  ;; Wrap all segments not containing a \E in \Q...\E, and replace \E
  ;; with \\E.
  (apply str
         "\\Q"
         (concat (->> (string/split s #"\\E" -1)
                      (interpose "\\E\\\\E\\Q"))
                 ["\\E"])))

(defn quoted
  [s]
  (str "'" s "'"))

(defn comma-separated-keywords
  [words]
  (let [quoted-words (map quoted words)]
    (if (> (count quoted-words) 2)
      (str (string/join ", " (butlast quoted-words)) ", " "and " (last quoted-words))
      (string/join " and " quoted-words))))

(defn dashes->underscores
  "Accepts a string or a keyword as an argument, replaces all occurrences of the
  dash/hyphen character with an underscore, and returns the same type (string
  or keyword) that was passed in.  This is useful for translating data structures
  from their wire format to the format that is needed for JDBC."
  [str]
  (let [result (string/replace (name str) \- \_)]
    (if (keyword? str)
      (keyword result)
      result)))

(defn underscores->dashes
  "Accepts a string or a keyword as an argument, replaces all occurrences of the
   underscore character with a dash, and returns the same type (string
   or keyword) that was passed in.  This is useful for translating data structures
   from their JDBC-compatible representation to their wire format representation."
  [s]
  (let [opt-key? (optional-key? s)
        result (if opt-key?
                 (string/replace (name (:k s)) \_ \-)
                 (string/replace (name s) \_ \-))]
    (cond
      opt-key? (if (keyword? (:k s))
                 (schema/optional-key (keyword result))
                 (schema/optional-key result))
      (keyword? s) (keyword result)
      :else result)))

(defn dash->underscore-keys
  "Converts all top-level keys (including nested maps) in `m` to use dashes
  instead of underscores as word separators"
  [m]
  (spector/transform [spector/ALL]
                #(update % 0 dashes->underscores)
                m))

(defn underscore->dash-keys
  "Converts all top-level keys (including nested maps) in `m` to use underscores
  instead of underscores as word separators"
  [m]
  (spector/transform [spector/ALL]
                #(update % 0 underscores->dashes)
                m))

(defn pprint-json-parse-exception
  "Returns a parsed JsonParseException message.
  From the second line of the error message, the line and
  column index are extracted, so that we can place an arrow,
  pointing at that position."
  [error query]
  (let [error-lines (string/split-lines (.getMessage error))
        [_ line column] (re-find #"line: (\d+), column: (\d+)" (second error-lines))
        error-line-index (parse-int line)
        error-column-index (parse-int column)

        ;subtracting 1 from the line and column index, because they start from 1, not 0
        query-line-index (dec error-line-index)
        query-column-index (dec error-column-index)]

    (tru "Json parse error at line {0}, column {1}:\n\n{2}\n{3}^\n\n{4}"
         error-line-index,
         error-column-index,
         ;query line that produced the parsing error
         ((string/split-lines query) query-line-index),
         ;arrow that indicates which string in the line raised the error
         ;subtracting an additional 1 from the column index,
         ;to make room for the arrow sign
         (apply str (repeat (dec query-column-index) " ")),
         ;the error message
         (first error-lines))))
