(ns zeiat.translator
  "The translator agent implementation. This handles processing commands and refreshing the backend."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [zeiat.backend :as backend :refer [ChatStatus]]
    [zeiat.ircd.core :refer [privmsg]]
    [zeiat.types :refer [TranslatorState]]
    [zeiat.state :as statelib]
    #_{:clj-kondo/ignore [:unused-referred-var]}
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]))

(defn startup! :- TranslatorState
  "Called when the translator is created. Currently a no-op."
  [state :- TranslatorState]
  state)

(defn- filter-n-self-messages
  [n messages]
  (let [to-remove (->> messages (filter #(= :me (:from %))) (take n) set)]
    [(count to-remove) (filter (complement to-remove) messages)]))

(defn fetch-new-messages :- TranslatorState
  [state :- TranslatorState, chats :- [ChatStatus]]
  (reduce
    (fn [state info]
      (let [chat (:name info)]
       (log/debug "Fetching new messages for" chat
                  "with cached timestamp" (statelib/read-cache state chat)
                  "and info from backend" info)
       (log/trace "fetch-new-messages" chat (:last-seen state))
       (let [{:keys [last-seen outgoing]} (statelib/read-cache state chat)
             messages (backend/read-messages-since (:backend state) chat last-seen)
             [n-removed displayable-messages] (filter-n-self-messages outgoing messages)]
         (run! privmsg displayable-messages)
         (log/debug "Updating last-seen value for", chat, "to", (:timestamp (last messages) last-seen), "from", last-seen)
         (log/debug "Reducing outgoing counter for" chat "by" n-removed "from" outgoing)
         (when (> n-removed outgoing)
           (log/warn chat "n-removed value greater than outgoing:" n-removed outgoing))
         ; If messages is empty, (:timestamp (last messages)) is nil, so in that case
         ; we default to the value we have recorded already -- otherwise we would end up
         ; erasing it and fetching the entire history next time.
         (statelib/write-cache
           state chat
           ; TODO: (last messages) doesn't always work here because some backends (e.g. discord) send messages out-of-order
           ; in ways that still read reasonably but mean that the last message isn't always the one with the highest TS
           ; we should instead select the max timestamp from the messages we fetched
           ; probably something like: (last (sort (map :timestamp messages)))
           :last-seen (:timestamp (last messages) last-seen)
           :outgoing (max 0 (- outgoing n-removed))))))


         ; TODO anything that results in a channel being re-statted should also result
         ; in a new NAMES line being sent to the client
    state chats))

(defn- interesting?
  [{:keys [channels] :as _state} {:keys [type name] :as _chat}]
  ;(log/trace "interesting?" _chat)
  (or (= type :dm)
    (contains? channels name)))

(defn- unread?
  [state {:keys [name status] :as chat}]
  (let [last-seen (:last-seen (statelib/read-cache state name))]
    (log/trace "unread?" (statelib/read-cache state name) chat)
    (cond
      ; No cache entry? We have to trust the :read/:unread status reported by the backend.
      (nil? last-seen) (= :unread status)
      ; If we have a cache entry but it's set to the special value "", always treat it as unread.
      (= "" last-seen) true
      ; If we have a cache entry, but the backend didn't return anything to compare it to,
      ; treat it as if we had no cache entry.
      (nil? (:last-seen chat)) (= :unread status)
      ; Finally, if we do have a cache entry *and* the backend returned something we can compare it to, do so.
      :else (not= last-seen (:last-seen chat)))))

(defn- only-cache-update?
  "Given a chat-status struct, return true if we should use this to update the cache but don't need to do anything else with it."
  [state chat-status]
  (and
    (:last-seen chat-status)
    (= :read (:status chat-status))
    (nil? (statelib/read-cache state (:name chat-status)))))

(defn update-cache-entry
  [state [name last-seen]]
  (log/debug "Recording initial last-seen value of" last-seen "for" name)
  (statelib/write-cache state name :last-seen last-seen))


(defn- update-last-seen-cache
  [state potential-updates]
  (->> potential-updates
    ; Only process updates where we only need to update the cache, not also
    ; fetch new messages.
    ; In practice this means channels that:
    ; - have a timestamp from the backend
    ; - have no new messages, according to the backend
    ; - and for which we have no existing cache entry.
    (filter (partial only-cache-update? state))
    ; convert to seq of [name timestamp] pairs
    (map (juxt :name :last-seen))
    (reduce update-cache-entry state)))

(defn- fetch-new
  [state chats]
  (fetch-new-messages state
    (filter (every-pred (partial interesting? state)
             (partial unread? state))
            chats)))

; TODO: we probably want two agents:
; - a frontend agent that sends and recieves IRC messages and manages the sendq. Messages that can be replied to without
;   involving the backend will be. Messages that need to go to the backend get sent off with agent messages.
; - a backend agent that does polling of the backend and handles communication with it
; this needs (and doesn't have yet) a proper design to handle the fact that the state will need to be split across these
; agents and stuff...it's going to be tricky.
(defn poll-once
  [{:keys [backend] :as state}]
  (log/trace "polling for new messages...")
  (let [chats (backend/list-chat-status backend)]
    (-> state
      (update-last-seen-cache chats)
      (fetch-new chats))))

(defn- poll-repeatedly
  [{:keys [options socket] :as state}]
  (if (or (nil? (:poll-interval options)) (.isClosed socket))
    (do (log/trace "poll thread exiting") state)
    (let [state' (poll-once state)]
      (log/trace "poll complete, scheduling next poll")
      (future (Thread/sleep (:poll-interval options)) (send *agent* poll-repeatedly))
      state')))

(defn connect! :- s/Str
  "Called when user registration completes successfully. Should connect to the backend. Returns the info string provided by the backend."
  [state :- TranslatorState]
  (let [user (assoc (select-keys state [:name :user :realname :pass])
               :host (.. (:socket state) getInetAddress getCanonicalHostName))
        welcome (backend/connect (:backend state) user)]
    (poll-repeatedly state)
    welcome))

; TODO on SIGINT we don't reliably shut down the backend, which can e.g. leave
; orphaned chromedriver processes lying around everywhere
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
