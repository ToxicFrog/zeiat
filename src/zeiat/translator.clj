(ns zeiat.translator
  "The translator agent implementation. This handles processing commands and refreshing the backend."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [zeiat.backend :as backend :refer [AnyName]]
    [zeiat.ircd.core :refer [*state* privmsg]]
    [zeiat.types :refer [TranslatorState]]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    ))

(defn startup! :- TranslatorState
  "Called when the translator is created. Currently a no-op."
  [state :- TranslatorState]
  state)

(defn fetch-new-messages :- TranslatorState
  [state :- TranslatorState, chats :- [AnyName]]
  (reduce
    (fn [state chat]
      (log/trace "fetch-new-messages" chat (:last-seen state))
      (let [last-seen (get-in state [:last-seen chat])
            messages (backend/read-messages-since (:backend state) chat last-seen)]
        (run! privmsg (filter #(not= :me (:from %)) messages))
        (assoc-in state [:last-seen chat] (:timestamp (last messages) "--MISSING--"))))
    state chats))

(defn- interesting?
  [{:keys [channels] :as _state} {:keys [type name] :as _chat}]
  (log/trace "interesting?" _chat)
  (or (= type :dm)
    (contains? channels name)))

(defn- unread?
  [{:keys [last-seen] :as _state} {:keys [name status] :as chat}]
  (log/trace "unread?" chat (get last-seen name))
  (cond
    ; If we do not have a cache entry, or if the backend doesn't have a :last-seen field,
    ; consider the :status field authoritative
    (not (contains? last-seen name)) (= :unread status)
    (nil? (:last-seen chat)) (= :unread status)
    ; If it does have a :last-seen field, compare it with the cache entry
    :else (not= (last-seen name) (:last-seen chat))
    ))

(defn- poll
  [{:keys [backend socket] :as state}]
  (if (.isClosed socket)
    (do (log/trace "poll thread exiting") state)
    (do
      (log/trace "polling for new messages...")
      (binding [*state* state]
        (->> (backend/list-chat-status backend)
             (filter (partial interesting? state))
             (filter (partial unread? state))
             (map :name)
             (send *agent* fetch-new-messages)))
      (future
        (Thread/sleep 5000)
        (send *agent* poll))
      state)))

(defn connect! :- s/Str
  "Called when user registration completes successfully. Should connect to the backend. Returns the info string provided by the backend."
  [state :- TranslatorState]
  (let [user (assoc (select-keys state [:name :user :realname :pass])
               :host (.. (:socket state) getInetAddress getCanonicalHostName))
        welcome (backend/connect (:backend state) user)]
    (poll state)
    welcome))

(defn shutdown! :- TranslatorState
  ([state]
   (shutdown! state "(no reason given)"))
  ([{:keys [socket writer backend] :as state} :- TranslatorState, reason :- s/Str]
   (log/info "Shutting down translator:" socket "because:" reason)
   (when (not (.isClosed socket))
     (.println writer (str ":Zeiat ERROR :" reason))
     (.close socket))
   (backend/disconnect backend)
  state))
