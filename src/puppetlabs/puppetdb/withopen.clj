(ns puppetlabs.puppetdb.withopen
  "This is a workaround for an illegal reflective access error using (with-open):

   WARNING: Illegal reflective access by clojure.lang.InjectedInvoker/0x00000008401e8040 (file:/home/rroland/.m2/repository/org/clojure/clojure/1.10.1/clojure-1.10.1.jar) to method sun.nio.ch.ChannelInputStream.close()
   WARNING: Please consider reporting this to the maintainers of clojure.lang.InjectedInvoker/0x00000008401e8040
   WARNING: Use --illegal-access=warn to enable warnings of further illegal reflective access operations
   WARNING: All illegal access operations will be denied in a future release

  Upstream bug: https://clojure.atlassian.net/browse/CLJ-2066

  Macro is based off hinting of IDisposable in Clojure-CLR's with-open macro, see:
  https://github.com/clojure/clojure-clr/blob/cc58bcb6f4d949f56eb4dc668850cf04dc6df7d7/Clojure/Clojure.Source/clojure/core.clj#L3831-L3850
  "
  (:refer-clojure :exclude (with-open)))

(defmacro ^{:private true} assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                 (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
       ~(let [more (nnext pairs)]
          (when more
            (list* `assert-args more)))))

(defmacro with-open
  "bindings => [name init ...]
  Evaluates body in a try expression with names bound to the values
  of the inits, and a finally clause that calls (.close name) on each
  name in reverse order."
  {:added "1.0"}
  [bindings & body]
  (assert-args
   (vector? bindings) "a vector for its binding"
   (even? (count bindings)) "an even number of forms in binding vector")
  (cond
    (= (count bindings) 0) `(do ~@body)
    (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                              (try
                                (with-open ~(subvec bindings 2) ~@body)
                                (finally
                                  (. ~(with-meta (bindings 0) {:tag 'java.io.Closeable}) close))))
    :else (throw (IllegalArgumentException.
                  "with-open only allows Symbols in bindings"))))
