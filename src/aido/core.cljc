(ns aido.core
  (:require [clojure.string :as str]
            [aido.options :as ao]
            [aido.compile :as ac]))

(def SUCCESS :success)
(def FAILURE :failure)
(def RUNNING :running)
(def ERROR :error)

(def ... nil)

(defrecord Result [status db])

(defn tick-result [status db]
  "Wraps a status (SUCCESS, FAILURE, etc..) and a db state to return them together."
  (Result. status db))







(defn has-not-failed? [result]
  (let [status (:status result)]
    (or (= status SUCCESS)
        (= status RUNNING))))

(def has-failed? (complement has-not-failed?))



(defn tick-node-type [db [node & _]]
  (println "decode: " node)
  node)


(defmulti tick
          "The tick function sends the tick to a node of different types."
          {:arglists '([db options node])}
          tick-node-type)


; node types


; LOOP
;
; The loop node allows to execute one child ... a multiple number of times, specified by the
; {:count n} parameter or until the child returns FAILURE.
;
; If no count is specified looping continues indefinitely
; The loop returns SUCCESS if the specified number of iterations succeed
; The loop returns FAILURE if the child fails on any iteration

[:loop {:count 3}
 ...]

(defmethod ao/required-options :loop [& _]
  [:count])

(defmethod tick :loop [db [node-type options & [child & _]]]
  (loop [db db
         n  0]
    (if (= n (:count options))
      (tick-result SUCCESS db)
      (let [result (tick db child)]

        (if (has-failed? result)
          result
          (recur (:db result) (inc n)))))))


(defmethod ao/required-options :loop-until-success [& _]
  [:count])

(defmethod tick :loop-until-success [db [node-type options & [child & _]]]
  (loop [db db
         n  0]
    (if (= n (:count options))
      (tick-result FAILURE db)
      (let [result (tick db child)]
        (if (has-not-failed? result)
          result
          (recur (:db result) (inc n)))))))

; PARALLEL
;
; The parallel node executes all of its children in parallel
;
; Parameters
; :success-mode :any (*) | :all
; :failure-mode :any | :all (*)
;
; When success-mode is :any the :parallel node returns SUCCESS if any child returns SUCCESS
; When success-mode is :all the :parallel node returns SUCCESS if all children return SUCCESS
; Similarly for :failure-mode
;
; If SUCCESS and FAILURE limits are hit at the same time SUCCESS wins

[:parallel {:success-mode :any :failure-mode :any}
 ...]

(defmethod ao/required-options :parallel [& _]
  [:mode :test])

(defmethod tick :parallel [db [node-type {:keys [mode test]} & children]]
  (let [c (count children)
        {:keys [success failure db]} (reduce (fn [{:keys [success failure db]} child]
                                               (let [result (tick db child)]
                                                 {:success (if (has-not-failed? result) (inc success) success)
                                                  :failure (if (has-failed? result) (inc failure) failure)
                                                  :db      (:db result)})
                                               ) {:success 0 :failure 0 :db db} children)]
    (cond
      (and (= :success mode) (= :all test) (= c success)) (tick-result SUCCESS db)
      (and (= :success mode) (= :any test) (> success 0)) (tick-result SUCCESS db)
      (= :success mode) (tick-result FAILURE db)
      (and (= :failure mode) (= :all test) (= c failure)) (tick-result FAILURE db)
      (and (= :failure mode) (= :any test) (> failure 0)) (tick-result FAILURE db)
      (= :failure mode) (tick-result SUCCESS db))))


; SELECT
;
; The :select node executes its children sequentially stopping after the first one that
; returns SUCCESS or RUNNING
;
; If no child returns SUCCESS the :select returns FAILURE

[:selector
 ...]

(defmethod tick :selector [db [node-type options & children]]
  (loop [db        db
         child     (first children)
         remaining (rest children)]
    (let [{:keys [status db] :as rval} (tick db child)]
      (if (has-not-failed? rval)
        rval
        (if (empty? remaining)
          (tick-result FAILURE db)
          (recur db (first remaining) (rest remaining)))))))

; SEQUENCE
;
; The :sequence node executes all of its children in turn.
;
; If any child returns FAILURE the sequence halts and returns FAILURE
; If all children return SUCCESS the sequence returns SUCCESS

[:sequence ...]

(defmethod tick :sequence [db [node-type options & children]]
  (reduce (fn [{:keys [db]} child]
            (let [result (tick db child)]
              (if (has-failed? result)
                (reduced result)
                result))
            ) {:db db} children))


; ALWAYS
;
; The :always node can have a single child and returns a fixed value regardless of whether the child
; returns SUCCESS or FAILURE
;
; :returns SUCCESS | FAILURE

[:always {:returning FAILURE}
 ...]

(defmethod ao/required-options :always [& _]
  [:returning])

(defmethod tick :always [db [node-type options & [child & _]]]
  (let [{:keys [status db]} (tick db child)]
    (tick-result (:returning options) db)))

; RANDOMLY
;
; The :randomly node either
; takes one child and executes it if
;
; Parameters
; :p - chance of executing the child from 0.0 (never) to 1.0 (always)
;
; Returns
; SUCCESS if the child is executed and returns SUCCESS
; FAILURE if the child is not executed or the child returns FAILURE

[:random {:p 0.8}
 ...]

(defmethod ao/required-options :randomly [& _]
  [:p])

(defmethod tick :randomly [db [node-type options & children]]
  (case (count children)
    1 (if (< (rand) (:p options))
        (tick db (first children))
        (tick-result FAILURE db))
    2 (if (< (rand) (:p options))
        (tick db (first children))
        (tick db (second children)))))

(defmethod tick :failure [db [node-type options & _]]
  (tick-result FAILURE db))

(defmethod tick :success [db [node-type options & _]]
  (tick-result SUCCESS db))