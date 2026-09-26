(ns cedn.test-util
  "Helpers shared by the test namespaces.")

#?(:clj
   (defn on-jvm
     "Evaluate form on the JVM and return its value; nil on babashka. For
     values bb cannot build: classes it lacks (java.sql.Time), constructors
     its native image cannot call reflectively (java.sql.Timestamp), and
     proxies. Through eval, so bb never resolves the class names."
     [form]
     (when-not (System/getProperty "babashka.version")
       (eval form))))

#?(:clj
   (defn sql-time
     "A java.sql.Time, or nil on babashka (which lacks the class)."
     []
     (on-jvm '(java.sql.Time. 0))))
