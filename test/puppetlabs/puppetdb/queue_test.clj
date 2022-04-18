(ns puppetlabs.puppetdb.queue-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.queue :refer :all]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.constants :as constants]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.testutils.queue :as tqueue]
            [puppetlabs.puppetdb.testutils :as tu]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.testutils.nio :as nio]
            [puppetlabs.puppetdb.utils :refer [utf8-length utf8-truncate]]
            [puppetlabs.puppetdb.command.constants :as cconst]
            [puppetlabs.puppetdb.time :as time
             :refer [now days parse-wire-datetime seconds]]))

(defn catalog->command-req [version {:keys [certname name] :as catalog}]
  (create-command-req "replace catalog"
                            version
                            (or certname name)
                            nil
                            ""
                            identity
                            (tqueue/coerce-to-stream catalog)))


(deftest test-sanitize-certname
  (are [raw sanitized] (= sanitized (sanitize-certname raw))
    "foo/bar" "foo-bar"
    "foo/bar/baz" "foo-bar-baz"
    "/foo/" "-foo-"
    "/foo//bar///baz/" "-foo--bar---baz-")
  (doseq [bad-char (conj constants/filename-forbidden-characters \_)]
    (is (= "sanitize-me" (sanitize-certname (format "sanitize%cme" bad-char))))))

(defn cmd-req-stub [producer-ts command version certname compression]
  {:command command
   :version version
   :certname certname
   :producer-ts producer-ts
   :compression compression})

