(ns puppetlabs.puppetdb.anonymizer
  (:require [clojure.string :as str]
            [puppetlabs.kitchensink.core :refer [regexp? uuid string-contains?]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.random
             :refer [random-bool
                     random-node-name
                     random-pp-path
                     random-string
                     random-string-alpha
                     random-type-name]]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.schema :as pls])
  (:import [org.apache.commons.lang3 StringUtils]))

;; Validation functions, for use within pre/post conditions

(defn edge?
  "Returns true if it looks like an edge"
  [edge]
  (and
   (map? edge)
   (contains? edge "source")
   (contains? edge "target")
   (contains? edge "relationship")))

(def event-schema-str (utils/str-schema reports/event-wireformat-schema))
(def resource-schema-str (utils/str-schema (assoc reports/resource-wireformat-schema
                                                  :events [event-schema-str])))
(def metric-schema-str (utils/str-schema reports/metric-wireformat-schema))
(def log-schema-str (utils/str-schema reports/log-wireformat-schema))
(def report-schema-str (utils/str-schema (assoc reports/report-wireformat-schema
                                                :resources [resource-schema-str]
                                                :metrics [metric-schema-str]
                                                :logs [log-schema-str])))

(defn resource?
  "Returns true if it looks like a resource"
  [resource]
  (and
   (map? resource)
   (contains? resource "parameters")
   (contains? resource "exported")
   (contains? resource "tags")
   (contains? resource "title")
   (contains? resource "type")))

(defn catalog?
  "Returns true if it looks like a catalog"
  [catalog]
  ;; I would have liked to have used the catalog/validate validation, however
  ;; it isn't designed for the post-processed format, only the original format
  ;; that the master gives us.
  (and
   (map? catalog)
   (contains? catalog "certname")
   (contains? catalog "version")))

;; Rules engine functions

