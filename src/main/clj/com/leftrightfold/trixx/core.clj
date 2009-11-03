;;  The contents of this file are subject to the Mozilla Public License
;;  Version 1.1 (the "License"); you may not use this file except in
;;  compliance with the License. You may obtain a copy of the License at
;;  http://www.mozilla.org/MPL/
;;
;;  Software distributed under the License is distributed on an "AS IS"
;;  basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
;;  License for the specific language governing rights and limitations
;;  under the License.
;;
;;  The Original Code is Trixx.
;;
;;  The Initial Developer of the Original Code is Aaron Feng
;;  Portions created by Aaron Feng are Copyright (C) Aaron Feng 2009.
;;
;;  All Rights Reserved.
;;
;;  Contributor(s): ______________________________________.


(ns com.leftrightfold.trixx.core
  (:import (com.rabbitmq.client ConnectionParameters ConnectionFactory
                                MessageProperties QueueingConsumer Connection)
           (com.ericsson.otp.erlang OtpSelf OtpPeer OtpConnection
                                    OtpErlangObject OtpErlangBinary OtpErlangLong
                                    OtpErlangList OtpErlangAtom OtpErlangTuple
                                    OtpErlangPid
                                    OtpNode OtpMbox))
  (:require [clojure.contrib.seq-utils  :as su]
            [clojure.contrib.str-utils  :as str]
            [clojure.contrib.java-utils :as ju]
            [com.leftrightfold.trixx.log-utils :as log]))

(def *log* (log/get-logger *ns*))

(def *node-name*       (atom "trixx"))
(def *cookie*          (atom (ju/get-system-property "com.leftrightfold.trixx.cookie")))
(def *server*          (atom (ju/get-system-property "com.leftrightfold.trixx.rabbit-server"   "localhost")))
(def *rabbit-instance* (atom (ju/get-system-property "com.leftrightfold.trixx.rabbit-instance" "rabbit")))
(def *random*          (atom (java.util.Random.)))
(def *message-properties* {:minimal-basic MessageProperties/MINIMAL_BASIC
                           :persistent-text-plain MessageProperties/PERSISTENT_TEXT_PLAIN})
(def *delivery* (atom nil))

(defn- randomNumber []
  (.. @*random* nextInt))

