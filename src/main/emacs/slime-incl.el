(add-to-list 
  'slime-lisp-implementations
  '(trixx-repl 
     ("@project.dir@/bin/trixx-repl")
     :init swank-clojure-init
     :init-function krb-swank-clojure-init) t)
