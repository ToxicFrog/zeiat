(ns zeiat.core
  "A library for connecting IRC to other protocols. Zeiat only handles the IRC frontend; it's up to you to provide a backend for it to connect to."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [clojure.data]
    [clojure.java.io :as io]
    [zeiat.ircd :as ircd]
    [zeiat.ircd.core :as ircd-core]
    [zeiat.state :as statelib]
    [zeiat.translator :as translator]
    [zeiat.types :refer [TranslatorAgent TranslatorState ZeiatBackend ZeiatOptions]]
    #_{:clj-kondo/ignore [:unused-referred-var]}
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log])
  (:import
    [java.io PrintWriter BufferedReader]
    [java.net Socket ServerSocket]))

(defn- handle-agent-error
  [agent error]
  (log/warn error "Error in agent" agent)
  (try
    (translator/shutdown! @agent (str error))
    (catch Exception e
      (log/error e "Error shutting down agent")
      (System/exit 2))))

; TODO this throws if the socket is closed, we should catch the throw and terminate gracefully
(defn- reader-seq [reader]
  (take-while
    some?
    (repeatedly #(.readLine reader))))

(defn ^:private make-reader :- (s/pred future?)
  "Spawn a thread that endlessly reads messages from the socket, parses them, and sends them to the agent. Returns a future that will be realized when the socket is closed."
  [socket :- Socket, agent :- TranslatorAgent]
  (log/trace "Creating socket reader:", socket)
  (let [reader (-> socket io/reader BufferedReader.)]
    (log/trace "reader created, creating future")
    (future
      (log/trace "socket reader thread, here we go!")
      (try
        (doseq [line (reader-seq reader)]
          (apply send agent
            ; the aliasing of dispatch-message into ircd/core confuses it
            #_{:clj-kondo/ignore [:unresolved-var]}
            ircd/dispatch-message (ircd/parse-line line)))
        (log/trace "socket closed, gbye")
        (catch Exception e
          (log/error e "Error reading from socket" socket "shutting down this client.")
          (send agent translator/shutdown! "client socket closed"))))))

(defschema BackendConstructor
  "Schema for functions passed to core/run to create new ZeiatBackend instances appropriate to the host program. It will be called with the Agent that backend will be owned by and should return an implementation of ZeiatBackend. See backend.clj for the protocol and related schemas."
  (s/=> ZeiatBackend
    clojure.lang.Agent))

(defn create :- TranslatorAgent
  "Create a translator agent using the given socket (already connected to an IRC client) and backend instance."
  [socket :- Socket, make-backend :- BackendConstructor, options :- ZeiatOptions]
  (let [agent (agent {:socket socket
                      :options options
                      :userdata {}
                      :name nil :user nil :realname nil :pass nil
                      :channels #{}
                      :cache {}
                      :writer (-> socket io/writer (PrintWriter. true))}
                ; We do not install the validator here because we can't create the :reader or :backend
                ; until after the agent has been created; so instead we partially create the agent
                ; state here, then finish creating it in initialize-agent, which also installs
                ; the validator.
                :error-mode :fail
                :error-handler handle-agent-error)]
    (log/trace "Creating translator agent:", agent)
    (send agent assoc
      :backend (make-backend agent)
      :reader (make-reader socket agent))
    (send agent (fn [state]
                  (set-validator! agent (s/validator TranslatorState))
                  state))
    (add-watch agent :trace-watcher
      (fn [_key _agent state state']
        (let [[removed added _] (clojure.data/diff state state')]
          (when (or removed added)
            (log/trace "Agent updated:" (state :name)))
          (when removed
            (log/trace "-" removed))
          (when added
            (log/trace "+" added)))))
    (send agent translator/startup!)))

(defn running? :- s/Bool
  [agent :- TranslatorAgent]
  (and
    (realized? (:reader @agent))
    (not (.isClosed (:socket @agent)))))

(def default-options
  {:poll-interval 5000
   :cache-key nil})

(defn run :- [TranslatorAgent]
  "Open a listen socket and loop accepting clients from it and creating a new Zeiat instance for each client. Continues until the socket is closed, then returns a seq of all still-connected clients."
  ([listen-port :- s/Int, make-backend :- BackendConstructor]
   (run listen-port make-backend {}))
  ([listen-port :- s/Int, make-backend :- BackendConstructor, options :- ZeiatOptions]
   (let [sock (ServerSocket. listen-port)]
    (log/info "Listening for connections on port" listen-port)
    (loop [clients []]
      (if (not (.isClosed sock))
        (recur
          (conj
            (filter running? clients)
            (create (.accept sock) make-backend (merge default-options options))))
        clients)))))

;; API used by ZeiatBackend instances to communicate with the zeiat core

; we have a problem here in that poll needs to be callable from outside the *agent* for the intended
; use cases to work. This means the backend needs some way of getting the agent to which it is bound.
; but we don't have the agent until after the backend is created.
; I think the solution here is:
; - pass make-backend to create
; - create creates the agent containing a skeleton (as it does now) which is missing :backend
; - it then calls (create-backend agent) and stuffs that into :backend
; - then it does the reader assoc and other startup stuff it already does now
(defn poll
  "Queue a poll cycle to be executed on the given agent as soon as it's free. The agent will call .listChatStatus to get status info and .readMessagesSince to read any new messages that have arrived, then forward them to the client.

  Note that this will not schedule an automatic, recurring poll. To do that set the :poll-interval option when calling run."
  [agent continuation]
  (log/trace "Scheduling poll of agent" agent)
  (when (not (agent-error agent))
    (send-off agent translator/poll-once continuation)))

(defn reply-now
  "Send a reply to the client immediately. This must be called from inside the agent, i.e. inside one of the ZeiatBackend implementation functions after it's been called from Zeiat itself. The message goes on the wire as soon as this is called rather than waiting for the agent to be free."
  [& fields]
  (binding [ircd-core/*state* @*agent*]
    (apply ircd-core/reply fields)))

(defn reply
  "Queue a reply to the client. Rest arguments are the same as zeiat.ircd.core/reply. The reply will be sent next time the agent is free."
  [agent & fields]
  (apply send agent reply-now fields))

(defn set-cache-key!
  "Set the cache key for this agent. It will queue a task on the agent that (a) records the new cache key and (b) loads the corresponding cache file, if it exists. This will overwrite the existing cache, so it is recommended this be called only once and as soon as possible; ideally, pass the cache-key into (create) or (run), but failing that, call it before your backend returns from (connect), as that is your last chance to modify the cache before the polling loop starts."
  [cache-key]
  (log/trace "Scheduling cache key update to" cache-key)
  (send *agent*
    (fn [state]
      (log/info "Setting cache key to" cache-key "and overwriting current cache of size" (count (state :cache)))
      (let [state' (-> state (assoc-in [:options :cache-key] cache-key) statelib/load-cache)]
        (log/info "Loaded new cache of size" (count (state' :cache)))
        state'))))
