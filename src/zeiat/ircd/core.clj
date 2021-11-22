(ns zeiat.ircd.core
  "Core functions for the ircd."
  (:refer-clojure :exclude [def defn defmethod defrecord fn letfn])
  (:require
    [clojure.string :as string]
    [zeiat.types :refer [TranslatorState]]
    [schema.core :as s :refer [def defn defmethod defrecord defschema fn letfn]]
    [taoensso.timbre :as log]))

(def ^:dynamic *state* nil)

(defn- fields-to-message
  [fields]
  (string/join
    " "
    (concat
      (butlast fields)
      [(str ":" (last fields))])))

(defn reply
  [& fields]
  (let [message (fields-to-message fields)]
    (log/trace "<<<" message)
    (.println (:writer *state*) message))
  *state*)

(defn- tags->str
  [tags]
  (->> tags
       (map (fn [[k v]] (str k "=" v)))
       (map (fn [s] s)) ; TODO implement tag escapement
       (string/join ";")))

(defn reply-tagged
  [tags & fields]
  (apply reply (->> tags tags->str (str "@")) fields))

(defn reply-tagged-from
  [tags name & fields]
  (apply reply-tagged tags (str ":" name) fields))

(defn reply-from
  [name & fields]
  (apply reply (str ":" name) fields))

(defn numeric
  [num & fields]
  (.println
    (:writer *state*)
    (fields-to-message
      (concat [":Zeiat" (format "%03d" num) (:name *state* "*")] fields)))
  *state*)

(defmulti message
  (fn [command & _rest]
    (-> command string/upper-case keyword)))

(defmethod message :default
  [command & rest]
  (reply 421 "*" command "Unrecognized command"))

(defn dispatch-message
  [state :- TranslatorState, command :- s/Str, & argv :- [s/Str]]
  (binding [*state* state]
    (try
      (log/trace "dispatching message" command argv)
      (apply message command argv)
      ; TODO we should define some error types of our own like NoSuchChannel,
      ; NoSuchUser, etc, catch those, and return appropriate numerics
      (catch clojure.lang.ArityException e
        (log/warn e "internal error handling command")
        (numeric 461 command "Wrong number of arguments for command")))))

(defn- irctarget
  [who]
  (if (= :me who)
    (:name *state*)
    (:name who)))

(defn cap?
  [cap]
  (contains? (:caps *state*) cap))

(defn- reply-privmsg
  [msg text]
  (let [from (irctarget (:from msg))
        to (irctarget (:to msg))]
    (if (and (:timestamp msg) (cap? "server-time"))
      (reply-tagged-from {"time" (:timestamp msg)} from "PRIVMSG" to text)
      (reply-from from "PRIVMSG" to text))))

(defn privmsg
  [msg]
  (log/trace "privmsg" msg)
  (->> (string/split-lines (:text msg))
       (map string/trim)
       (filter (complement empty?))
       (run! (partial reply-privmsg msg))))
