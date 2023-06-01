(ns flow-storm.nrepl.middleware
  (:require [flow-storm.runtime.debuggers-api :as debuggers-api]
            [flow-storm.types :refer [make-value-ref]]
            [nrepl.misc :refer [response-for] :as misc]
            [nrepl.middleware :as middleware :refer [set-descriptor!]]
            [cider.nrepl.middleware.util.error-handling :refer [base-error-response]]
            [nrepl.middleware.caught :as caught]
            [nrepl.transport :as t]
            [nrepl.bencode]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cider.nrepl.middleware.util.cljs :as cljs-utils])
  (:import [nrepl.transport Transport]
           [flow_storm.types ValueRef]))

(defmethod nrepl.bencode/write-bencode ValueRef
  [output vr]
  (nrepl.bencode/write-bencode output (:vid vr)))

(defn value-ref->int [m k]
  (if (contains? m k)
    (update m k :vid)
    m))

(defn find-first-fn-call-code [{:keys [fq-fn-symb] :as msg}]
  `{:status :done
    :fn-call (debuggers-api/find-first-fn-call (symbol ~fq-fn-symb))})

(defn find-first-fn-call [{:keys [fq-fn-symb] :as msg}]
  (response-for msg {:status :done
                     :fn-call (-> (debuggers-api/find-first-fn-call (symbol fq-fn-symb))
                                  (value-ref->int :fn-args))}))

(defn get-form [{:keys [form-id] :as msg}]
  (let [form (debuggers-api/get-form nil nil form-id)
        form (update form :form/file (fn [file-name]
                                       (when-let [file (if (str/starts-with? file-name "/")
                                                         (io/file file-name)
                                                         (io/resource file-name))]
                                         (.getPath file))))]
    (when-not (:form/file form)
      (println "Can't get the file path for form %s" form))

    (response-for msg {:status :done
                       :form form})))

(defn timeline-entry [{:keys [flow-id thread-id idx drift] :as msg}]
  (response-for msg {:status :done
                     :entry (-> (debuggers-api/timeline-entry (if (number? flow-id) flow-id nil)
                                                              thread-id
                                                              idx
                                                              (keyword drift))
                                (value-ref->int :fn-args)
                                (value-ref->int :result))}))

(defn frame-data [{:keys [flow-id thread-id fn-call-idx] :as msg}]
  (response-for msg {:status :done
                     :frame (-> (debuggers-api/frame-data (if (number? flow-id) flow-id nil)
                                                          thread-id
                                                          fn-call-idx
                                                          {})
                                (value-ref->int :args-vec)
                                (value-ref->int :ret))}))

(defn pprint-val-ref [{:keys [val-ref print-level print-length print-meta pprint] :as msg}]
  (response-for msg {:status :done
                     :pprint (debuggers-api/val-pprint (make-value-ref val-ref)
                                                       {:print-length print-length
                                                        :print-level  print-level
                                                        :print-meta?  (Boolean/parseBoolean print-meta)
                                                        :pprint?      (Boolean/parseBoolean pprint)})}))

(defn cljs-transport
  [{:keys [^Transport transport] :as msg} result-proc-fn]
  (reify Transport
    (recv [_this]
      (.recv transport))

    (recv [_this timeout]
      (.recv transport timeout))

    (send [this response]
      (cond (contains? response :value)
            (let [rsp-val (read-string (:value response))
                  processed-val (result-proc-fn rsp-val)
                  rsp (response-for msg processed-val)]
              (.send transport rsp))

            ;; If the eval errored, propagate the exception as error in the
            ;; inspector middleware, so that the client CIDER code properly
            ;; renders it instead of silently ignoring it.
            (and (contains? (:status response) :eval-error)
                 (contains? response ::caught/throwable))
            (let [e (::caught/throwable response)
                  resp (base-error-response msg e :inspect-eval-error :done)]
              (.send transport resp))

            :else (.send transport response))
      this)))

(defn process-msg [next-handler {:keys [^Transport transport] :as msg} msg-code-gen-fn result-proc-fn cljs?]
  (let [rt-code (msg-code-gen-fn msg)]

    (if cljs?
      ;; ClojureScript handling
      (let [tr (cljs-transport msg result-proc-fn)
            cljs-code (pr-str rt-code)]
        (next-handler (assoc msg
                             :transport tr
                             :op "eval"
                             :code cljs-code
                             :ns "cljs.user")))

      ;; Clojure handling
      (let [rsp (response-for msg (result-proc-fn (eval rt-code)))]
        (t/send transport rsp)))))

(defn wrap-flow-storm
  "Middleware that provides flow-storm functionality "
  [next-handler]
  (fn [{:keys [op] :as msg}]
    (let [piggieback? (or cljs-utils/cider-piggieback? cljs-utils/nrepl-piggieback?)]
      (case op
        "flow-storm-find-first-fn-call" (process-msg next-handler
                                                     msg
                                                     find-first-fn-call-code
                                                     (fn [result]
                                                       (update result :fn-call value-ref->int :fn-args))
                                                     piggieback?)
        (next-handler msg)))
    #_(case op
        "flow-storm-find-first-fn-call" (t/send transport (find-first-fn-call msg))
        "flow-storm-get-form"           (t/send transport (get-form msg))
        "flow-storm-timeline-entry"     (t/send transport (timeline-entry msg))
        "flow-storm-frame-data"         (t/send transport (frame-data msg))
        "flow-storm-pprint"             (t/send transport (pprint-val-ref msg))
        (h msg))))

(set-descriptor!
 #'wrap-flow-storm
 (cljs-utils/expects-piggieback
  {:requires #{}
   :expects #{}
   :handles {"flow-storm-find-first-fn-call"
             {:doc "Find the first FnCall for a symbol"
              :requires {"fq-fn-symb" "The Fully qualified function symbol"}
              :optional {}
              :returns {"fn-call" "A map with ..."}}

             "flow-storm-get-form"
             {:doc "Return a registered form"
              :requires {"form-id" "The id of the form"}
              :optional {}
              :returns {"form" "A map with ..."}}

             "flow-storm-timeline-entry"
             {:doc "Return a timeline entry"
              :requires {"flow-id" "The flow-id for the entry"
                         "thread-id" "The thread-id for the entry"
                         "idx" "The current timeline idx"
                         "drift" "The drift, one of next-out next-over prev-over next prev at"}
              :optional {}
              :returns {"entry" "A map with ..."}}

             "flow-storm-frame-data"
             {:doc "Return a frame for a fn-call index"
              :requires {"flow-id" "The flow-id for the entry"
                         "thread-id" "The thread-id for the entry"
                         "fn-call-idx" "The fn-call timeline idx"}
              :optional {}
              :returns {"frame" "A map with ..."}}

             "flow-storm-pprint"
             {:doc "Return a pretty printing for a value reference id"
              :requires {"val-ref" "The value reference id"
                         "print-length" "A *print-length* val for pprint"
                         "print-level" "A *print-level* val for pprint"
                         "print-meta" "A *print-meta* val for pprint"
                         "pprint" "When true will pretty print, otherwise just print"}
              :optional {}
              :returns {"pprint" "A map with :val-str and :val-type"}}}}))
