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

(defmethod message :PRIVMSG
  [_ channel msg]
  ; TODO: echo the sent message back to the client when it appears, if the client
  ; has negotiated the echo capability
  ; TODO: handle CTCP properly, especially CTCP ACTION
  (if (backend/write-message (:backend *state*) channel msg)
    *state*
    (if (string/starts-with? channel "#")
      (numeric 403 channel "No such channel")
      (numeric 401 channel "No such user"))))

(defmethod message :RECAP
  [_ channel]
  (let [recap (backend/read-messages (:backend *state*) channel)]
    (log/trace "Done fetching RECAP, total message count:" (count recap))
    (if (nil? recap)
      (numeric 403 channel "No such user/channel")
      (do (run! privmsg recap) *state*))))
