(ns zeiat.types
  "Schema type definitions for the Zeiat library."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [zeiat.backend :as backend]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]))

(defschema ZeiatBackend
  "Schema for objects implementing the Zeiat backend protocol. See zeiat.backend for details."
  (s/protocol backend/ZeiatBackend))

(defschema CacheEntry
  {; ID of most recently seen message. Can also be the sentinel values "" for "consider all messages unread"
   ; and nil for "no data, trust the read/unread bit from the backend instead".
   :last-seen (s/maybe s/Str)
   ; Number of outgoing messages we haven't seen echoed back yet and should drop on receipt
   :outgoing s/Int
   ; Queue of outgoing messages we haven't sent yet. This is used to batch together multiline messages
   ; so that the backend can send them as a single message.
   ; These are not included in the outgoing count.
   ; The queue is flushed (and :outgoing incremented) when the sendq timeout expires, or
   ; immediately when a message other than PRIVMSG is sent.
   :sendq [s/Str]})

(defschema TranslatorState
  "The internal state of a Zeiat translator session.
  Structurally, a Zeiat session consists of:
  - an agent that holds the TranslatorState and manages access to the backend
  - a thread that constantly reads messages from the IRC connection, parses them, and sends them to the agent
  - a thread that periodically reminds the agent to check the backend for new messages
  "
  {; Connection to the IRC client; read by the reader thread, written by the agent.
   :socket java.net.Socket
   ; Writer wrapped around the socket so we can println to it.
   :writer (s/pred (partial instance? java.io.Writer))
   ; A future backed by the reader thread. When the reader thread exits, this will
   ; become realized.
   :reader (s/pred future?)
   ; Backend object, implementation-dependent
   :backend ZeiatBackend
   ; Information about the state of the IRC client starts here.
   ; NICK/USER information -- used when generating PRIVMSG/JOIN events. Set once
   ; on connection and then fixed in place.
   ; TODO: move this to a backend/User struct so it can be directly compared
   :name (s/maybe backend/UserName)
   :user (s/maybe backend/UserName)
   :realname (s/maybe s/Str)
   :pass (s/maybe s/Str)
   ; Set of joined channels. The output of listUnread() will be intersected with this
   ; to determine which channels the client should get messages from.
   :channels #{backend/ChannelName}
   ; Negotiated capabilities.
   ; Currently supported caps:
   ; - message-tags (implicitly supported for server-time)
   ; - server-time (timestamped messages)
   ; - zeiat.ancilla.ca/autorecap (recap on channel join)
   ; Future support planned:
   ; - echo-message (once supported in weechat)
   ; - batch (for chathistory)
   ; - chathistory (once supported in weechat)
   ; - msgid (to support echo/edits/reactji)
   (s/optional-key :caps) #{(s/cond-pre s/Str (s/eq :FINISHED))}
   ; Cache of chat information. Stores the last-seen ID from the channel (for
   ; use with readMessagesSince) and the number of outgoing messages (so we can
   ; filter message echoes out).
   :cache {backend/AnyName CacheEntry}})


(defschema TranslatorAgent
  ; No validation for the agent interior as yet, but TranslatorState is installed as a validator when the agent is created, which should help.
  ; TODO: proper agent validation, perhaps by reifying the validator here and then checking that the agent's validator is == the one for TranslatorState
  (s/pred (partial instance? clojure.lang.Agent)))

