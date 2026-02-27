(ns jar-smoke-test
  "Smoke test that exercises the CEDN public API from the installed JAR.
   Run via: bb test:jar  (or see bb.edn for the long-form command).
   This file is NOT on the normal src/test classpath — it is invoked
   by a separate clojure process whose only dep is the Maven artifact."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cedn.core :as cedn]))

(deftest canonical-str-test
  (testing "map key sorting"
    (is (= "{:a 1 :b 2}" (cedn/canonical-str {:b 2 :a 1}))))
  (testing "nested structures"
    (is (= "{:a [1 2 3] :b {:c true}}"
           (cedn/canonical-str {:b {:c true} :a [1 2 3]}))))
  (testing "nil"
    (is (= "nil" (cedn/canonical-str nil))))
  (testing "string escaping"
    (is (= "\"hello\\nworld\"" (cedn/canonical-str "hello\nworld"))))
  (testing "set sorting"
    (is (= "#{1 2 3}" (cedn/canonical-str #{3 1 2}))))
  (testing "keyword"
    (is (= ":foo" (cedn/canonical-str :foo))))
  (testing "double formatting"
    (is (= "1.5" (cedn/canonical-str 1.5)))
    (is (= "0.0" (cedn/canonical-str 0.0)))))

(deftest canonical-bytes-test
  (testing "bytes match UTF-8 encoding of canonical string"
    (let [v {:z 0 :a 1}
          s (cedn/canonical-str v)
          bs (cedn/canonical-bytes v)]
      (is (= (seq (.getBytes ^String s "UTF-8")) (seq bs))))))

(deftest valid?-test
  (testing "CEDN-P valid values"
    (is (cedn/valid? 42))
    (is (cedn/valid? "hello"))
    (is (cedn/valid? {:a [1 2 3]}))
    (is (cedn/valid? true))
    (is (cedn/valid? nil)))
  (testing "CEDN-P invalid values"
    (is (not (cedn/valid? 22/7)))))

(deftest canonical?-test
  (testing "already canonical"
    (is (cedn/canonical? "{:a 1 :b 2}")))
  (testing "not canonical (wrong key order)"
    (is (not (cedn/canonical? "{:b 2 :a 1}")))))

(deftest inspect-test
  (testing "inspect returns expected keys"
    (let [result (cedn/inspect {:a 1})]
      (is (= :ok (:status result)))
      (is (string? (:canonical result)))
      (is (string? (:sha-256 result))))))

(deftest rank-test
  (testing "total ordering"
    (is (= [nil true 3 "b" :a]
           (sort cedn/rank [3 :a nil true "b"])))))

(deftest readers-round-trip-test
  (testing "#inst round-trip preserves nanoseconds"
    (let [inst (java.time.Instant/parse "2025-02-26T12:00:00.123456789Z")
          s (cedn/canonical-str inst)
          v (clojure.edn/read-string {:readers cedn/readers} s)]
      (is (= inst v))))
  (testing "#uuid round-trip"
    (let [u (java.util.UUID/randomUUID)
          s (cedn/canonical-str u)
          v (clojure.edn/read-string {:readers cedn/readers} s)]
      (is (= u v)))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