(defn pattern-string?
  "Returns true if the string looks like a pattern"
  [string]
  {:pre [(string? string)]
   :post [(boolean? %)]}
  (boolean (re-find #"^\/.+\/" string)))

(defn pattern->regexp
  "Converts a string pattern of the form: /myregexp/ to a proper regexp"
  [pattern]
  {:pre [(string? pattern) (pattern-string? pattern)]
   :post [(regexp? %)]}
  (re-pattern (.substring pattern 1 (dec (count pattern)))))

(defn matcher-match?
  "Compares a rule matcher against a value, returning true if it's a match."
  [test value]
  {:post [(boolean? %)]}
  (cond
   (string? test) (if (pattern-string? test)
                    (boolean (some->> value (re-find (pattern->regexp test))))
                    (= test value))
   (vector? test) (boolean (some true? (map #(matcher-match? % value) test)))))

(defn rule-match?
  "Given a single rule map, and a context map returns true if the rule matches.

  We perform this test by iterating across all the keys defined in the rule, and
  seeing if the matchers in the value match against the data in the context.

  We'll only return true if single defined matcher is matched."
  [rule context]
  {:pre [(map? rule)
         (map? context)]
   :post [(boolean? %)]}
  (let [rule-context (get rule "context")]
    (every? true?
            (for [[k test] rule-context
                  :let [value (get context k)]]
              (if (and (coll? value) (empty? value))
                false
                (matcher-match? test value))))))

(defn rules-match
  "Cycles through a set of rules, return the value of the :anonymize parameter
  if there is a match"
  [rules context]
  {:pre [(or (coll? rules) (nil? rules))
         (map? context)]
   :post [(boolean? %)]}
  (loop [[x :as xs] rules]
    (cond
      ;; Default to returning true if there is no match
      (empty? xs) true
      (rule-match? x context) (get x "anonymize")
      :else (recur (rest xs)))))

(defn anonymize-text
  "This is for anonymizing text data where we care only about size, not content."
  [text]
  (when text
    (StringUtils/repeat "?" (count text))))

;; Functions for anonymizing the final leaf data
(defn anonymize-leaf-value
  "Based on the input value, return an appropriate random replacement"
  [value]
  (cond
   (string? value) (random-string (max 10 (count value)))
   (integer? value) (rand-int (max value 20))
   (float? value) (rand (max value 1))
   (boolean? value) (random-bool)
   (map? value) (zipmap (map #(random-string (max 10 (count (name %)))) (keys value))
                        (vals (utils/update-vals value (keys value)
                                                 anonymize-leaf-value)))
   (nil? value) nil
   :else (random-string 30)))

(def anonymize-leaf-memoize
  (memoize
   (fn [ltype value]
     (case ltype
       :node (random-node-name)
       :type (random-type-name)
       :title (random-string (max 10 (count value)))
       :parameter-name (random-string-alpha (max 10 (count value)))
       :catalog-input-type (random-string-alpha (max 10 (count value)))
       :catalog-input-value (random-string-alpha (max 10 (count value)))
       :text (anonymize-text value)
       :message (random-string (max 10 (count value)))
       :file (random-pp-path)
       :line (when value (rand-int (max value 20)))
       :transaction_uuid (uuid)
       :fact-name (random-string (max 10 (count (name value))))
       :environment (random-string (max 10 (count value)))
       (:fact-value :parameter-value)
       (cond
         (vector? value) (map (partial anonymize-leaf-memoize ltype) value)
         (seq? value) (seq (map (partial anonymize-leaf-memoize ltype) value))
         :else (anonymize-leaf-value value))))))

(defn anonymize-leaf
  "Anonymize leaf data, if the context matches a rule"
  [value ltype context config]
  (let [type-rules (get-in config ["rules" (name ltype)])]
    (if (rules-match type-rules context)
      (anonymize-leaf-memoize ltype value)
      value)))

;; Functions for anonymizing data structures

(defn anonymize-reference
  "This anonymizes a reference entry, conditionally anonymizing based on rule"
  [rel context config]
  {:pre  [(string? rel)]
   :post [(= (type %) (type rel))]}
  (let [[_ rel-type rel-title] (re-matches #"(.+)\[(.+)\]" rel)
        ;; here we modify the context, as the anonymization of a reference
        ;; is not about where it appears
        newcontext {"node" (get context "node")
                    "title" rel-title
                    "type" rel-type}
        ;; optionally anonymize each part
        new-type (anonymize-leaf rel-type :type newcontext config)
        new-title (anonymize-leaf rel-title :title newcontext config)]
    (str new-type "[" new-title "]")))

(defn anonymize-references
  "Anonymize a collection of references"
  [rels context config]
  {:pre  [(or (coll? rels) (string? rels))]
   :post [(= (type %) (type rels))]}
  (if (coll? rels)
    (mapv #(anonymize-reference % context config) rels)
    (anonymize-reference rels context config)))

(defn anonymize-aliases
  "Anonymize a collection of aliases"
  [aliases context config]
  {:pre  [(or (coll? aliases) (string? aliases) (nil? aliases))]
   :post [(= (type %) (type aliases))]}
  (when-not (nil? aliases)
    (if (coll? aliases)
      (mapv #(anonymize-leaf % :title context config) aliases)
      (anonymize-leaf aliases :title context config))))

(defn anonymize-parameter
  "Anonymize a parameter/value pair"
  [parameter context config]
  {:pre  [(coll? parameter)]
   :post [(coll? %)]}
  (let [[key val] parameter
        newcontext (assoc context "parameter-value" val)]
    (case key
      ;; Metaparameters are special
      ("stage" "tag")
      [key (anonymize-leaf val :title newcontext config)]
      "alias"
      [key (anonymize-aliases val newcontext config)]

      ;; References get randomized in a special way
      ("require" "before" "notify" "subscribe")
      [key (anonymize-references val newcontext config)]

      ;; Everything else gets anonymized as per normal
      [(anonymize-leaf key :parameter-name newcontext config)
       (anonymize-leaf val :parameter-value newcontext config)])))

(defn anonymize-parameters
  "Anonymize the parameters keys and values for a resource"
  [parameters context config]
  {:pre  [(map? parameters)]
   :post [(map? %)]}
  (into {} (map #(anonymize-parameter % context config) parameters)))

(defn anonymize-catalog-inputs-input
  "Anonymize a catalog-inputs input [type value] pair"
  [input context config]
  {:pre  [(coll? input)]
   :post [(coll? %)]}
  (let [[type val] input]
    [(anonymize-leaf type :catalog-input-type context config)
     (anonymize-leaf val :catalog-input-value context config)]))

(defn anonymize-catalog-inputs-inputs
  "Anonymize the catalog-inputs inputs type/value pairs"
  [inputs context config]
  {:pre  [(coll? inputs)]
   :post [(coll? %)]}
  (into [] (map #(anonymize-catalog-inputs-input % context config) inputs)))

(defn capitalize-resource-type
  "Converts a downcase resource type to an upcase version such as Foo::Bar"
  [type]
  {:pre  [(string? type)]
   :post [(string? %)]}
  (str/join "::" (map str/capitalize (str/split type #"::"))))

(defn anonymize-lowercase-type
  "Anonymize a tag"
  [tag context config]
  {:pre  [(string? tag)]
   :post [(string? %)]}
  (let [newtag (capitalize-resource-type tag)
        newcontext {"node" (get context "node")
                    "type" newtag}]
    (str/lower-case (anonymize-leaf newtag :type newcontext config))))

(defn anonymize-tags
  "Anonymize a collection of tags"
  [tags context config]
  {:pre  [(coll? tags)]
   :post [(coll? %)]}
  (map #(anonymize-lowercase-type % context config) tags))

(defn anonymize-edge
  "Anonymize an edge reference from a catalog"
  [edge context config]
  {:pre  [(edge? edge)]
   :post [(edge? %)]}
  (let [sourcecontext {"node"  (get context "node")
                       "type"  (get-in edge ["source" "type"])
                       "title" (get-in edge ["source" "title"])}
        targetcontext {"node"  (get context "node")
                       "type"  (get-in edge ["target" "type"])
                       "title" (get-in edge ["target" "title"])}]
    (-> edge
        (update-in ["source" "title"] anonymize-leaf :title sourcecontext config)
        (update-in ["source" "type"]  anonymize-leaf :type sourcecontext config)
        (update-in ["target" "title"] anonymize-leaf :title targetcontext config)
        (update-in ["target" "type"]  anonymize-leaf :type targetcontext config))))

(defn anonymize-edges
  "Anonymize a collection of edge references from a catalog"
  [edges context config]
  {:pre  [(coll? edges)]
   :post [(coll? %)]}
  (map #(anonymize-edge % context config) edges))

(defn anonymize-containment-path-element
  "Anonymize a containment path resource reference"
  [element context config]
  {:pre  [(string? element)]
   :post [(string? %)]}
  (cond
   (.isEmpty element) ""
   (string-contains? "[" element) (anonymize-reference element context config)
   :else (anonymize-leaf element :title (assoc context "type" "Class") config)))

(defn anonymize-containment-path
  "Anonymize a collection of containment path resource references from an event"
  [path context config]
  (some->> path
           (map #(anonymize-containment-path-element % context config))))

(defn anonymize-log-source
  "assumes that capital words are types, bracketed phrases are parameter names,
   and lower-cased words are titles. The last assumption is not valid but is
   intentionally conservative so that full anonymization doesn't miss edge cases."
  [source context config]
  (let [type-pattern #"[A-Z]\w+"
        param-name-pattern #"\[.*?\]"
        title-pattern #"[a-z]\w+"]
    (-> source
        (str/replace type-pattern #(anonymize-leaf % :type context config))
        (str/replace param-name-pattern #(anonymize-leaf % :parameter-name context config))
        (str/replace title-pattern #(anonymize-leaf % :title context config)))))

(defn anonymize-catalog-resource
  [resource context config]
  {:pre  [(resource? resource)]
   :post [(resource? %)]}
  (let [newcontext {"node" (get context "node")
                    "title" (get resource "title")
                    "tags" (get resource "tags")
                    "file" (get resource "file")
                    "line" (get resource "line")
                    "type" (get resource "type")}]
    (-> resource
        (utils/update-when ["file"] anonymize-leaf :file newcontext config)
        (utils/update-when ["line"] anonymize-leaf :line newcontext config)
        (utils/update-when ["code_id"] anonymize-leaf :code_id newcontext config)
        (utils/update-when ["job_id"] anonymize-leaf :job_id newcontext config)
        (update "parameters" anonymize-parameters newcontext config)
        (update "tags" anonymize-tags newcontext config)
        (update "title" anonymize-leaf :title newcontext config)
        (update "type" anonymize-leaf :type newcontext config))))

(defn anonymize-catalog-resources
  [resources context config]
  {:pre  [(coll? resources)]
   :post [(coll? %)]}
  (map #(anonymize-catalog-resource % context config) resources))

(defn anonymize-event
  [{:strs [message property old_value new_value] :as event}
   context config]
  (let [newcontext (assoc context
                          "message" message
                          "property_name" property)]
    (-> event
        (update "message" anonymize-leaf :message newcontext config)
        (update "property" anonymize-leaf :parameter-name newcontext config)
        (update "new_value" anonymize-leaf :parameter-value (assoc newcontext :parameter-value new_value) config)
        (update "old_value" anonymize-leaf :parameter-value (assoc newcontext :parameter-value old_value) config))))

(defn anonymize-events [events context config]
  (map #(anonymize-event % context config) events))

(defn anonymize-report-resource
  [resource context config]
  (let [newcontext {"node" (get context "node")
                    "title" (get resource "resource_title")
                    "type" (get resource "resource_type")
                    "file" (get resource "file")
                    "line" (get resource "line")}]
    (-> resource
        (update "resource_title" anonymize-leaf :title newcontext config)
        (update "resource_type" anonymize-leaf :type newcontext config)
        (utils/update-when ["file"] anonymize-leaf :file newcontext config)
        (utils/update-when ["line"] anonymize-leaf :line newcontext config)
        (utils/update-when ["containment_path"] anonymize-containment-path newcontext config)
        (update "events" anonymize-events newcontext config))))

(pls/defn-validated anonymize-report-resources :- [resource-schema-str]
  [resources :- [resource-schema-str]
   context config]
  (map #(anonymize-report-resource % context config) resources))

(defn anonymize-metric [metric context config]
  (if (= "time" (get metric "category"))
    (update metric "name" #(anonymize-lowercase-type % context config))
    metric))

(pls/defn-validated anonymize-metrics :- [metric-schema-str]
  [metrics :- [metric-schema-str]
   context
   config]
  (map #(anonymize-metric % context config) metrics))

(defn anonymize-log [log context config]
  (-> log
      (update "message" anonymize-leaf :text context config)
      (update "source" anonymize-log-source context config)
      (update "tags" anonymize-tags context config)
      (update "file" anonymize-leaf :file context config)
      (update "line" anonymize-leaf :line context config)))

(pls/defn-validated anonymize-logs :- [log-schema-str]
  [logs :- [log-schema-str]
   context
   config]
  (map #(anonymize-log % context config) logs))

;; Primary entry points, for anonymizing catalogs and reports

(defn anonymize-catalog
  "Returns an anonymized catalog from an existing catalog"
  [config catalog]
  {:pre  [(catalog? catalog)]
   :post [(catalog? %)]}
  (let [context {"node" (get catalog "certname")}]
    (-> catalog
        (update "resources" anonymize-catalog-resources context config)
        (update "edges" anonymize-edges context config)
        (update "certname" anonymize-leaf :node context config)
        (update "producer" anonymize-leaf :node context config)
        (update "transaction_uuid" anonymize-leaf :transaction_uuid context config)
        (update "environment" anonymize-leaf :environment context config))))

(pls/defn-validated anonymize-report :- report-schema-str
  "Anonymize a report"
  [config report :- report-schema-str]
  (let [context {"node" (get report "certname")}]
    (-> report
        (update "certname" anonymize-leaf :node context config)
        (update "producer" anonymize-leaf :node context config)
        (update "resources" anonymize-report-resources context config)
        (update "metrics" anonymize-metrics context config)
        (update "logs" anonymize-logs context config)
        (update "transaction_uuid" anonymize-leaf :transaction_uuid context config)
        (update "environment" anonymize-leaf :environment context config))))

(defn anonymize-fact-values
  "Anonymizes fact names and values"
  [facts context config]
  (reduce-kv
    (fn [acc k v]
      (assoc acc
             (anonymize-leaf k :fact-name (assoc context "fact-name" k) config)
             (if (map? v)
               (anonymize-fact-values v context config)
               (anonymize-leaf v :fact-value (assoc context
                                                    "fact-name" k
                                                    "fact-value" v) config))))
    {} facts))

(defn anonymize-facts
  "Anonymize a fact set"
  [config wire-facts]
  (let [context {"node" (get wire-facts "certname")}]
    (-> wire-facts
        (update "certname" anonymize-leaf :node context config)
        (update "producer" anonymize-leaf :node context config)
        (update "values" anonymize-fact-values context config)
        (update "environment" anonymize-leaf :environment context config))))

(defn anonymize-configure-expiration
  "Anonymize a set of expiration configurations"
  [config wire-node]
  (let [context {"node" (get wire-node "certname")}]
    (-> wire-node
        (update "certname" anonymize-leaf :node context config))))

(defn anonymize-catalog-inputs
  "Anonymize a set of catalog inputs"
  [config wire-catalog-inputs]
  (let [context {"node" (get wire-catalog-inputs "certname")}]
    (-> wire-catalog-inputs
        (update "certname" anonymize-leaf :node context config)
        (update "inputs" anonymize-catalog-inputs-inputs context config))))

(def anon-profiles
  ^{:doc "Hard coded rule engine profiles indexed by profile name"}
  {
   "full" {
           ;; Full anonymization means anonymize everything
           "rules" {}
           }
   "moderate" {
               "rules" {
                        "type" [
                                ;; Leave the core type names alone
                                {"context" {"type" [
                                                    "Augeas" "Computer" "Cron" "Exec" "File" "Filebucket" "Group" "Host"
                                                    "Interface" "K5login" "Macauthorization" "Mailalias" "Mcx" "Mount"
                                                    "Notify" "Package" "Resources" "Router" "Schedule" "Schedule_task"
                                                    "Selboolean" "Selmodule" "Service" "Ssh_authorized_key" "Sshkey" "Stage"
                                                    "Tidy" "User" "Vlan" "Yumrepo" "Zfs" "Zone" "Zpool"]}
                                 "anonymize" false}
                                {"context" {"type" "/^Nagios_/"} "anonymize" false}
                                ;; Class
                                {"context" {"type" "Class"} "anonymize" false}
                                ;; Stdlib resources
                                {"context" {"type" ["Anchor" "File_line"]} "anonymize" false}
                                ;; PE resources, based on prefix
                                {"context" {"type" "/^Pe_/"} "anonymize" false}
                                ;; Some common type names from PL modules
                                {"context" {"type" [
                                                    "Firewall" "A2mod" "Vcsrepo" "Filesystem" "Logical_volume"
                                                    "Physical_volume" "Volume_group" "Java_ks"]}
                                 "anonymize" false}
                                {"context" {"type" [
                                                    "/^Mysql/" "/^Postgresql/" "/^Rabbitmq/" "/^Puppetdb/" "/^Apache/"
                                                    "/^Mrepo/" "/^F5/" "/^Apt/" "/^Registry/" "/^Concat/"]}
                                 "anonymize" false}
                                ]
                        "title" [
                                 ;; Leave the titles alone for some core types
                                 {"context"   {"type" ["Filebucket" "Package" "Stage" "Service"]}
                                  "anonymize" false}
                                 ]
                        "parameter-name" [
                                          ;; Parameter names don't need anonymization
                                          {"context" {} "anonymize" false}
                                          ]
                        "parameter-value" [
                                           ;; Leave some metaparameters alone
                                           {"context" {"parameter-name" ["provider" "ensure" "noop" "loglevel" "audit" "schedule"]}
                                            "anonymize" false}
                                           ;; Always anonymize values for parameter names with 'password' in them
                                           {"context" {"parameter-name" [
                                                                         "/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
                                            "anonymize" true}
                                           ]
                        "catalog-input-type" [
                                              ;; leave catalog-input types alone
                                              {"context" {} "anonymize" false}
                                              ]
                        "line" [
                                ;; Line numbers without file names does not give away a lot
                                {"context" {} "anonymize" false}
                                ]
                        "transaction_uuid" [
                                            {"context" {} "anonymize" false}
                                            ]

                        "fact-name"
                        [{"context" {"fact-name" ["architecture" "/^augeasversion.*/" "/^bios_.*/" "/^blockdevice.*/" "/^board.*/" "domain"
                                                  "facterversion" "fqdn" "hardwareisa" "hardwaremodel" "hostname" "id" "interfaces"
                                                  "/^ipaddress.*/" "/^iptables.*/" "/^ip6tables.*/" "is_pe" "is_virtual" "/^kernel.*/" "/^lsb.*/"
                                                  "/^macaddress.*/" "/^macosx.*/" "/^memoryfree.*/" "/^memorysize.*/" "memorytotal" "/^mtu_.*/"
                                                  "/^netmask.*/" "/^network.*/" "/^operatingsystem.*/" "osfamily" "path" "/^postgres_.*/"
                                                  "/^processor.*/" "/^physicalprocessorcount.*/" "productname" "ps" "puppetversion"
                                                  "rubysitedir" "rubyversion" "/^selinux.*/" "/^sp_.*/" "/^ssh.*/" "swapencrypted"
                                                  "/^swapfree.*/" "/^swapsize.*/" "timezone" "/^uptime.*/" "uuid" "virtual"]}
                          "anonymize" false}
                         {"context" {} "anonymize" true}]

                        "fact-value"
                        [{"context" {"fact-name" ["/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
                          "anonymize" true}
                         {"context" {"fact-name" ["architecture" "/^augeasversion.*/" "/^bios_.*/" "/^blockdevice.*/" "/^board.*/" "facterversion"
                                                  "hardwareisa" "hardwaremodel" "id" "interfaces" "/^iptables.*/" "/^ip6tables.*/" "is_pe"
                                                  "is_virtual" "/^kernel.*/" "/^lsb.*/" "/^macosx.*/" "/^memory.*/" "/^mtu_.*/" "/^netmask.*/"
                                                  "/^operatingsystem.*/" "osfamily" "/^postgres_.*/" "/^processor.*/" "/^physicalprocessorcount.*/"
                                                  "productname" "ps" "puppetversion" "rubysitedir" "rubyversion" "/^selinux.*/"
                                                  "swapencrypted" "/^swapfree.*/" "/^swapsize.*/" "timezone" "/^uptime.*/" "virtual"]}
                          "anonymize" false}
                         {"context" {} "anonymize" true}]

                        "environment"
                        [{"context" {} "anonymize" true}]
                        }
               }

   "low" {
          "rules" {
                   "node" [
                           ;; Users presumably want to hide node names more often then not
                           {"context" {} "anonymize" true}
                           ]
                   "type" [
                           {"context" {} "anonymize" false}
                           ]
                   "title" [
                            {"context" {} "anonymize" false}
                            ]
                   "parameter-name" [
                                     {"context" {} "anonymize" false}
                                     ]
                   "catalog-input-type" [
                                         {"context" {} "anonymize" false}
                                         ]
                   "line" [
                           {"context" {} "anonymize" false}
                           ]
                   "file" [
                           {"context" {} "anonymize" false}
                           ]
                   "message" [
                              ;; Since messages themselves may contain values, we should anonymize
                              ;; any message for 'secret' parameter names
                              {"context" {"parameter-name" [
                                                            "/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
                               "anonymize" true}
                              {"context" {} "anonymize" false}
                              ]
                   "parameter-value" [
                                      ;; Always anonymize values for parameter names with 'password' in them
                                      {"context" {"parameter-name" [
                                                                    "/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
                                       "anonymize" true}
                                      ]
                   "transaction_uuid" [
                                       {"context" {} "anonymize" false}
                                       ]

                   "fact-name"
                   [{"context" {} "anonymize" false}]

                   "fact-value" [
                                 {"context" {"fact-name" ["/password/" "/pwd/" "/secret/" "/key/" "/private/"]}
                                  "anonymize" true}
                                 {"context" {} "anonymize" false}]

                   "environment" [{"context" {} "anonymize" false}]
                   }
          }
   "none" {
           "rules" {
                    "node" [ {"context" {} "anonymize" false} ]
                    "text" [ {"context" {} "anonymize" false} ]
                    "type" [ {"context" {} "anonymize" false} ]
                    "title" [ {"context" {} "anonymize" false} ]
                    "parameter-name" [ {"context" {} "anonymize" false} ]
                    "catalog-input-type" [ {"context" {} "anonymize" false} ]
                    "line" [ {"context" {} "anonymize" false} ]
                    "file" [ {"context" {} "anonymize" false} ]
                    "message" [ {"context" {} "anonymize" false} ]
                    "parameter-value" [ {"context" {} "anonymize" false} ]
                    "catalog-input-value" [ {"context" {} "anonymize" false} ]
                    "transaction_uuid" [ {"context" {} "anonymize" false} ]
                    "fact-name" [{"context" {} "anonymize" false}]
                    "fact-value" [{"context" {} "anonymize" false}]
                    "environment" [{"context" {} "anonymize" false}]
                    }
           }
   })

(def anon-profiles-str (str/join ", " (keys anon-profiles)))
