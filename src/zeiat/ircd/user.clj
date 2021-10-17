(ns zeiat.ircd.user
  "User-management commands for the ircd interface."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [zeiat.ircd.core :as ircd :refer [message *state* reply-from numeric]]
    [zeiat.translator :as translator]
    [zeiat.types :refer [TranslatorState]]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    ))

;;; Client Management

(defn- registered?
  "True if the client connection is fully registered."
  [state]
  (and
    ; NAME and USER commands need to have been completed
    (:name state)
    (:user state)
    ; and :caps needs to either be absent (no CAP LS command seen) or, if present,
    ; needs to contain the :FINISHED marker indicating that capability negotiation
    ; has completed.
    (or (nil? (:caps state)) (:FINISHED (:caps state)))))

(defn check-registration [state]
  (when (registered? state)
    (binding [*state* state]
      (let [welcome (translator/connect! state)]
        (numeric 1 "Welcome to the Zeiat IRC relay.")
        (numeric 4 welcome)
        (numeric 5 "CHANTYPES=# NICKLEN=64 SAFELIST MAXTARGETS=1 LINELEN=8192")
        (numeric 376 "End of MOTD."))))
  state)

(defmethod message :NICK :- TranslatorState
  [_ nick]
  (if (registered? *state*)
    ; User already logged in, don't allow name changes post hoc
    (numeric 484 "NICK command restricted")
    ; Otherwise add it to the state
    (-> *state*
        (assoc :name nick)
        (check-registration))))

(defmethod message :USER :- TranslatorState
  [_ uname _mode _unused rname]
  (if (registered? *state*)
    (numeric 462 "Connection already registered.")
    (-> *state*
        (assoc :user uname)
        (assoc :realname rname)
        (check-registration))))

(defmethod message :PASS :- TranslatorState
  [_ pass]
  (if (registered? *state*)
    (numeric 462 "Connection already registered.")
    (assoc *state* :pass pass)))

(defmethod message :PING :- TranslatorState
  [_ ping]
  (reply-from "Zeiat" "PONG" "Zeiat" ping))

(defmethod message :QUIT :- TranslatorState
  [_ reason]
  (translator/shutdown! *state* reason))
