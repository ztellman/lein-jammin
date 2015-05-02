(ns leiningen.jammin
  (:require
    [leinjacker.utils :as utils]
    [leinjacker.eval :as eval]))

(def load-forms

  '(do
     (in-ns 'leiningen.jammin)
     (clojure.core/use 'clojure.core)

     (set! *warn-on-reflection* false)

     (require
       '[clojure.string :as str]
       '[io.aviso.exception :as exception])

     (import
       '[java.lang.reflect
         Array]
       '[java.lang.management
         ThreadMXBean
         ManagementFactory
         ThreadInfo]
       '[java.lang
         StackTraceElement])

     (def ^ThreadMXBean thread-bean
       (doto
         (ManagementFactory/getThreadMXBean)
         (.setThreadContentionMonitoringEnabled true)
         (.setThreadCpuTimeEnabled true)))

     (defn thread-ids []
       (seq (.getAllThreadIds thread-bean)))

     (defn deadlocked-thread-ids []
       (-> thread-bean
         .findDeadlockedThreads
         seq))

     (defn thread-info [id]
       (let []
         (let [info (.getThreadInfo thread-bean id 10000)]
           {:stack-trace (reverse (.getStackTrace info))
            :user-time (.getThreadUserTime thread-bean id)
            :blocked-time (.getBlockedTime info)
            :waited-time (.getWaitedTime info)
            :name (.getThreadName info)
            })))

     (defn pprint-stack-trace [elements]
       (when-not (empty? elements)
         (->>
           (exception/format-exception
             (doto (Throwable.) (.setStackTrace elements))
             {:filter (constantly :show)})
           str/split-lines
           (map #(str % "\n"))
           butlast
           (apply str))))

     (defn pprint-all-threads []
       (let [stack->infos (->> (thread-ids)
                            (map thread-info)
                            (group-by :stack-trace)
                            (sort-by (fn [[stack infos]]
                                       (->> infos (map :user-time) (apply max))))
                            reverse)]
         (.write *out*
           (with-out-str
             (println "\n == Things seem to be stuck. ==")
             (doseq [[stack infos] stack->infos]
               (print "\n\n")
               (doseq [name (map :name infos)]
                 (println name))

               (print "\n")
               (println (pprint-stack-trace (into-array stack))))))
         (.flush *out*)))

     (defn wait-for-hang [duration]
       (let [id (.getId (Thread/currentThread))]
         (->> (repeatedly
                (fn []
                  (Thread/sleep 100)
                  (let [ids (remove #{id} (thread-ids))]
                    (zipmap ids (->> ids (map thread-info) (map :stack-trace))))))
           (partition (Math/ceil (/ duration 100)))
           (filter #(apply = %))
           first)
         nil))))

(eval load-forms)

(defn jammin
  "If nothing happens for a while, print out the stack traces."
  [project seconds task & args]
  (let [seconds (Integer/parseInt seconds)]
    (eval/hook-eval-in-project
      (fn [eip project form pre-form]
        (eip
          project
          `(do
             (future
               (try
                 (leiningen.jammin/wait-for-hang (* 1000 ~seconds))
                 (catch Throwable e#
                   (.printStackTrace e#)))
               (leiningen.jammin/pprint-all-threads))
             ~form)
          `(do
             ~load-forms
             ~pre-form)))))

  (eval/apply-task task
    (utils/merge-projects project {:dependencies '[[io.aviso/pretty "0.1.17"]]})
    args))
