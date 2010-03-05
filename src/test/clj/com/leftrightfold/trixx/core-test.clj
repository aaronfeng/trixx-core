(ns com.leftrightfold.trixx.core-test
  (:use clojure.contrib.test-is)
  (:require
   [com.leftrightfold.trixx.core      :as core]
   [clojure.contrib.java-utils        :as ju]
   [com.leftrightfold.trixx.log-utils :as log]))

(def *log* (log/get-logger *ns*))

(defmacro with-shadowed-system-properties [props-map & body]
  `(let [prop-names# (keys ~props-map)
         sys-props#  (System/getProperties)
         orig-vals#  (reduce (fn [resmap# prop-name#]
                               (let [orig-val# (.get sys-props# prop-name#)]
                                 (prn (format "shadowing: %s %s => %s"
                                              prop-name# orig-val# (~props-map prop-name#)))
                                 (.put sys-props# prop-name# (~props-map prop-name#))
                                 (assoc resmap# prop-name# orig-val#)))
                             {}
                             prop-names#)
         res# (do ~@body)]
     (prn (format "prop-names: %s" prop-names#))
     (prn (format "orig-vals:  %s" orig-vals#))
     (prn (format "orig-vals.keys:  %s" (keys orig-vals#)))
     (doseq [prop-name# (keys orig-vals#)]
       (prn (format "resetting: %s" prop-name#))
       (let [val# (orig-vals# prop-name#)]
         (prn (format "resetting: %s => %s" prop-name# val#))
         (if (nil? val#)
           (.remove sys-props# prop-name#)
           (.put sys-props# prop-name# val#))))
     res#))


(deftest determine-amqp-server-hostname
  (let [prop-val (with-shadowed-system-properties
                     {core/*erlang-amqp-server-property* "my.server"}
                   (core/get-amqp-server))]
    (is (= "localhost" (core/get-amqp-server)))
    (is (= "my.server" prop-val))
    (binding [core/getenv {"TRIXX_AMQP_SERVER" "my.server"}]
      (is (= "my.server" (core/get-amqp-server))))))

;; ok, this is more complex to unit test since it actually attempts a
;; connection to the otp instance...

;; (deftest determine-otp-nodename
;;   (let [prop-val (with-shadowed-system-properties
;;                      {core/*erlang-amqp-server-property* "me@nodename"}
;;                    (core/get-default-otp-nodename))]
;;     (is (= "rabbit@localhost" (core/get-default-otp-nodename)))
;;     (is (= "me@nodename" prop-val))
;;     (binding [core/getenv {"NODENAME"           "my@nodename"
;;                            "RABBITMQ_NODENAME"  "my@nodeame"
;;                            "TRIXX_OTP_NODENAME" "my@nodename"}]
;;       (is (= "me@nodename" (core/get-default-otp-nodename))))))

(deftest determine-erlang-cookie
  (let [prop-val (with-shadowed-system-properties
                     {core/*erlang-cookie-property* "my.cookie.prop"}
                   (core/get-erlang-cookie))]
    (is (= "my.cookie.prop" prop-val))
    (binding [core/getenv {"TRIXX_ERLANG_COOKIE" "my.cookie.env"}]
      (is (= "my.cookie.env" (core/get-erlang-cookie))))))

(deftest load-cookie-from-file
(let [file-tester-fn (fn [expected-value file-contents]
      (binding [core/exists? (fn [fname] true)
                slurp (fn [fname] file-contents)]
        (is (= expected-value (core/load-cookie-from-file "some/file.path")))))]
  (file-tester-fn "7HE3rL4n6K00K13" "7HE3rL4n6K00K13")
  (file-tester-fn "7HE3rL4n6K00K13" "7HE3rL4n6K00K13\n")
  (file-tester-fn "7HE3rL4n6K00K13" "7HE3rL4n6K00K13\nother stuff")
  (file-tester-fn "7HE3rL4n6K00K13" "7HE3rL4n6K00K13\r\nother stuff")
  (file-tester-fn "7HE3rL4n6K00K13" "7HE3rL4n6K00K13\r\nother stuff")))


(comment
  (run-tests)

  (System/getProperty core/*erlang-amqp-server-property* )
  (System/setProperty core/*erlang-amqp-server-property* "foo")
  (.remove (System/getProperties) core/*erlang-amqp-server-property*)
  (.get (System/getProperties) core/*erlang-amqp-server-property*)

)