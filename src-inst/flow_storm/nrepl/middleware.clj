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

(defn find-first-fn-call [{:keys [fq-fn-symb]}]
  {:code `{:status :done
           :fn-call (debuggers-api/find-first-fn-call (symbol ~fq-fn-symb))}
   :post-proc (fn [result]
                (update result :fn-call value-ref->int :fn-args))})

(defn get-form [{:keys [form-id]}]
  {:code `{:status :done
           :form (debuggers-api/get-form nil nil ~form-id)}
   :post-proc (fn [result]
                (update-in result [:form :form/file]
                           (fn [file-name]
                             (when-let [file (if (str/starts-with? file-name "/")
                                               (io/file file-name)
                                               (io/resource file-name))]
                               (.getPath file)))))})

(defn timeline-entry [{:keys [flow-id thread-id idx drift]}]
  {:code `{:status :done
           :entry (debuggers-api/timeline-entry ~(if (number? flow-id) flow-id nil)
                                                ~thread-id
                                                ~idx
                                                ~(keyword drift))}
   :post-proc (fn [result]
                (-> result
                    (update :entry value-ref->int :fn-args)
                    (update :entry value-ref->int :result)))})

(defn frame-data [{:keys [flow-id thread-id fn-call-idx]}]
  {:code `{:status :done
           :frame (-> (debuggers-api/frame-data ~(if (number? flow-id) flow-id nil)
                                                ~thread-id
                                                ~fn-call-idx
                                                {}))}
   :post-proc (fn [result]
                (-> result
                    (update :frame value-ref->int :args-vec)
                    (update :frame value-ref->int :ret)))})

(defn pprint-val-ref [{:keys [val-ref print-level print-length print-meta pprint]}]
  {:code `{:status :done
           :pprint (debuggers-api/val-pprint (make-value-ref ~val-ref)
                                             {:print-length ~print-length
                                              :print-level  ~print-level
                                              :print-meta?  ~(Boolean/parseBoolean print-meta)
                                              :pprint?      ~(Boolean/parseBoolean pprint)})}
   :post-proc identity})

(defn dbg [& args]
  (let [msg (str (pr-str args) "\n")]
    (spit "./XXXXX.tmp" msg :append true)))

(defn cljs-transport
  [{:keys [^Transport transport] :as msg} result-proc-fn]
  (reify Transport
    (recv [_this]
      (.recv transport))

    (recv [_this timeout]
      (.recv transport timeout))

    (send [this response]
      (dbg "@@@ CLJS got response" response)
      (cond (contains? response :value)
            (let [_ (dbg "@@@ CLJS (:value response) " (type (:value response)) " " (:value response))
                  rsp-val (if (string? (:value response))
                            (read-string (:value response))
                            (:value response))
                  _ (dbg "@@@@ after reading string" rsp-val)
                  processed-val (result-proc-fn rsp-val)
                  _ (dbg "@@@@ after processed-val" processed-val)
                  rsp (response-for msg processed-val)]
              (dbg "@@@@ sending response" rsp)
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

(defn process-msg [next-handler {:keys [^Transport transport] :as msg} msg-proc-fn cljs?]
  (let [{:keys [code post-proc]} (msg-proc-fn msg)]

    (if cljs?
      ;; ClojureScript handling
      (let [tr (cljs-transport msg post-proc)
            cljs-code (pr-str code)]
        (dbg "@@@@ CLJS sending code to piggieback " cljs-code)
        (next-handler (assoc msg
                             :transport tr
                             :op "eval"
                             :code cljs-code
                             :ns "cljs.user")))

      ;; Clojure handling
      (let [rsp (response-for msg (post-proc (eval code)))]
        (t/send transport rsp)))))

(defn wrap-flow-storm
  "Middleware that provides flow-storm functionality "
  [next-handler]
  (fn [{:keys [op] :as msg}]
    (let [piggieback? (or cljs-utils/cider-piggieback? cljs-utils/nrepl-piggieback?)]
      (case op
        "xxx" (process-msg next-handler ;; @@@@@@@@@@
                           msg
                           (fn [_] {:code `{:status :done :r (str (type 42))}
                                    :post-proc identity})
                           piggieback?)
        "flow-storm-find-first-fn-call" (process-msg next-handler msg find-first-fn-call piggieback?)
        "flow-storm-get-form"           (process-msg next-handler msg get-form           piggieback?)
        "flow-storm-timeline-entry"     (process-msg next-handler msg timeline-entry     piggieback?)
        "flow-storm-frame-data"         (process-msg next-handler msg frame-data         piggieback?)
        "flow-storm-pprint"             (process-msg next-handler msg pprint-val-ref     piggieback?)
        (next-handler msg)))))

(def descriptor
  (cljs-utils/expects-piggieback
   {:requires #{}
    :expects #{}
    :handles {
              "xxx" ;; @@@@@@@@@@
              {:doc "xxx"
               :requires {}
               :optional {}
               :returns {"r" ""}}

              "flow-storm-find-first-fn-call"
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

(set-descriptor! #'wrap-flow-storm descriptor)
