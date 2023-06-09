(ns zeiat.stub-backend
  "A stub implementation of ZeiatBackend, for testing."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [zeiat.backend :refer [ZeiatBackend]]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    ))

(defn make-stub :- (s/protocol ZeiatBackend)
  []
  (reify ZeiatBackend
    (connect [this user]
      (log/trace "stub: connect" this user)
      "this server is connected to a stub backend that does nothing")
    (disconnect [this]
      (log/trace "stub: disconnect" this))
    (listChannels [this]
      (log/trace "stub: listChannels" this)
      [])
    (listUsers [this]
      (log/trace "stub: listUsers" this)
      [])
    (listChatStatus [this]
      (log/trace "stub: listChatStatus" this)
      [])
    (statChannel [this channel]
      (log/trace "stub: statChannel" this channel)
      {:name "#test" :users [] :count 0 :topic "test channel"})
    (listMembers [this channel]
      (log/trace "stub: listMembers" this channel)
      [])
    (readMessages [this channel]
      (log/trace "stub: readMessages" this channel)
      [])
    (readMessagesSince [this channel id]
      (log/trace "stub: readMessages" this channel id)
      [])
    (writeMessage [this channel message]
      (log/trace "stub: writeMessage" this channel message)
      true)
    (writeAction [this channel action]
      (log/trace "stub: writeAction" this channel action)
      true)))
