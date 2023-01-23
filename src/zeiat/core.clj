(ns zeiat.core
  "A library for connecting IRC to other protocols. Zeiat only handles the IRC frontend; it's up to you to provide a backend for it to connect to."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [clojure.java.io :as io]
    [zeiat.ircd :as ircd]
    [zeiat.ircd.core :as ircd-core]
    [zeiat.translator :as translator]
    [zeiat.backend :as backend]
    [zeiat.types :refer [TranslatorAgent TranslatorState ZeiatBackend]]
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
          ; TODO: if we get a whole bunch of privmsgs in rapid succession, concat them together and send them off as a single
          ; message with embedded newlines
          ; the backend is then responsible for sending it as a single message, in backends that support newlines,
          ; or splitting it up if it doesn't.
          (apply send agent
            ircd/dispatch-message (ircd/parse-line line)))
        (log/trace "socket closed, gbye")
        (catch Exception e
          (log/error e "Error reading from socket" socket "shutting down this client.")
          (send agent translator/shutdown! "client socket closed"))))))

(defn ^:private backend-replier :- s/Any
  "Called by the backend when it needs to send data to the IRC client directly, e.g. numerics detailing additional connection steps. Same behaviour as ircd.core/reply.
  Note that it is assumed to be called from 'inside' the agent for that backend, so (a) it implicitly depends on *agent* and (b) the message goes on the wire immediately rather than going into the agent queue."
  [& fields]
  (binding [ircd-core/*state* @*agent*]
    (apply ircd-core/reply fields)))

(defn create :- TranslatorAgent
  "Create a translator agent using the given socket (already connected to an IRC client) and backend instance."
  [socket :- Socket, backend :- ZeiatBackend]
  (let [agent (agent {:socket socket
                      :backend backend
                      :poll-interval 5000
                      :name nil :user nil :realname nil :pass nil
                      :channels #{}
                      :cache {}
                      :writer (-> socket io/writer (PrintWriter. true))}
                ; We do not install the validator here because we can't create the :reader field
                ; until after the agent has been created; so instead we partially create the agent
                ; state here, then finish creating it in initialize-agent, which also installs
                ; the validator.
                :error-mode :fail
                :error-handler handle-agent-error)]
    (log/trace "Creating translator agent:", agent)
    (send agent assoc :reader (make-reader socket agent))
    (send agent (fn [state]
                  (set-validator! agent (s/validator TranslatorState))
                  state))
    (send agent translator/startup!)))

(defschema Replier
  (s/=> s/Any [s/Str]))

(defn run :- [TranslatorAgent]
  "Open a listen socket and loop accepting clients from it and creating a new Zeiat instance for each client. Continues until the socket is closed, then returns a seq of all still-connected clients."
  [listen-port :- s/Int, make-backend :- (s/=> ZeiatBackend Replier)]
  (let [sock (ServerSocket. listen-port)]
    (log/info "Listening for connections on port" listen-port)
    (loop [clients []]
      (if (not (.isClosed sock))
        (recur
          ; TODO: once we can detect dead clients, we should filter them from the list here
          ; so they don't endlessly pile up.
          (conj clients (create (.accept sock) (make-backend backend-replier))))
        clients))))

(defn running? :- s/Bool
  [agent :- TranslatorAgent]
  (realized? (:reader @agent)))
