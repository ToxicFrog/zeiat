(ns zeiat.ircd
  "Proxy module that loads all the modules making up the IRCD engine and exports the 'dispatch-message function to send events to it. It also includes some functions for parsing IRC traffic."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [zeiat.ircd.core :as ircd-core]
    [zeiat.ircd.user]
    [zeiat.ircd.cap]
    [zeiat.ircd.channels]
    [zeiat.ircd.messages]
    [zeiat.ircd.list]
    [taoensso.timbre :as log]
    #_{:clj-kondo/ignore [:unused-referred-var]}
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [clojure.string :as string]))


(intern *ns* 'dispatch-message ircd-core/dispatch-message)

(defn- extract-prefix
  [message]
  (if (= \: (first message))
    ; prefix -- split into prefix and tail, drop : from prefix
    (update (string/split message #" " 2)
      0 string/replace-first ":" "")
    ; no prefix
    [nil message]))

(defn- next-message-field
  [message]
  (if (= \: (first message))
    ; field starts with :, all the rest of the message is the field
    [(string/replace-first message ":" "") nil]
    ; no leading :, eat the field up to the next space
    (string/split message #" " 2)))

(defn parse-line
  "Parse an IRC message into a prefix, command, and args."
  [message]
  (log/trace ">>>" message)
  (let [[_prefix message] (extract-prefix message)]
    (loop [message message fields []]
      (if (nil? message)
        fields
        (let [[field message] (next-message-field message)]
          (recur message (conj fields field)))))))
