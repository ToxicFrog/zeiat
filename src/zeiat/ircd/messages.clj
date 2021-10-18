(ns zeiat.ircd.messages
  "Commands for sending and receiving messages."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [clojure.string :as string]
    [zeiat.backend :as backend]
    [zeiat.ircd.core :as ircd :refer [message *state* numeric privmsg]]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    ))

(defn- is-ctcp?
  [msg]
  (and
    (= \u0001 (first msg))
    (= \u0001 (last msg))))

(defn- write-ctcp
  [channel msg]
  (let [[command payload] (-> msg
                              (subs 1 (dec (count msg)))
                              (string/split #" " 2))]
    (backend/write-ctcp channel command payload)))

(defmethod message :PRIVMSG
  [_ channel msg]
  ; TODO: echo the sent message back to the client when it appears, if the client
  ; has negotiated the echo capability
  (cond
    (is-ctcp? msg) (write-ctcp channel msg)
    (backend/write-message (:backend *state*) channel msg) *state*
    (= \# (first channel)) (numeric 403 channel "No such channel")
    :else (numeric 401 channel "No such user")))

(defmethod message :RECAP
  [_ channel]
  (let [recap (backend/read-messages (:backend *state*) channel)]
    (log/trace "Done fetching RECAP, total message count:" (count recap))
    (if (nil? recap)
      (numeric 403 channel "No such user/channel")
      (do (run! privmsg recap) *state*))))
