(ns com.leftrightfold.trixx.log-utils
 (:import [org.apache.commons.logging Log LogFactory]))

(defn get-logger [context]
 (LogFactory/getLog (str context)))

(defmacro trace [& body] `(if (.isTraceEnabled ~'*log*) (.trace~'*log* (str ~@body))))
(defmacro debug [& body] `(if (.isDebugEnabled ~'*log*) (.debug~'*log* (str ~@body))))
(defmacro info  [& body] `(if (.isInfoEnabled ~'*log*)  (.info~'*log* (str ~@body))))
(defmacro warn  [& body] `(if (.isWarnEnabled ~'*log*)  (.warn~'*log* (str ~@body))))
(defmacro error [& body] `(if (.isErrorEnabled ~'*log*) (.error~'*log* (str ~@body))))
(defmacro fatal [& body] `(if (.isFatalEnabled ~'*log*) (.fatal~'*log* (str ~@body))))

(defmacro tracef [fmt & args] `(trace (format ~fmt ~@args)))
(defmacro debugf [fmt & args] `(debug (format ~fmt ~@args)))
(defmacro infof  [fmt & args] `(info (format ~fmt ~@args)))
(defmacro warnf  [fmt & args] `(warn (format ~fmt ~@args)))
(defmacro errorf [fmt & args] `(error (format ~fmt ~@args)))
(defmacro fatalf [fmt & args] `(fatal (format ~fmt ~@args)))
