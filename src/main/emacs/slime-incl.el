(add-to-list 'slime-lisp-implementations
              '(trixx-core ("@bin.dir@/trixx-core-repl")
                         :init swank-clojure-init
                         :init-function krb-swank-clojure-init)

               t)
