(ns zeiat.backend
  "Schema and protocol definitions for Zeiat backends. If implementing a new backend, you should start here."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [clojure.string :as string]))

(defschema ChannelName
  "An IRC-compatible channel name. Only #channels are supported."
  (s/constrained
    s/Str
    #(re-matches #"#[^ ,]+" %)))

(defschema UserName
  "An IRC-compatible user name. This is somewhat more permissive than rfc2812, allowing any non-empty name that doesn't contain !@: or whitespace."
  (s/constrained
    s/Str
    #(re-matches #"[^ !@:]+" %)))

(defschema AnyName
  (s/conditional
    #(string/starts-with? % "#") ChannelName
    string? UserName))

(defschema User
  "User metadata returned by listUsers and listUnread."
  {:name UserName
   :user UserName
   :host UserName
   :realname s/Str
   ; Backend can store whatever additional information here is convenient
   s/Any s/Any
   })

(defschema Channel
  "Channel metadata returned by listChannels, listUnread, and statChannel."
  {:name ChannelName  ; IRC-compatible name of channel
   ; List of users on channel. May be empty on listChannels, but should be filled
   ; in if possible by statChannel.
   :users [User]
   ; Count is displayed in LIST and is nominally the number of users in the channel,
   ; but in some backends that is either very expensive to determine or not meaningful
   ; at all; in that case the backend may choose to send an "activity count" such as
   ; number of unread messages, or just send 0.
   :count s/Int
   ; Channel topic. If the backend actually has this as a concept, include it where
   ; possible. If not, or if it's particularly expensive to fetch, include any other
   ; human-facing information that might be useful -- channel category, full name,
   ; etc. If you can't get anything just send the empty string.
   :topic s/Str
   ; Backend can store whatever additional information here is convenient
   s/Any s/Any
   })

(defschema Chat
  "Something that can send and receive messages, i.e. either a User or a Channel."
  ; TODO use cond-pre here, etc
  (s/conditional
    :user User
    :users Channel))

(defschema ChatTarget
  "The target of a chat message; either a User or the special value :me to indicate the logged in user."
  (s/cond-pre (s/enum :me) User))

(defschema Message
  {; TODO should probably rename this "id" since it might not be a ts in all backends
   ; TODO we will need to extend this to have both id and timestamp information, the former
   ; for readMessagesSince, the latter for the servertime capability -- these will be the
   ; same in a lot of backends but not necessarily all of them!
   :timestamp s/Any
   ; Origin, should be :me if the author is the logged in user and the author otherwise
   :from (s/cond-pre (s/enum :me) User)
   ; Destination, should be :me for incoming DMs, the target user for outgoing DMs, and
   ; the target channel for channel messages
   :to (s/conditional
         keyword? (s/enum :me)
         :user User
         :users Channel)
   ; The actual text of the message
   :text s/Str
   ; Backend can store whatever additional information here is convenient, e.g.
   ; timestamps, the original HTML, whatever.
   s/Any s/Any
   })

(defschema ChatStatus
  "Status information for a chat (a channel or DM).
  The :status field should always be present and be :read if the backend knows *definitively* that there are no pending messages in that chat, and :unread if it knows *or suspects* that there may be unread messages.
  The :last-seen field is optional, but should be included whenever possible; it should be the ID of the most recently seen message, and will be matched up with IDs returned by readMessages and readMessagesSince."
  {:name AnyName
   :type (s/enum :dm :channel)
   :status (s/enum :read :unread)
   (s/optional-key :last-seen) s/Str})

(defprotocol ZeiatBackend
  "A protocol that Zeiat uses to communicate with whatever backend you connect to it. Library users should supply something that implements this protocol to zeiat/create, or a function that returns protocol implementions to zeiat/run."
  (connect [this user] ;- Str
    "Connect to the backend. Called when a new user connects to Zeiat and completes IRC user registration (NICK/USER/PASS commands). The user parameter holds the user information, with an additional :pass field if the user supplied a password.
    It should return a user-readable string that will be displayed to the user as part of a server info message.")
  (disconnect [this] ;- nil
    "Disconnect from the backend. Called when a user quits or is otherwise disconnected.")
  (listChannels [this] ;- [Channel]
    "List all available channels. Called in response to a user's LIST command.")
  (listUsers [this] ;- [User]
    "List all available (DMable) users. Called in response to a user's WHO command.")
  (listChatStatus [this] ;- {AnyName ChatStatus}
    "List status information for all chats; should return a map of chat IRC name to status. See the definition for ChatStatus for details on what should be included.")
  (statChannel [this channel] ;- Chat
    "Return information about a channel's users and read status.")
  (readMessages [this channel] ;- [Message]
    "Return all messages from the given channel available in the backscroll. Implementors can limit this to only what's easily available if convenient (e.g. return only history that was autoloaded, not all history). Calling this should mark the chat as read.")
  (readMessagesSince [this channel id] ;- [Message]
    "Return all messages from the given channel after (not including) the message with the given ID. Calling this should mark the chat as read.")
  (writeMessage [this channel message] ;- bool
    "Send a message to the given channel or user. Returns true if the message was successfully sent, false otherwise.")
  (writeAction [this channel action] ;- bool
    "Send a CTCP ACTION command to the given channel (i.e. the '/me does stuff' command)."))

(defschema ^:private Backend (s/protocol ZeiatBackend))

;; Functions for checked calls to backend impls
(defn connect :- s/Str
  [this :- Backend, user :- User]
  (.connect this user))

(defn disconnect
  [this :- Backend]
  (.disconnect this))

(defn list-channels :- [Channel]
  [this :- Backend]
  (.listChannels this))

(defn list-users :- [User]
  [this :- Backend]
  (.listUsers this))

(defn list-chat-status :- [ChatStatus]
  ([this :- Backend]
   (.listChatStatus this)))

(defn stat-channel :- Channel
  [this :- Backend, channel :- ChannelName]
  (.statChannel this channel))

(defn read-messages :- [Message]
  [this :- Backend, channel :- AnyName]
  (.readMessages this channel))

(defn read-messages-since :- [Message]
  [this :- Backend, channel :- AnyName, id :- s/Any]
  (.readMessagesSince this channel id))

(defn write-message :- s/Bool
  [this :- Backend, channel :- AnyName, msg :- s/Str]
  (.writeMessage this channel msg))

(defn write-action :- s/Bool
  [this :- Backend, channel :- AnyName, action :- s/Str]
  (.writeAction this channel action))
