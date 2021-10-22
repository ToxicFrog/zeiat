(ns zeiat.translator
  "The translator agent implementation. This handles processing commands and refreshing the backend."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [zeiat.backend :as backend :refer [AnyName]]
    [zeiat.ircd.core :refer [*state* privmsg]]
    [zeiat.types :refer [TranslatorState]]
    [zeiat.state :as statelib]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]
    ))

(defn startup! :- TranslatorState
  "Called when the translator is created. Currently a no-op."
  [state :- TranslatorState]
  state)

(defn- filter-n-self-messages
  [n messages]
  (let [to-remove (->> messages (filter #(= :me (:from %))) (take n) set)]
    [(count to-remove) (filter (complement to-remove) messages)]))

(defn fetch-new-messages :- TranslatorState
  [state :- TranslatorState, chats :- [AnyName]]
  (reduce
    (fn [state chat]
      (log/trace "fetch-new-messages" chat (:last-seen state))
      (let [{:keys [last-seen outgoing]} (statelib/read-cache state chat)
            messages (backend/read-messages-since (:backend state) chat last-seen)
            [n-removed displayable-messages] (filter-n-self-messages outgoing messages)]
        (run! privmsg displayable-messages)
        (log/debug "Updating last-seen value for", chat, "to", (:timestamp (last messages) last-seen), "from", last-seen)
        (log/debug "Reducing outgoing counter for" chat "by" n-removed "currently" outgoing)
        (when (> n-removed outgoing)
          (log/warn chat "n-removed value greater than outgoing:" n-removed outgoing))
        ; If messages is empty, (:timestamp (last messages)) is nil, so in that case
        ; we default to the value we have recorded already -- otherwise we would end up
        ; erasing it and fetching the entire history next time.
        ; TODO anything that can write a new cache entry (which right now means this, recap, and PRIVMSG handlers)
        ; needs to write a COMPLETE new cache entry, which is not great
        (statelib/write-cache
          state chat
          :last-seen (:timestamp (last messages) last-seen)
          :outgoing (max 0 (- outgoing n-removed)))
        ))
        ; TODO anything that results in a channel being re-statted should also result
        ; in a new NAMES line being sent to the client
    state chats))

(defn- interesting?
  [{:keys [channels] :as _state} {:keys [type name] :as _chat}]
  ;(log/trace "interesting?" _chat)
  (or (= type :dm)
    (contains? channels name)))

; TODO this logic needs some rethinking; in particular it causes problems on Discord when we have a situation
; where a channel is focused -- the channel state is always :read, so we have to compare the last-seen field
; from the channel info struct to the one in the cache, but if we have yet to read messages from that channel
; the last-seen field in the cache is nil, so we never realize we need to fetch messages from it
; perhaps we should do something like: if we get a last-seen value but it's also marked read and we don't
; have a cache entry for it, initialize the cache to that value?
(defn- unread?
  [state {:keys [name status] :as chat}]
  (let [last-seen (:last-seen (statelib/read-cache state name))]
    (log/trace "unread?" last-seen chat)
    (cond
      ; No cache entry? We have to trust the :read/:unread status reported by the backend.
      (nil? last-seen) (= :unread status)
      ; If we have a cache entry but it's set to the special value "", always treat it as unread.
      (= "" last-seen) true
      ; If we have a cache entry, but the backend didn't return anything to compare it to,
      ; treat it as if we had no cache entry.
      (nil? (:last-seen chat)) (= :unread status)
      ; Finally, if we do have a cache entry *and* the backend returned something we can compare it to, do so.
      :else (not= last-seen (:last-seen chat))
      )))

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
      ; TODO the send returns immediately so I'm pretty sure this can result in poll requests piling up
      ; the poll future should be recreated in fetch-new-messages instead
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
