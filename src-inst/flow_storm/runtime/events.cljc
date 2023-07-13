(ns flow-storm.runtime.events)

(def callback (atom nil))
(def pending-events (atom []))

(defn clear-subscription! []
  (reset! callback nil))

(defn clear-pending-events! []
  (reset! pending-events []))

(defn subscribe! [callback-fn]
  (reset! callback callback-fn)  
  (locking pending-events
    (doseq [pe @pending-events]
      (callback-fn pe))))

(defn make-flow-created-event [flow-id form-ns form timestamp]
  [:flow-created {:flow-id flow-id
                  :form-ns form-ns
                  :form (pr-str form)
                  :timestamp timestamp}])

(defn make-threads-updated-event [flow-id]
  [:threads-updated {:flow-id flow-id}])

(defn make-var-instrumented-event [{:keys [var-symb file line]}]
  [:var-instrumented {:var-symb var-symb
                      :file file
                      :line line}])

(defn make-var-uninstrumented-event [{:keys [var-symb file line]}]
  [:var-uninstrumented {:var-symb var-symb
                        :file file
                        :line line}])

(defn make-ns-instrumented-event [ns-name]
  [:namespace-instrumented {:ns-name ns-name}])

(defn make-ns-uninstrumented-event [ns-name]
  [:namespace-uninstrumented {:ns-name ns-name}])

(defn make-tap-event [tap-val]
  [:tap {:value tap-val}])

(defn make-task-submitted-event [task-id]
  [:task-submitted {:task-id task-id}])

(defn make-task-result-event [task-id result]
  [:task-result {:task-id task-id :result result}])

(defn make-task-progress-event [task-id progress]
  [:task-progress {:task-id task-id :progress progress}])

(defn make-heap-info-update-event [heap-info]
  [:heap-info-update heap-info])

(defn make-goto-location-event [flow-id thread-id thread-name idx]
  [:goto-location {:flow-id flow-id :thread-id thread-id :thread-name thread-name :idx idx}])

(defn make-break-installed-event [fq-fn-symb]
  [:break-installed {:fq-fn-symb fq-fn-symb}])

(defn make-break-removed-event [fq-fn-symb]
  [:break-removed {:fq-fn-symb fq-fn-symb}])

(defn make-recording-updated-event [recording?]
  [:recording-updated {:recording? recording?}])

(defn show-doc-event [vsymb]
  [:show-doc {:var-symbol vsymb}])

(defn publish-event! [[ev-key :as ev]]
  (if-let [cb @callback]

    (cb ev)

    (when (not= ev-key :heap-info-update)
      (locking pending-events
        (swap! pending-events conj ev)))))
