(ns hangbrain.zeiat.translator
  "The translator agent implementation. This handles processing commands and refreshing the backend."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [hangbrain.zeiat.types :refer [TranslatorState TranslatorAgent]]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    ))

(defn startup! :- TranslatorState
  "Called when the translator is created. Currently a no-op."
  [state :- TranslatorState]
  state)

(defn connect! :- TranslatorState
  "Called when user registration completes successfully. Should connect to the backend."
  [state :- TranslatorState]
  (.connect (:backend state))
  state)

(defn shutdown! :- TranslatorState
  [{:keys [socket writer backend] :as state} :- TranslatorState]
  (log/info "Shutting down translator:" socket)
  (when (not (.isClosed socket))
    (.println writer "QUIT translator shutdown")
    (.close socket))
  (.disconnect backend)
  state)
