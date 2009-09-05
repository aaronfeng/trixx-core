(ns com.leftrightfold.trixx.main
  (:gen-class))

(defn -main [& args]
  (prn "Welcome to Trixx Repl")
  (clojure.lang.Repl/main (into-array String args)))
