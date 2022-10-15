(ns zeiat.ircd.messages
  "Commands for sending and receiving messages."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [clojure.string :as string]
    [zeiat.backend :as backend]
    [zeiat.ircd.core :as ircd :refer [message *state* numeric privmsg reply-from]]
    [zeiat.state :as statelib]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]))

(defn- is-ctcp?
  [msg]
  (and
    (= \u0001 (first msg))
    (= \u0001 (last msg))))

(defn- reply-missing [target]
  (if (= \# (first target))
    (numeric 403 target "No such channel")
    (numeric 401 target "No such user")))

(defn- write-ctcp
  [channel msg]
  (let [[command payload] (-> msg
                              (subs 1 (dec (count msg)))
                              (string/split #" " 2))]
    (cond
      (= "ACTION" command)
      (do
        (statelib/flush-queue *state* channel 0)
        (if (backend/write-action (:backend *state*) channel payload)
          (statelib/update-cache *state* channel :outgoing inc)
          (reply-missing channel)))
      :else
      (numeric 421 (str "CTCP:" command) "Unsupported CTCP subcommand"))))

(defn- write-privmsg
  [channel msg]
  (statelib/enqueue *state* channel msg))

(defmethod message :PRIVMSG
  [_ channel msg]
  ; TODO: echo the sent message back to the client when it appears, if the client
  ; has negotiated the echo capability
  (if (is-ctcp? msg)
    ; TODO we should only inc outgoing if this actually succeeds...we need
    ; better error handling in general
    (write-ctcp channel msg)
    (write-privmsg channel msg)))

; TODO: support optional second argument saying how many messages to recap
(defmethod message :RECAP
  [_ channel]
  ; sentinel value in the cache telling it to always assume unread and fetch all data
  (if (= \# (first channel))
      (reply-from "Zeiat" "NOTICE" channel "RECAP message received, please wait...")
      (reply-from channel "NOTICE" (:name *state*) "RECAP message received, please wait..."))
  (statelib/write-cache *state* channel
                        :last-seen ""))
