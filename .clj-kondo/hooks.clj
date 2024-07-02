(ns hooks
  (:require
   [clj-kondo.hooks-api :as api
    :refer [list-node
            sexpr
            token-node
            token-node?
            vector-node
            vector-node?]]))

(defn check-json-response
  [{{[_ response-body response response-exp & body] :children} :node}]
  ;; Rewrite as:
  ;;   (let [response response-exp
  ;;         response-body nil]
  ;;     ...body...)
  ;;
  {:node (list-node
          (list* (token-node 'let)
                 (vector-node [response response-exp
                               response-body nil])
                 body))})

(defn deftest-http-app
  [{{[_ name bindings & body] :children} :node}]
  ;; Rewrite as: (defn name [] (doseq bindings ...body...))
  {:node (list-node
          (list (token-node 'defn)
                name
                (vector-node [])
                (list-node (list* (token-node 'doseq)
                                  bindings
                                  body))))})

(defn rewrite-service
  [name doc proto dependencies & methods]
  ;; Rewrite as:
  ;;   (do
  ;;     (declare DummyProtocol)
  ;;     (defrecord name
  ;;         [dependency-service-method ...]
  ;;       DummyProtocol
  ;;       ...methods...))
  {:node (list-node
          (list (token-node 'do)
                (list-node
                 (list (token-node 'declare) (token-node 'DummyProtocol)))
                (list-node
                 (list* (token-node 'defrecord)
                        name
                        (vector-node (->> (sexpr dependencies)
                                          flatten
                                          (remove keyword?)
                                          (map token-node)
                                          vec))
                        (token-node 'DummyProtocol)
                        methods))))})

(defn arrange-service-args
  [args]
  (cond
    (vector-node? (nth args 0))
    (list* nil nil args)

    (vector-node? (nth args 1))
    (if (token-node? (nth args 0))
      (list* nil (nth args 0) (rest args))
      (list* (nth args 0) nil (rest args)))

    :else args))

(defn defservice
  [{{[_ name & others] :children} :node}]
  (apply rewrite-service name (arrange-service-args others)))

(defn service
  [{{[_ & body] :children} :node}]
  (apply rewrite-service (token-node (gensym)) (arrange-service-args body)))

(defn rewrite-defspec
  [name num-tests-or-options? property]
  ;; Rewrite as: (def name property)
  {:node (list-node (list (token-node 'def) name property))})

(defn defspec
  [{{[_ name & others] :children} :node}]
  (case (count others)
    1 (apply rewrite-defspec name nil others)
    2 (apply rewrite-defspec name others)
    (throw (ex-info "Unrecognized defspec form" {}))))

(defn deftest-antonyms
  [{{[_ name & body] :children} :node}]
  ;; Rewrite as: (defn name [] ...body...)
  {:node (list-node
          (list* (token-node 'defn)
                 name
                 (vector-node [])
                 body))})

(defn one-binding-then-body
  [{{[_ binding & body] :children} :node}]
  ;; Rewrite as: (let [binding nil] ...body...)
  {:node (list-node
          (list* (token-node 'let)
                 (vector-node [binding nil])
                 body))})

(defn with-coordinated-fn
  [{{[_ proceed waiter & body] :children} :node}]
  ;; Just ignore the waiter.  Rewrite as:
  ;;   (let [proceed identity] & body)
  {:node (list-node
          (list* (token-node 'let)
                 (vector-node [proceed (token-node 'identity)])
                 body))})


(defn with-final
  [{{[_ bindings & body] :children} :node}]
  ;; Strip the :error and :always args and rewrite as let.
  (let [basic-bindings (loop [bindings (:children bindings)
                              result []]
                         (case (count bindings)
                           0 result
                           2 (apply conj result bindings)
                           (1 3) (throw (ex-info "Unexpected end of with-final bindings" {}))
                           (let [[name init maybe-kind & others] bindings]
                             (if-not (#{:always :error} maybe-kind)
                               (recur (cons maybe-kind others)
                                      (conj result
                                            name
                                            init))
                               (let [[action & others] others]
                                 (recur others
                                        (conj result
                                              name
                                              init
                                              (token-node '_)
                                              action)))))))]
    {:node (list-node
            (list* (token-node 'let)
                   (vector-node basic-bindings)
                   body))}))

(defn with-test-webserver
  [{{[_ app port & body] :children} :node}]
  ;; Rewrite as:
  ;;   (let [port nil]
  ;;     app
  ;;     ...body...)
  {:node (list-node
          (list* (token-node 'let)
                 (vector-node [port nil])
                 app
                 body))})
