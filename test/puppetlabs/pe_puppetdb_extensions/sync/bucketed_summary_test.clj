(ns puppetlabs.pe-puppetdb-extensions.sync.bucketed-summary-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pe-puppetdb-extensions.sync.bucketed-summary :refer :all]
            [clj-time.coerce :refer [to-date-time]]))

(deftest next-clock-hour-test
  (are [in out] (= (to-date-time out) (next-clock-hour (to-date-time in)))
    "2014-12-31T22:00" "2014-12-31T23:00"
    "2014-12-31T23:00" "2015-01-01T00:00"
    "2014-12-31T23:30" "2015-01-01T00:00"))

(deftest take-while-consecutive-test
  (are [in out] (= out (take-while-consecutive inc in))
    [1 2 3 5 6 7] [1 2 3]
    [1 3 4] [1]
    [1] [1]
    [] []))

(deftest group-consecutive-test
  (are [in out] (= out (group-consecutive inc in))
    [1 2 3 5 6 7] [[1 2 3] [5 6 7]]
    [1 3 4] [[1] [3 4]]
    [1] [[1]]
    [] []))

(deftest hourly-bucket-timestamps-to-timespans-test
  (let [[a b c d e f g h]
        (map to-date-time
             ["2014-12-31T22"
              "2014-12-31T23"
              "2015-01-01T00"
              "2015-01-01T01"
              "2015-01-01T02"
              "2015-01-01T03"
              "2015-01-01T04"
              "2015-01-01T05"])]
    (are [in out] (= out (hourly-bucket-timestamps-to-timespans in))
      [c d e g h] [[c (next-clock-hour e)] [g (next-clock-hour h)]]
      [a b c d e f] [[a (next-clock-hour f)]])))

(defn maybe-to-date-time [x]
  (if (= x :open)
    :open
    (to-date-time x)))

(deftest timespan-seq-complement-test
  (are [in out] (= out (timespan-seq-complement in))
    [[(to-date-time "2014-12-31T22") (to-date-time "2015-01-01T00")]]
    [[:open (to-date-time "2014-12-31T22")] [(to-date-time "2015-01-01T00") :open]]

    [[(to-date-time "2014-12-31T22") (to-date-time "2015-01-01T00")]
     [(to-date-time "2015-01-01T03") (to-date-time "2015-01-01T05")]]
    [[:open (to-date-time "2014-12-31T22")]
     [(to-date-time "2015-01-01T00") (to-date-time "2015-01-01T03")]
     [(to-date-time "2015-01-01T05") :open]]))

(deftest sql-query-condition-for-timespans-test
  (is (= (str "tstzrange(NULL,'2014-01-01','[)') @> producer_timestamp OR "
              "tstzrange('2014-01-03','2014-01-04','[)') @> producer_timestamp OR "
              "tstzrange('2014-01-06',NULL,'[)') @> producer_timestamp")
         (sql-query-condition-for-timespans
          [[:open "2014-01-01"]
           ["2014-01-03" "2014-01-04"]
           ["2014-01-06" :open]]))))

(deftest pdb-query-condition-for-timespans-test
  (are [spans q] (= q (pdb-query-condition-for-timespans spans))
    [[1 2] [3 4]] ["or"
                   ["and" [">=" "producer_timestamp" "1"] ["<" "producer_timestamp" "2"]]
                   ["and" [">=" "producer_timestamp" "3"] ["<" "producer_timestamp" "4"]]]

    [[:open 2] [3 4]] ["or"
                       ["<" "producer_timestamp" "2"]
                       ["and" [">=" "producer_timestamp" "3"] ["<" "producer_timestamp" "4"]]]

    [[1 2] [3 :open]] ["or"
                       ["and" [">=" "producer_timestamp" "1"] ["<" "producer_timestamp" "2"]]
                       [">=" "producer_timestamp" "3"]]))