(defn- #^String load-cookie
  "Set the Erlang *cookie* from the contents of a local file (as a string)."
  [#^String cookie-file]
  (reset! *cookie* (str/chop (slurp cookie-file))))

(defn- #^String load-default-cookie-file
  "Set the Erlang *cookie* from the file $HOME/.erlang.cookie (the default location)."
  []
  (load-cookie (str (ju/get-system-property "user.home") "/.erlang.cookie")))

(defn set-erlang-cookie! []
  ;; Try to set the cookie, using various fallbacks
  (let [cookie (ju/get-system-property "com.leftrightfold.trixx.cookie")]
    (if cookie
      (do
        (reset! *cookie* cookie)
        (log/infof "set *cookie*=%s via system property (com.leftrightfold.trixx.cookie)" @*cookie*))))

  (if (not @*cookie*)
    (let [file (str (ju/get-system-property "user.home") "/.erlang.cookie")
          f (ju/as-file file)]
      (if (.exists f)
        (do
          (log/infof "set *cookie*=%s via file: %s" @*cookie* file)
          (load-cookie file))))))

(defn clear-cookie!
  "Clears the erlang cookie, useful for REPL and testing."
  []
  (reset! *cookie* nil))

(defn init* [server cookie]
  (reset! *server* server)
  (reset! *cookie* cookie))

(defn init []
  (set-erlang-cookie!)
  (if (not @*cookie*)
    (throw (RuntimeException. (format "Trixx Initialization Error, Erlang cookie not set, unable to continue.  Tried system property com.leftrightfold.trixx.cookie and the file $HOME/.erlang.cookie"))))
  (log/infof "*cookie*=%s"          @*cookie*)
  (log/infof "*server*=%s"          @*server*)
  (log/infof "*rabbit-instance*=%s" @*rabbit-instance*))


(defstruct exchange-info :name :vhost :type :durable :auto-delete)

(defstruct queue-info :name :vhost :durable :auto-delete
           :messages-ready :messages-unacknowledged
	         :messages-uncommitted :messages :acks-uncommitted
           :consumers :transactions :memory)

(defstruct queue-binding :vhost :exchange :queue :routing-key)
(defstruct connection-info :pid :address :port :peer-address :peer-port
           :recv-oct :recv-cnt :send-oct :send-cnt :send-pend
           :state :channels :user :vhost :timeout :frame-max)

(defstruct user :name :vhost :config :write :read)

(defstruct status-info :running-applications :nodes :running-nodes)

(defstruct vhost :name)

;;; erlang helper
(defmulti  value class)
(defmethod value OtpErlangBinary [o] (String. (.binaryValue o)))
(defmethod value OtpErlangLong   [o] (Integer/parseInt (str o)))
(defmethod value OtpErlangAtom   [o] (.atomValue o))
(defmethod value :default        [o] (str o))
(defmethod value nil             [o] "")

(defmulti  as-seq class)
(defmethod as-seq OtpErlangList  [o] (seq (.elements o)))
(defmethod as-seq OtpErlangTuple [o] (seq (.elements o)))

;;; helper methods
(defn _1st  [item]    (.elementAt item 0))
(defn _2nd  [item]    (.elementAt item 1))
(defn _3rd  [item]    (.elementAt item 2))
(defn _4th  [item]    (.elementAt item 3))
(defn _5th  [item]    (.elementAt item 4))
(defn _6th  [item]    (.elementAt item 5))
(defn _7th  [item]    (.elementAt item 6))
(defn _8th  [item]    (.elementAt item 7))
(defn _9th  [item]    (.elementAt item 8))
(defn _10th [item]    (.elementAt item 9))
(defn _nth  [item i]  (.elementAt item i))
(defn _boolean [b]    (Boolean/parseBoolean b))

(defn- otp->pull
  "Most OTP types are nested strctures, this helper aids in pulling
them apart.  The path is applied as a nested series of calls to
.elementAt on the item."
  [item & path]
  (loop [item item
         [pos & path] path]
    (if pos
      (recur (.elementAt item pos) path)
      item)))

(defn- otp->pullv
  "Most OTP types are nested strctures, this helper aids in pulling
them apart.  The path is applied as a nested series of calls to
.elementAt on the item.  The final value pulled is then passed to the
`value' function to coerce it into a String, or Integer - see `value'."
  [item & path]
  (loop [item item
         [pos & path] path]
    (if pos
      (recur (.elementAt item pos) path)
      (value item))))

;;; utility functions
(defn- #^Connection create-conn
  "Create a connection from a fresh, discarded, connection factory."
  [#^String server #^ConnectionParameters params]
  (let [factory (ConnectionFactory. params)] factory
       (.newConnection factory server)))

(defn- create-conn-params
  "Create a com.rabbitmq.client.ConnectionParameters instance, with the given vhost
user and password set on the instance."
  [#^String vhost #^String user #^String password]
  (doto (ConnectionParameters.)
    (.setVirtualHost vhost)
    (.setUsername    user)
    (.setPassword    password)))

(defmacro with-consumer [server vhost queue no-ack user password]
  `(with-open [connection# (create-conn ~server (create-conn-params ~vhost ~user ~password))
               channel# (.createChannel connection#)]
     (let [consumer# (QueueingConsumer. channel#)]
       (.basicConsume channel# ~queue ~no-ack consumer#)
       (reset! *delivery* (.nextDelivery consumer#))
       (if (not ~no-ack) (.basicAck channel# (.. @*delivery* getEnvelope getDeliveryTag) false)))))

(defmacro with-channel
  "Executes the given form (as if a doto) in the context of a fresh connection and channgel."
  [server vhost user password f]
  `(with-open [connection# (create-conn ~server (create-conn-params ~vhost ~user ~password))
	       channel# (.createChannel connection#)]
     (doto channel# ~f)))

(defn- #^OtpErlangList create-args
  "Helper to construct and return an OtpErlangList suitable for the var-args of rpc calls."
  [& args]
  (OtpErlangList.
   (into-array OtpErlangObject
	       (map (fn [x]
                      (if (or (instance? OtpErlangBinary x)
                              (instance? OtpErlangTuple x)
                              (instance? OtpErlangAtom x)
                              (instance? OtpErlangPid x))
                        x
                        (OtpErlangBinary. (.getBytes (str x)))))
		    args))))

;; TODO : needs to handle the fact the result returned may not be a OtpErlangList
(defn- execute
  "RPC Call into the erlang node."
  ([module function args]
     (let [self (OtpSelf. (str @*node-name* "-" (randomNumber)) @*cookie*)
           peer (OtpPeer. @*rabbit-instance*)]
       (with-open [conn (.connect self peer)]
         (log/infof "[execute] module=%s function=%s args=%s" module function args)
         (.sendRPC conn module function (apply create-args args))
         (.receiveRPC conn)))))

(defn- execute->seq
  "Execute the rpc call, coercing the result into a sequence (must be an OtpErlangList or OtpErlangTuple)."
  [module function args]
  (as-seq (execute module function args)))

(defn- is-successful?
  [f]
  (try (f) true
       (catch Throwable e
         (log/error e)
         (.printStackTrace e)
         false)))

;;; via erlang
(defn status
  []
  (let [result (execute "rabbit" "status" [])]
    (let [running-apps (map (fn [a]
                              {:service     (otp->pullv a 0)
                               :description (otp->pullv a 1)
                               :version     (otp->pullv a 2)})
                            (as-seq (_2nd (_1st result))))
          nodes (map value (as-seq (_2nd (_2nd result))))
          running-nodes (map value (as-seq (_2nd (_3rd result))))]
      (struct status-info running-apps nodes running-nodes))))

(defn list-exchanges
  "List all the exchanges for a given virtual host"
  [#^String vhost]
  (map #(let [vhost       (otp->pullv % 0 1 1)
              name        (otp->pullv % 0 1 3)
              type        (otp->pullv % 1 1)
              durable     (_boolean (otp->pullv % 2 1))
              auto-delete (_boolean (otp->pullv % 3 1))
              ;; didn't translate {arguments, []}
              ]
          (struct exchange-info name vhost type durable auto-delete))
       (execute->seq "rabbit_exchange" "info_all" [vhost])))

(defn list-queues
  "List all queues for a given virtual host."
  [#^String vhost]
  (let [queues (execute->seq "rabbit_amqqueue" "info_all" [vhost])]
    (map #(let [vhost                   (otp->pullv % 0 1 1)
                name                    (otp->pullv % 0 1 3)
                durable                 (otp->pullv % 1 1)
                auto-delete             (otp->pullv % 2 1)
                ;;didn't translate {arguments, []}
                ;;                 {pid, #Pid<....>}
                messages-ready          (otp->pullv % 5 1)
                messages-unacknowledged (otp->pullv % 6 1)
                messages-uncommitted    (otp->pullv % 7 1)
                messages                (otp->pullv % 8 1)
                ack-uncommitted         (otp->pullv % 9 1)
                consumers               (otp->pullv % 10 1)
                transactions            (otp->pullv % 11 1)
                memory                  (otp->pullv % 12 1)]
            (struct queue-info name vhost durable auto-delete messages-ready messages-unacknowledged
                    messages-uncommitted messages ack-uncommitted consumers transactions memory))
         queues)))

(defn list-bindings
  "List the bindings at a virtual host."
  [#^String vhost]
  (let [result (execute->seq "rabbit_exchange" "list_bindings" [vhost])]
    (map #(let [vhost       (otp->pullv % 0 1)
                exchange    (otp->pullv % 0 3)
                queue       (otp->pullv % 1 3)
                routing-key (otp->pullv % 2)
                ;; [] not translated
                ]
            (struct queue-binding vhost exchange queue routing-key))
         result)))

(defn stop-app
  "Stop the rabbit application in the connected erlang node."
  []
  (is-successful? #(execute "rabbit" "stop" [])))

(defn start-app
  "Start the rabbit application in the connected erlang node."
  []
  (is-successful? #(execute "rabbit" "start" [])))

(defn reset
  "Reset mnesia in the connected erlang node."
  []
  (is-successful? #(execute "rabbit_mnesia" "reset" [])))

(defn add-vhost
  "Add a vhost in the Rabbit Server."
  [#^String vhost]
  (is-successful? #(execute "rabbit_access_control" "add_vhost" [vhost])))

(defn delete-vhost
  "Delete a vhost from the Rabbit Server."
  [#^String vhost]
  (is-successful? #(execute "rabbit_access_control" "delete_vhost" [vhost])))

(defn list-vhosts
  "Returns a list of the virtual hosts in the rabbit server."
  []
  (let [result (execute->seq "rabbit_access_control" "list_vhosts" [])]
    (map (fn [h] (struct vhost (value h))) result)))

(defn- execute-list-permissions->seq
  "Taget is either user name or host name"
  [target command]
  (execute->seq "rabbit_access_control" command [target]))

(defn list-vhost-permissions
  [#^String vhost]
  (map #(struct user
               (otp->pullv % 0)
               vhost
               (otp->pullv % 1)
               (otp->pullv % 2)
               (otp->pullv % 3))
       (execute-list-permissions->seq vhost "list_vhost_permissions")))

(defn list-user-permissions
  [u]
  (remove nil?
          (map #(if (and (instance? OtpErlangTuple %)
                         (= (count (.elements %)) 4))
                    (struct user
                            u
                            (otp->pullv % 0)
                            (otp->pullv % 1)
                            (otp->pullv % 2)
                            (otp->pullv % 3)))
                (execute-list-permissions->seq u "list_user_permissions"))))

(defn list-users
  []
  (su/flatten
    (map #(list-user-permissions (value %))
         (execute->seq "rabbit_access_control" "list_users" []))))

(defn user-exists? [#^String user]
    (loop [[uname & users] (map :name (list-users))]
          (cond (not uname)     false
                (= user uname)  true
                :else           (recur users))))

;; needs to handle user already exists error
(defn add-user
  [#^String name #^String password]
  (is-successful? #(execute "rabbit_access_control" "add_user" [name password])))

(defn change-password
  [#^String name #^String password]
  (is-successful? #(execute "rabbit_access_control" "change_password" [name password])))

(defn delete-user
  [#^String name]
  (is-successful? #(execute "rabbit_access_control" "delete_user" [name])))

(defn- parse-ip
  [addr_tuple]
  (let [part1 (_1st addr_tuple)
	part2 (_2nd addr_tuple)
	part3 (_3rd addr_tuple)
	part4 (_4th addr_tuple)]
    (str part1 "." part2 "." part3 "." part4)))

(defn list-connections
  []
  ;;; pid is an OtpErlangPid not String
  (map #(let [pid           (otp->pull % 0 1)
              address       (parse-ip (otp->pull % 1 1))
              port          (otp->pullv % 2 1)
              peer_address  (parse-ip (otp->pull % 3 1))
              peer_port     (otp->pullv % 4 1)
              recv-oct      (otp->pullv % 5 1)
              recv-cnt      (otp->pullv % 6 1)
              send-oct      (otp->pullv % 7 1)
              send-cnt      (otp->pullv % 8 1)
              send-period   (otp->pullv % 9 1)
              state         (otp->pullv % 10 1)
              channels      (otp->pullv % 11 1)
              user          (otp->pullv % 12 1)
              vhost         (otp->pullv % 13 1)
              timeout       (otp->pullv % 14 1)
              frame-max     (otp->pullv % 15 1)]
          (struct connection-info pid address port peer_address
                  peer_port recv-oct recv-cnt
                  send-oct send-cnt send-period
                  state channels user vhost
                  timeout frame-max))
       (execute->seq "rabbit_networking" "connection_info_all" [])))

(defn- exit [pid]
  (is-successful? #(execute "erlang" "exit" [pid (OtpErlangAtom. "kill")])))

(defn kill-connections
  "Kill all user connections for a given vhost"
  [vhost user]
  (let [successful (atom true)]
    (doseq [conn (filter #(and (= vhost (:vhost %))
                               (= user (:user %))) (list-connections))]
      (reset! successful (and successful (exit (:pid conn)))))
    @successful))

(defn set-permissions
  [#^String user #^String vhost {config_regex :config write_regex :write read_regex :read}]
  (is-successful? #(execute "rabbit_access_control" "set_permissions"
                            [user vhost config_regex write_regex read_regex])))

(defn clear-permissions
  [#^String user #^String vhost]
  (is-successful? #(execute "rabbit_access_control" "set_permissions" [user vhost "^$" "^$" "^$"])))

;;; via protocol
(defn add-queue
  [#^String vhost #^String user #^String password #^String queue-name #^Boolean durable]
  (is-successful? #(with-channel @*server* vhost user password (.queueDeclare queue-name durable))))

(defn delete-queue
  [#^String vhost #^String user #^String password #^String queue-name]
  (is-successful? #(with-channel @*server* vhost user password (.queueDelete queue-name))))

(defn add-exchange
  [#^String vhost #^String user #^String password #^String name #^String type #^Boolean durable]
  (is-successful? #(with-channel @*server* vhost user password (.exchangeDeclare name type durable))))

(defn delete-exchange
  [#^String vhost #^String user #^String password #^String name]
  (is-successful? #(with-channel @*server* vhost user password (.exchangeDelete name))))

(defn add-binding
  [#^String vhost #^String user #^String password #^String queue #^String exchange #^String routing-key]
  (is-successful? #(with-channel @*server* vhost user password (.queueBind queue exchange routing-key))))

(defn basic-publish
  [#^String user #^String password #^String vhost #^String exchange #^String routing-key #^MessageProperties properties #^"[B" bytes]
  (is-successful? #(with-channel @*server* vhost user password (.basicPublish exchange routing-key properties bytes))))

(defn- is-user
  [tuple]
  (= (otp->pullv tuple 0)
     "user"))

(defn valid-user
  [#^String name #^String password]
  (is-user (execute
            "rabbit_access_control" "check_login"
            [(OtpErlangBinary. (.getBytes "PLAIN"))
             (OtpErlangBinary. (.getBytes (str name "\u0000" password)))])))