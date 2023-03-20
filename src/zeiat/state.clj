(ns zeiat.state
  "Convenience functions for manipulating the TranslatorState internals."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    [zeiat.backend :as backend :refer [AnyName]]
    [zeiat.ircd.core :as ircd]
    [zeiat.types :refer [TranslatorState CacheEntry Enqueued]])
  (:import
    [dev.dirs ProjectDirectories]))

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

(defn- find-cache
  "Returns a jio.File pointing to the cache file for this state, if it has a :cache-key. If it does not, returns nil. Note that the cache is not guaranteed to exist!"
  [state]
  (when-let [key (-> state :options :cache-key)]
    (io/file
      (.-cacheDir (ProjectDirectories/from "ca" "ancilla" "zeiat"))
      (str key ".edn"))))

(defn save-cache
  "Writes the last-seen cache for the given state to disk, at a location automatically computed from its user info."
  [state :- TranslatorState]
  (when-let [file (find-cache state)]
    (log/trace "Writing cache to" file "with value" (state :cache))
    (io/make-parents (.getPath file))
    (->> state :cache pr-str (spit (.getPath file))))
  state)

(defn load-cache
  "Loads the last-seen cache from a file previously written by save-cache! and returns a new state with the cache loaded. If the file does not yet exist, behaves as identity."
  [state :- TranslatorState]
  (let [file (find-cache state)]
    (if (and file (.exists file))
      (let [cache (->> file slurp edn/read-string)]
        (log/trace "Reading cache from" file "with value" cache)
        (assoc state :cache (update-vals cache #(assoc % :outgoing 0))))
      state)))

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
