(ns cedn.core-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cedn.core :as cedn]
            [clojure.edn :as edn])
  #?(:clj (:import [java.util Arrays])))

;; --- canonical-str ---

(deftest canonical-str-test
  (are [input expected]
       (= expected (cedn/canonical-str input))
    nil          "nil"
    true         "true"
    false        "false"
    42           "42"
    3.14         "3.14"
    ""           "\"\""
    "hello"      "\"hello\""
    :foo         ":foo"
    :ns/bar      ":ns/bar"
    '()          "()"
    [1 2 3]      "[1 2 3]"
    #{3 1 2}     "#{1 2 3}"
    {:b 2 :a 1}  "{:a 1 :b 2}"))

;; --- canonical-bytes ---

(deftest canonical-bytes-test
  (testing "returns UTF-8 byte array"
    (let [bs (cedn/canonical-bytes 42)]
      (is (= "42" #?(:clj (String. ^bytes bs "UTF-8")
                     :cljs (.decode (js/TextDecoder.) bs)))))))

#?(:clj
   (deftest canonical-bytes-determinism-test
     (testing "same value produces same bytes"
       (let [v {:a [1 2 #{:x :y}] :b "hello"}
             b1 (cedn/canonical-bytes v)
             b2 (cedn/canonical-bytes v)]
         (is (Arrays/equals ^bytes b1 ^bytes b2))))))

;; --- Round-trip stability ---

(deftest round-trip-test
  (testing "canonical-str is stable across read/canonicalize cycles"
    (are [v]
         (let [s1 (cedn/canonical-str v)
               v2 (edn/read-string s1)
               s2 (cedn/canonical-str v2)]
           (= s1 s2))
      nil
      true
      42
      3.14
      "hello"
      :foo
      :ns/bar
      '(1 2 3)
      [1 2 3]
      #{1 2 3}
      {:a 1 :b 2}
      {:nested [1 #{:a :b} "x"]})))

;; --- valid? ---

(deftest valid-test
  (is (cedn/valid? {:a 1}))
  (is (cedn/valid? nil))
  (is (not (cedn/valid? ##NaN))))

;; --- explain ---

(deftest explain-test
  (is (nil? (cedn/explain {:a 1})))
  (is (some? (cedn/explain ##NaN))))

;; --- assert! ---

(deftest assert-test
  (is (nil? (cedn/assert! {:a 1})))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (cedn/assert! ##NaN))))

;; --- inspect ---

(deftest inspect-ok-test
  (let [result (cedn/inspect {:a 1 :b 2})]
    (is (= :ok (:status result)))
    (is (= "{:a 1 :b 2}" (:canonical result)))
    (is (some? (:bytes result)))
    (is (string? (:sha-256 result)))
    (is (= 64 (count (:sha-256 result))))
    (is (nil? (:errors result)))))

(deftest inspect-error-test
  (let [result (cedn/inspect ##NaN)]
    (is (= :error (:status result)))
    (is (nil? (:canonical result)))
    (is (some? (:errors result)))))

;; --- canonical? ---

(deftest canonical-test
  (is (cedn/canonical? "nil"))
  (is (cedn/canonical? "42"))
  (is (cedn/canonical? "{:a 1 :b 2}"))
  (is (not (cedn/canonical? "{:b 2 :a 1}")))
  (is (not (cedn/canonical? "not valid edn %%"))))

;; --- rank ---

(deftest rank-reexport-test
  (is (= [nil true 3.14 42 "str" :kw '(2) [1] #{} {}]
         (sort cedn/rank
               [:kw "str" true 42 nil [1] '(2) #{} {} 3.14]))))
