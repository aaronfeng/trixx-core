(ns com.leftrightfold.trixx.main
  (:require [com.leftrightfold.trixx.core :as trixx])
  (:gen-class))

(defn -main [& args]
  (prn "Welcome to Trixx Repl")
  (prn "Rabbit status:")
  (prn (trixx/status))
  (clojure.lang.Repl/main (into-array String args)))
