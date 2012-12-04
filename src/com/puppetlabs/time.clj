;; ## Time-related Utility Functions
;;
;; This namespace contains some utility functions for working with objects
;; related to time; it is mostly based off of the `Period` class from
;; Java's JodaTime library.

(ns com.puppetlabs.time
  (:import (org.joda.time.format PeriodFormatterBuilder PeriodFormatter)
           (org.joda.time Period ReadablePeriod PeriodType)))

;; Functions for parsing Periods from Strings

(defn- build-parser
  "A utility function that builds up a joda-time `PeriodFormatter` instance that
  can be used for parsing strings into `Period`s.  The parser returned by this
  function is intended to parse a single unit of time from a string formatted as
  `'%i%s'`, where `%i` is a positive integer, and `%s` is a suffix string used
  to determine what unit of time we're returning.

  `f` - A function that accepts an instance of `PeriodFormatterBuilder` and
        calls the appropriate `append*` method for the desired unit of time.
  `suffix` - the suffix string that will cause this parser to match."
  ([f suffix]
   (build-parser f suffix suffix))
  ([f singular-suffix plural-suffix]
   {:pre   [(fn? f)
            (string? singular-suffix)
            (string? plural-suffix)]
    :post  [(instance? PeriodFormatter %)]}
   (-> (PeriodFormatterBuilder.)
       (f)
       (.appendSuffix singular-suffix plural-suffix)
       (.toFormatter))))

(def day-parser
  "A parser that matches strings ending with `d` and returns a `Period` of days"
  (build-parser #(.appendDays %) "d"))

(def hour-parser
  "A parser that matches strings ending with `h` and returns a `Period` of hours"
  (build-parser #(.appendHours %) "h"))

(def minute-parser
  "A parser that matches strings ending with `m` and returns a `Period` of minutes"
  (build-parser #(.appendMinutes %) "m"))

(def second-parser
  "A parser that matches strings ending with `s` and returns a `Period` of seconds"
  (build-parser #(.appendSeconds %) "s"))

(def millisecond-parser
  "A parser that matches strings ending with `ms` and returns a `Period` of
  milliseconds"
  (build-parser #(.appendMillis %) "ms"))

(def period-parser
  "A parser that matches strings ending with `d`, `h`, `m`, `s`, or `ms` and
  returns a `Period` object representing the specified amount of time."
  (.. (PeriodFormatterBuilder.)
      ; it's important that "millisecond" parser comes first in this list,
      ; because the suffix string is a superset of the suffix string for
      ; "minutes"... so if the "minute" parser is earlier in the list,
      ; it will recognize the "m" from a millisecond string, try to parse it,
      ; fail, and throw an exception.
      (append millisecond-parser)
      (append second-parser)
      (append minute-parser)
      (append hour-parser)
      (append day-parser)
      (toFormatter)))

(defn parse-period
  "Parse a String into an instance of `Period`, representing a duration of time.
  For example, `(parse-period \"2d\")` returns a `Period` representing a duration
  of 2 days.  Currently supported suffixes are `'d'`, `'h'`, `'m'`, `'s'`, and
  `'ms'`."
  [s]
  {:pre  [(string? s)]
   :post [(instance? Period %)]}
  (.parsePeriod period-parser s))

;; Functions for converting `Period` instances to readable strings

(def day-formatter
  "A formatter that converts Day `Period` objects to strings formatted as, e.g.,
  '2 days'."
  (build-parser #(.appendDays %) " day" " days"))

(def hour-formatter
  "A formatter that converts Hour `Period` objects to strings formatted as,
  e.g., '2 hours'."
  (build-parser #(.appendHours %) " hour" " hours"))

(def minute-formatter
  "A formatter that converts Minute `Period` objects to strings formatted as,
  e.g., '2 minutes'."
  (build-parser #(.appendMinutes %) " minute" " minutes"))

(def second-formatter
  "A formatter that converts Second `Period` objects to strings formatted as,
  e.g., '2 seconds'."
  (build-parser #(.appendSeconds %) " second" " seconds"))

(def millisecond-formatter
  "A formatter that converts  `Period` objects to strings formatted as, e.g.,
  '2 milliseconds'."
  (build-parser #(.appendMillis %) " millisecond" " milliseconds"))

(def period-formatter
  "A formatter that converts `Period` objects to human-readable strings."
  (.. (PeriodFormatterBuilder.)
    ; it's important that "millisecond" parser comes first in this list,
    ; because the suffix string is a superset of the suffix string for
    ; "minutes"... so if the "minute" parser is earlier in the list,
    ; it will recognize the "m" from a millisecond string, try to parse it,
    ; fail, and throw an exception.
    (append millisecond-formatter)
    (append second-formatter)
    (append minute-formatter)
    (append hour-formatter)
    (append day-formatter)
    (toFormatter)))

(defn format-period
  "Convert a `Period` object into a human-readable String; e.g.:

      `(format-period (clj-time.core/secs 120))`

  returns '2 minutes'.  The `Period` will only be normalized to
  the largest round unit; e.g., a `Period` of 121 seconds will
  not be normalized to minutes."
  [p]
  {:pre  [(instance? ReadablePeriod p)]
   :post [(string? %)]}
  (let [normalized   (.. p
                         (toPeriod)
                         (normalizedStandard (PeriodType/dayTime)))
        largest-unit (condp > 0
                        (.getMillis normalized)  (PeriodType/millis)
                        (.getSeconds normalized) (PeriodType/seconds)
                        (.getMinutes normalized) (PeriodType/minutes)
                        (.getHours normalized)   (PeriodType/hours)
                        (PeriodType/days))
        normalized   (.normalizedStandard normalized largest-unit)]
    (.print period-formatter normalized)))


;; Comparison functions

(defn periods-equal?
  "Given two or more instances of `Period`, returns true if they all represent
  the same duration of time (regardless of whether or not they are specified in
  the same units of time... in other words, the following will return `true`:

      `(periods-equal? (days 2) (hours 48))`

  even though this will return `false`:

      `(= (days 2) (hours 48))`"
  ([p] true)
  ([p1 p2] (= (.toStandardDuration p1) (.toStandardDuration p2)))
  ([p1 p2 & more]
    (if (periods-equal? p1 p2)
      (if (next more)
        (recur p2 (first more) (next more))
        (periods-equal? p2 (first more)))
      false)))

;; Conversion functions (convert `Period` instances to numeric values of a
;; specified time unit)

(defn- to-unit
  "Helper function for converting periods to specific units"
  [f period]
  {:pre  [(instance? ReadablePeriod period)]
   :post [(>= % 0)] }
  (-> period
      (.toStandardDuration)
      (f)))

(def to-days
  "Given an instance of `Period`, return an integer representing the number of days"
  (partial to-unit #(.getStandardDays %)))

(def to-hours
  "Given an instance of `Period`, return an integer representing the number of hours"
  (partial to-unit #(.getStandardHours %)))

(def to-minutes
  "Given an instance of `Period`, return an integer representing the number of minutes"
  (partial to-unit #(.getStandardMinutes %)))

(def to-secs
  "Given an instance of `Period`, return an integer representing the number of seconds"
  (partial to-unit #(.getStandardSeconds %)))

(def to-millis
  "Given an instance of `Period`, return an integer representing the number of milliseconds"
  (partial to-unit #(.getMillis %)))