(deftest test-metadata-serializer
  (let [recvd (now)
        recvd-long (time/to-long recvd)
        cmd "replace facts"
        cmd-abbrev (puppetdb-command->metadata-command cmd)
        cmd-ver 4
        cert->meta #(format "%d_%s_%d_%s.json" recvd-long cmd-abbrev cmd-ver %)]

    (testing "certnames are sanitized"
      (let [cname "foo_bar/baz"
            safe-cname "foo-bar-baz"
            cname-hash (kitchensink/utf8-string->sha1 cname)
            compression ""
            req (cmd-req-stub nil cmd cmd-ver cname compression)]
        (is (= (format "%d_%s_%d_%s_%s.json" recvd-long cmd-abbrev cmd-ver safe-cname cname-hash)
               (serialize-metadata recvd req false)))))

    (testing "long certnames are truncated"
      (let [long-cname (apply str "trol" (repeat 1000 "lo"))
            cname-hash (kitchensink/utf8-string->sha1 long-cname)
            trunc-cname (utf8-truncate long-cname (- max-metadata-utf8-bytes
                                                     41
                                                     (utf8-length (cert->meta ""))))
            req (cmd-req-stub nil cmd cmd-ver long-cname "")
            expected (cert->meta (str trunc-cname "_" cname-hash))
            serialized (serialize-metadata recvd req false)]
        (is (= expected serialized))
        (is (= max-metadata-utf8-bytes (utf8-length serialized)))))

    (testing "utf-8 multi-byte characters are truncated correctly"
      (let [disapproval-monster (->> "ಠ_"
                                     (repeat (inc (/ max-metadata-utf8-bytes 4)))
                                     (apply str))
            req (cmd-req-stub nil cmd cmd-ver disapproval-monster "")]
        (is (= max-metadata-utf8-bytes
               (utf8-length (serialize-metadata recvd req false))))))

    (testing "sanitized certnames are truncated to leave room for hash"
      (let [tricky-cname (apply str "____" (repeat max-metadata-utf8-bytes "o"))
            sanitized (str/replace tricky-cname \_ \-)
            cname-hash (kitchensink/utf8-string->sha1 tricky-cname)
            trunc-cname (utf8-truncate sanitized (- max-metadata-utf8-bytes
                                                    41
                                                    (utf8-length (cert->meta ""))))
            req (cmd-req-stub nil cmd cmd-ver tricky-cname "")
            expected (cert->meta (str trunc-cname "_" cname-hash))
            serialized (serialize-metadata recvd req false)]
        (is (= expected serialized))
        (is (= max-metadata-utf8-bytes (utf8-length serialized)))))

    (testing "short & safe certnames are preserved and the hash is omitted"
      (let [cname "bender.myowncasino.moon"
            compression ""
            req (cmd-req-stub nil cmd cmd-ver cname compression)]
        (is (= (format "%d_%s_%d_%s.json" recvd-long cmd-abbrev cmd-ver cname)
               (serialize-metadata recvd req false)))))

    (testing "compression format is added"
      (let [cname "compress.me"
            compression "gz"
            req (cmd-req-stub nil cmd cmd-ver cname compression)]
        (is (= (format "%d_%s_%d_%s.json.%s"
                       recvd-long cmd-abbrev cmd-ver cname compression)
               (serialize-metadata recvd req false)))))))

(deftest test-metadata
  (tqueue/with-stockpile q
    (let [start (now)
          ;; Sleep to ensure the command has a different time
          _ (Thread/sleep 1)
          cmdref (->> {:message "payload"}
                      tqueue/coerce-to-stream
                      (create-command-req "replace facts" 1 "foo.com" nil "" identity)
                      (store-command q))
           command (cmdref->cmd q cmdref)]
      (is (= {:command "replace facts"
              :version 1
              :certname "foo.com"
              :payload {:message "payload"}}
             (select-keys command [:command :version :certname :payload])))
      (is (time/before? start (-> (get-in command [:annotations :received])
                                  parse-wire-datetime))))))

(deftest test-sorted-command-buffer
  (testing "newer catalogs/facts cause older catalogs to be deleted"
    (let [buff (sorted-command-buffer 4)
          c (async/chan buff)
          start (now)
          received-time (time/to-string start)
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts start})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts (time/plus start (time/seconds 10))})
          foo-cmd-3 (map->CommandRef {:id 3
                                      :command "replace facts"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts start})
          foo-cmd-4 (map->CommandRef {:id 4
                                      :command "replace facts"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts (time/plus start (time/seconds 10))})]
      (is (= 0 (count buff)))

      (are [cmd] (async/offer! c cmd)
        foo-cmd-1
        foo-cmd-2
        foo-cmd-3
        foo-cmd-4)

      (is (not (async/offer! c "should not be added")))

      (is (= (assoc foo-cmd-1 :delete? true)
             (async/<!! c)))
      (is (= foo-cmd-2
             (async/<!! c)))
      (is (= (assoc foo-cmd-3 :delete? true)
             (async/<!! c)))
      (is (= foo-cmd-4
             (async/<!! c)))))

  (testing "a new catalog after the previous one was processed"
    (let [buff (sorted-command-buffer 1)
          c (async/chan buff)
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (time/to-string (now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (time/to-string (now))})]
      (is (= 0 (count buff)))

      (is (async/offer! c foo-cmd-1))
      (is (= foo-cmd-1 (async/<!! c)))

      (is (async/offer! c foo-cmd-2))
      (is (= foo-cmd-2 (async/<!! c)))))

  (testing "multiple older catalogs all get marked as deleted"
    (let [start (now)
          received-time (time/to-string start)
          c (async/chan (sorted-command-buffer 3))
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts start})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts (time/plus start (time/seconds 10))})
          foo-cmd-3 (map->CommandRef {:id 3
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts (time/plus start (time/seconds 20))})]
      (are [cmd] (async/offer! c cmd)
        foo-cmd-1
        foo-cmd-2
        foo-cmd-3)

      (is (= (assoc foo-cmd-1 :delete? true)
             (async/<!! c)))
      (is (= (assoc foo-cmd-2 :delete? true)
             (async/<!! c)))
      (is (= foo-cmd-3
             (async/<!! c)))))

  (testing "catalogs should be marked as deleted based on producer-ts, regardless of stockpile id"
    (let [start (now)
          received-time (time/to-string start)
          c (async/chan (sorted-command-buffer 3))
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts (time/plus start (time/seconds 20))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts (time/plus start (time/seconds 10))})
          foo-cmd-3 (map->CommandRef {:id 3
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts start})]
      (are [cmd] (async/offer! c cmd)
        foo-cmd-3
        foo-cmd-2
        foo-cmd-1)

      (is (= foo-cmd-1
             (async/<!! c)))
      (is (= (assoc foo-cmd-2 :delete? true)
             (async/<!! c)))
      (is (= (assoc foo-cmd-3 :delete? true)
             (async/<!! c)))))

  (testing "multiple reports should remain unchanged"
    (let [c (async/chan (sorted-command-buffer 10))
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "store report"
                                      :certname "foo.com"
                                      :received (time/to-string (now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "store report"
                                      :certname "foo.com"
                                      :received (time/to-string (now))})]
      (are [cmd] (async/offer! c cmd)
        foo-cmd-1
        foo-cmd-2)

      (is (= foo-cmd-1 (async/<!! c)))
      (is (= foo-cmd-2 (async/<!! c)))))

  (testing "catalogs without a producer-ts should never be bashed"
    (let [start (now)
          received-time (time/to-string start)
          c (async/chan (sorted-command-buffer 3))
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts nil})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts nil})
          foo-cmd-3 (map->CommandRef {:id 3
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts start})]
      (are [cmd] (async/offer! c cmd)
        foo-cmd-3
        foo-cmd-2
        foo-cmd-1)

      (is (= foo-cmd-1
             (async/<!! c)))
      (is (= foo-cmd-2
             (async/<!! c)))
      (is (= foo-cmd-3
             (async/<!! c)))))

  (testing "catalogs without a producer-ts should remain, but with a timestamp are fair game"
    (let [start (now)
          received-time (time/to-string start)
          c (async/chan (sorted-command-buffer 3))
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts nil})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts (time/plus start (time/seconds 10))})
          foo-cmd-3 (map->CommandRef {:id 3
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received received-time
                                      :producer-ts start})]
      (are [cmd] (async/offer! c cmd)
        foo-cmd-3
        foo-cmd-2
        foo-cmd-1)

      (is (= foo-cmd-1
             (async/<!! c)))
      (is (= foo-cmd-2
             (async/<!! c)))
      (is (= (assoc foo-cmd-3 :delete? true)
             (async/<!! c))))))

