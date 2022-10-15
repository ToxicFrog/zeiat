(ns zeiat.state
  "Convenience functions for manipulating the TranslatorState internals."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [clojure.string :as string]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    [zeiat.backend :as backend :refer [AnyName]]
    [zeiat.ircd.core :as ircd]
    [zeiat.types :refer [TranslatorState CacheEntry]]))

(defn has-cache? :- s/Bool
  [state :- TranslatorState, channel :- AnyName]
  (contains? (:cache state) channel))

(defn read-cache :- CacheEntry
  [state :- TranslatorState, channel :- AnyName]
  (get-in state [:cache channel] {:last-seen nil :outgoing 0 :sendq []}))

(defn write-cache :- TranslatorState
  [state :- TranslatorState, channel :- AnyName, & rest]
  (assoc-in state [:cache channel]
    (apply assoc (read-cache state channel) rest)))

(defn update-cache :- TranslatorState
  [state :- TranslatorState, channel :- AnyName, & rest]
  (assoc-in state [:cache channel]
    (reduce
      ; does (apply update cache-entry [key update-fn]) for each pair of things in rest
      (partial apply update)
      (read-cache state channel)
      (apply assoc nil rest))))

; TODO: this is duplicated between here and ircd/messages
(defn- reply-missing [target]
  (if (= \# (first target))
    (ircd/numeric 403 target "No such channel")
    (ircd/numeric 401 target "No such user")))

(defn flush-queue
  [state :- TranslatorState, channel :- AnyName]
  "Unconditionally flush the queue for the given channel."
  (let [queue (-> state (read-cache channel) :sendq)
        pending (count queue)
        msg (string/join "\n" queue)]
    (log/trace "Flushing sendq for" channel "with content" queue "aka" msg)
    (if (backend/write-message (:backend state) channel msg)
      ; Successful send; empty queue and increment outgoing count.
      ; TODO: on backends that don't support message batching, the outgoing count
      ; will be too low.
      (update-cache state channel
        :outgoing inc
        :sendq (constantly []))
      ; Unsuccessful send. Complain to the user and empty the queue.
      (do
        (log/trace "Flush failed, reporting error to user.")
        (reply-missing channel)
        (write-cache state channel :sendq [])))))

(defn try-flush-queue
  "Flush the queue for the given channel if it contains exactly n queued messages. One of these is scheduled whenever a new message is enqueued; if more messages get added after scheduling, this is a no-op."
  ; TODO: only have one queued flush and just reset its timer when we enqueue a new message.
  [state :- TranslatorState, channel :- AnyName, n :- s/Int]
  (let [queue (-> state (read-cache channel) :sendq)
        pending (count queue)]
    (log/trace "Flush:" channel "n:" n "queued:" (count queue))
    (if (= n pending)
      (flush-queue state channel)
      state)))

(defn enqueue :- TranslatorState
  [state :- TranslatorState, channel :- AnyName, msg :- s/Str]
  (update-in state [:cache channel :sendq]
    (fn [queue]
      (let [queue' (conj queue msg)
            n (count queue')]
        (log/trace "Enqueue:" channel "n:" n "msg:" msg)
        (future
          ; TODO: at the moment we sleep for 4s before flushing, because the gap between message receptions
          ; can be pretty bad. We really need some way of bringing the latency down here, or unambiguously telling
          ; when a multiline message is done.
          (Thread/sleep 4000)
          (send *agent* try-flush-queue channel n))
        queue'))))
