(ns zeiat.state
  "Convenience functions for manipulating the TranslatorState internals."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [clojure.string :as string]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    [zeiat.backend :as backend :refer [AnyName]]
    [zeiat.ircd.core :as ircd]
    [zeiat.types :refer [TranslatorState CacheEntry Enqueued]]))

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

(defn send-partition :- s/Num
  [state :- TranslatorState, channel :- AnyName, partition :- [Enqueued]]
  (let [type (-> partition first first)
        payload (map second partition)]
    (case type
      :PRIVMSG
      (if (backend/write-message (:backend state) channel (string/join "\n" payload))
        1 0)
      :ACTION
      (->> payload
        (map (partial backend/write-action (:backend state) channel))
        (filter identity)
        count))))

(defn flush-queue :- TranslatorState
  "Unconditionally flush the queue for the given channel."
  [state :- TranslatorState, channel :- AnyName]
  (let [queue (->> (read-cache state channel) :sendq (partition-by first))]
    (log/trace "Flushing sendq for" channel "with content" queue)
    (update-cache state channel
      :outgoing (fn [outgoing]
                  (reduce + outgoing (map (partial send-partition state channel) queue)))
      :sendq (constantly []))))

(defn try-flush-queue :- TranslatorState
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
  [state :- TranslatorState, channel :- AnyName, msg :- Enqueued]
  (update-cache state channel :sendq
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

(defn enqueue-privmsg :- TranslatorState
  [state :- TranslatorState, channel :- AnyName, msg :- s/Str]
  (enqueue state channel [:PRIVMSG msg]))

(defn enqueue-action :- TranslatorState
  [state :- TranslatorState, channel :- AnyName, msg :- s/Str]
  (enqueue state channel [:ACTION msg]))
