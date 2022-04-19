(ns puppetlabs.puppetdb.query-eng.parse
  "AST parsing"
  (:require
   [clojure.string :as str])
  (:import
   (java.util.regex Matcher)))

(def ^:private warn-on-reflection-orig *warn-on-reflection*)
(set! *warn-on-reflection* true)

;; Q: could even be stricter?
(def top-field-rx
  "Syntax of the first component of a field path, e.g. \"facts\" for
  facts.os."
  #"^[a-zA-Z][_0-9a-zA-Z]*\??(?=\.|$)")

(def field-rx
  "Syntax of an unquoted field component, e.g. the second component of
  foo.bar.baz.  Includes all characters up to the next dot, or the end
  of the field.  Taken in conjunction with the current quoted field
  syntax, there is no way to represent a field component that contains
  a dot and ends in a backslash, e.g. a fact named \"foo.bar\\\" since
  given the dot, it has to be quoted, but quoted fields can't end in a
  backslash right now (cf. quoted-field-rx)."
  #"^[^.]+(?=\.|$)")

(def quoted-field-rx
  "Syntax of a quoted field component, e.g. the second component of
  foo.\"bar\".baz.  It must begin with a double quote, and ends at the
  next double quote that is not preceded by a backslash and is
  followed by either a dot, or the end of the field.  There is
  currently no way to represent a field component that contains a dot
  and ends in a backslash, e.g. a fact named \"foo.bar\\\".  It has to
  be quoted, given the dot, but as just mentioned, quoted fields can't
  end in a backslash."
  #"(?s:^\"(.*?[^\\])\"(?=\.|$))")

(def match-rx
  "Syntax of a match(regex) field component, e.g. the second component
  of foo.match(\"bar?\").baz.  It must begin with match, open paren,
  double quote, and ends at the next double-quote, close-paren that is
  not preceded by a backslash and is followed by either a dot, or the
  end of the field.  The regex then, has essentially the same syntax
  as a double quoted field.  And similarly, there is currently not way
  to specify a match regular expression that ends in a backslash."
  #"(?s:^match\(\"(.*[^\\])\"\)(?=\.|$))")

(defn- find-at
  "Sets the start of m's region to i and then returns the result of a
  find.  This allows ^ to match at i."
  [^Matcher m i]
  (.region m i (.regionEnd m))
  (.find m))

(defn- index-or-name
  "Returns an ::indexed-field-part segment if s is of the form name[digits],
  otherwise a ::named-field-part segment."
  [s _indexes?]
  (if-let [[_ n i] (re-matches #"(?s:(.+)\[(\d+)\])" s)]
    ;; Must be Integer, not Long to avoid pg errors like this:
    ;; "ERROR: operator does not exist: jsonb -> bigint"
    {:kind ::indexed-field-part :name n :index (Integer/valueOf ^String i)}
    {:kind ::named-field-part :name s}))

(defn- parse-field-components
  "Parses the components of a dotted query field that come after the
  first, and conjoins a map describing each one onto the result."
  [^String s offset
   {:keys [indexes? matches?]
    :or {indexes? true matches? true}}
   result]
  (let [field-m (re-matcher field-rx s)
        match-m (re-matcher match-rx s)
        qfield-m (re-matcher quoted-field-rx s)]
    (loop [i offset
           result result]
      (if (= i (count s))
        result
        (do
          ;; Q: can this case ever match now?
          (when-not (= \. (nth s i))
            (throw
             (ex-info (format "AST field component at character %d does not begin with a dot: %s"
                              i (pr-str s))
                      {:kind ::invalid-field-component
                       :field s
                       :offset i})))
          (let [i (inc i)]
            ;; Assumes that this ordering produces no aliasing, which is
            ;; true right now because all the patterns should be mutually
            ;; exclusive.
            (cond
              (.startsWith s "\"\"" i)
              (throw
               (ex-info (format "Empty AST field component at character %d: %s"
                                i (pr-str s))
                        {:kind ::invalid-field-component
                         :field s
                         :offset i}))
              (and matches? (find-at match-m i))
              (recur (.end match-m)
                     (conj result {:kind ::match-field-part
                                   :pattern (.group match-m 1)}))
              ;; We could handle indexing as an option in the field
              ;; regexes, but we don't for now, so that the code's
              ;; hopefully a bit easier to follow.
              (find-at qfield-m i)
              (recur (.end qfield-m)
                     (conj result (index-or-name (.group qfield-m 1)
                                                 indexes?)))
              (find-at field-m i)
              (recur (.end field-m)
                     (conj result (index-or-name (.group field-m)
                                                 indexes?)))
              ;; Probably currently unreachable
              :else (throw
                     (ex-info (format "Don't recognize AST field component at character %d: %s"
                                      i (pr-str s))
                              {:kind ::invalid-field-component
                               :field s
                               :offset i})))))))))

(defn parse-field
  "Parses an AST field like \"certname\", \"facts.partition[3]\" and
  returns a vector of the field components as maps.  The first
  component will always be a ::named-field-part."
  ([s] (parse-field s 0 {}))
  ([s offset opts]
   (assert (string? s))
   (when (= offset (count s))
     (throw (ex-info "Empty AST field" {:kind ::invalid-field :field s})))
   (let [field-m (re-matcher top-field-rx s)]
     (when-not (find-at field-m offset)
       (throw
        (ex-info (str "First component of AST field is invalid: " (pr-str s))
                 {:kind ::invalid-field-component
                  :field s
                  :offset 0})))
     ;; Q: OK to disallow an initial quoted-field?
     (let [result [{:kind ::named-field-part :name (.group field-m)}]]
       (parse-field-components s (.end field-m) opts result)))))

(defn- quote-path-name-for-field-str [s]
  (when (re-matches #"(?s:.*\..*\\)" s)
    ;; Currently no way to represent a string including a dot that
    ;; ends in a backslash.
    (throw (ex-info (str "AST has no way to quote a path segment including a dot and ending in backslash: "
                         (pr-str s))
                    {:kind ::unquotable-field-segment
                     :name s})))
  (if (str/index-of s \.)
    (str \" s \")
    s))

(defn path-names->field-str
  "Returns a properly quoted AST field string (dotted path) for the
  given names (only handles names, not ::indexed-field-part
  or ::match-field-part expressions).  Throws an exception if any name
  cannot be quoted, since AST's current quoting syntax is
  incomplete (e.g. cannot represent a name that contains a dot and
  ends in backslash."
  [names]
  (str/join \. (map quote-path-name-for-field-str names)))

(set! *warn-on-reflection* warn-on-reflection-orig)
