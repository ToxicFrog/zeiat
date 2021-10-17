(ns zeiat.ircd.cap
  "Capability negotiation."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [clojure.string :as string]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    [zeiat.ircd.core :as ircd :refer [message *state* numeric reply-from]]
    [zeiat.ircd.user :as user]
    [zeiat.translator :as translator]
    [zeiat.types :refer [TranslatorState]]
    ))

;;; Client Management

; ideally, we'll want message-tags, msgid, and servertime capabilities
; chathistory and multiline would also be nice but those are still draft
; we can do vendor-specific caps by prefixing them with a hostname, so e.g.
; ancilla.ca/autorecap-dm, ancilla.ca/autorecap-channel, ancilla.ca/ctcp-edit,
; ancilla.ca/ctcp-react, and so forth
(def ^:private supported-caps
  #{"message-tags" "server-time" "zeiat.ancilla.ca/autorecap"})

(defn- try-enable-caps
  [state caps]
  (log/debug "Client negotiating capabilities:" caps)
  (if (not-empty (filter (complement supported-caps) caps))
    (do
      (log/debug "Requested caps don't match supported caps:" supported-caps)
      (reply-from "Zeiat" "CAP" "*" "NAK" (string/join " " caps)))
    (do
      (log/debug "Enabling caps:" caps)
      (reply-from "Zeiat" "CAP" "*" "ACK" (string/join " " caps))
      (update *state* :caps #(into % caps)))))

(defmethod message :CAP :- TranslatorState
  [_ cmd & rest]
  (log/trace "CAP" (keyword (string/upper-case cmd)) cmd rest)
  (case (keyword (string/upper-case cmd))
    ; list available caps. Also creates the :caps set in the state if not
    ; already present.
    :LS (do
          (reply-from "Zeiat" "CAP" "*" "LS" (string/join " " supported-caps))
          (update *state* :caps #(or % #{})))
    ; list caps currently enabled on this connection
    :LIST (reply-from "Zeiat" "CAP" (:name *state* "*") "LIST" (string/join " " (:caps *state*)))
    ; enable and ACK or reject and NACK requested caps
    :REQ (try-enable-caps *state* (string/split (first rest) #" "))
    ; end capability negotiation and permit registration to complete
    :END (user/check-registration (update *state* :caps #(conj % :FINISHED)))
    (numeric 410 cmd "Invalid CAP command")))
