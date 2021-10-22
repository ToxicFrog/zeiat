(ns zeiat.state
  "Convenience functions for manipulating the TranslatorState internals."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [zeiat.backend :as backend :refer [AnyName]]
    [zeiat.ircd.core :refer [*state* privmsg]]
    [zeiat.types :refer [TranslatorState CacheEntry]]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    ))

(defn has-cache? :- s/Bool
  [state :- TranslatorState, channel :- AnyName]
  (contains? (:cache state) channel))

(defn read-cache :- CacheEntry
  [state :- TranslatorState, channel :- AnyName]
  (get-in state [:cache channel] {:last-seen nil :outgoing 0}))

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