(deftest test-loading-existing-messages
  (testing "loading existing messages into a channel"
    (nio/call-with-temp-dir-path
     (get-path "target")
     (str *ns*)
     (fn [temp-path]
       (let [maybe-send-cmd-event! (constantly true)
             cmd-event-ch (async/chan 10)
             [q load-messages] (create-or-open-stockpile temp-path maybe-send-cmd-event! cmd-event-ch)]

         (is (nil? load-messages))
         (store-command q (catalog->command-req 1 {:message "payload 1"
                                                         :certname "foo1"}))
         (store-command q (catalog->command-req 1 {:message "payload 2"
                                                         :certname "foo2"}))
         (store-command q (catalog->command-req 1 {:message "payload 3"
                                                         :certname "foo3"}))
         (store-command q (catalog->command-req 1 {:message "payload 4"
                                                         :certname "foo4"})))

       (let [maybe-send-cmd-event! (constantly true)
             cmd-event-ch (async/chan 10)
             [q load-messages] (create-or-open-stockpile temp-path maybe-send-cmd-event! cmd-event-ch)
             command-chan (async/chan 4)
             cc (tu/call-counter)]

         (load-messages command-chan cc)

         (is (= #{"foo1" "foo2" "foo3" "foo4"}
                (set (map :certname (repeatedly 4 #(async/poll! command-chan))))))

         (is (= 4 (tu/times-called cc)))
         (is (= (repeat 4 ["replace catalog" 1])
                (tu/args-supplied cc)))

         (is (nil? (async/poll! command-chan)))))))

  (testing "adding new commands while loading existing commands"
    (nio/call-with-temp-dir-path
     (get-path "target")
     (str *ns*)
     (fn [temp-path]
       (let [maybe-send-cmd-event! (constantly true)
             cmd-event-ch (async/chan 10)
             [q load-messages] (create-or-open-stockpile temp-path maybe-send-cmd-event! cmd-event-ch)]

         (is (nil? load-messages))
         (store-command q (catalog->command-req 1 {:message "payload 1"
                                                   :certname "foo1"}))
         (store-command q (catalog->command-req 1 {:message "payload 2"
                                                   :certname "foo2"})))

       (let [maybe-send-cmd-event! (constantly true)
             cmd-event-ch (async/chan 10)
             [q load-messages] (create-or-open-stockpile temp-path maybe-send-cmd-event! cmd-event-ch)
             command-chan (async/chan 4)
             cc (tu/call-counter)]

         (store-command q (catalog->command-req 1 {:message "payload 3"
                                                   :certname "foo3"}))
         (store-command q (catalog->command-req 1 {:message "payload 4"
                                                   :certname "foo4"}))


         (load-messages command-chan cc)

         (is (= 2 (tu/times-called cc)))
         (is (= (repeat 2 ["replace catalog" 1])
                (tu/args-supplied cc)))

         (is (= #{"foo1" "foo2"}
                (set (map :certname (repeatedly 2 #(async/poll! command-chan))))))

         (is (not (async/poll! command-chan))))))))

(deftest test-producer-serialization
  (let [received-time (now)
        received-time-long (time/to-long received-time)]
    (is (= (str received-time-long "-5000")
           (encode-command-time received-time
                                (time/minus received-time (seconds 5)))))
    (is (= (str received-time-long "+0")
           (encode-command-time received-time received-time)))
    (is (= (str received-time-long "+5000")
           (encode-command-time received-time
                                (time/plus received-time (seconds 5)))))))

(deftest test-metadata-parsing
  (let [received (now)
        producer-ts (time/minus received (seconds 5))
        compression ""
        req (cmd-req-stub producer-ts
                          "replace catalog" cconst/latest-catalog-version
                          "foo.com" compression)]
    (is (= {:certname "foo.com"
            :command "replace catalog"
            :version cconst/latest-catalog-version
            :received (kitchensink/timestamp received)
            :producer-ts producer-ts
            :compression compression}
           (parse-metadata (serialize-metadata received req false)))))

  (let [received (now)
        producer-ts (time/plus received (seconds 5))
        compression "gz"
        req (cmd-req-stub producer-ts
                          "replace facts" cconst/latest-facts-version
                          "foo.com" compression)]
    (is (= {:certname "foo.com"
            :command "replace facts"
            :version cconst/latest-facts-version
            :received (kitchensink/timestamp received)
            :producer-ts producer-ts
            :compression compression}
           (parse-metadata (serialize-metadata received req false)))))

  (let [received (now)
        producer-ts received
        compression "gz"
        req (cmd-req-stub producer-ts
                          "store report" cconst/latest-report-version
                          "foo.com" compression)]
    (is (= {:certname "foo.com"
            :command "store report"
            :version cconst/latest-report-version
            :received (kitchensink/timestamp received)
            :producer-ts producer-ts
            :compression compression}
           (parse-metadata (serialize-metadata received req false))))))
