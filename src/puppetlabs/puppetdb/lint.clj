(ns puppetlabs.puppetdb.lint)

(defmacro ignore-value
  "Suppress eastwood's unused-ret-vals warning:
  https://github.com/jonase/eastwood#unused-ret-vals.  See
  ./eastwood.clj for the suppression that makes this work."
  [x]
  ;; Use the dummy let to avoid https://github.com/jonase/eastwood/issues/355
  `(let [x# ~x]
     nil))
