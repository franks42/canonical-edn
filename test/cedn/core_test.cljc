(ns cedn.core-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cedn.core :as cedn]
            [clojure.edn :as edn])
  #?(:clj (:import [java.time Instant]
                   [java.util Arrays Date UUID])))

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

(deftest canonical-bytes-determinism-test
  (testing "same value produces same bytes"
    (let [v {:a [1 2 #{:x :y}] :b "hello"}
          b1 (cedn/canonical-bytes v)
          b2 (cedn/canonical-bytes v)]
      (is #?(:clj  (Arrays/equals ^bytes b1 ^bytes b2)
             :cljs (= (vec b1) (vec b2)))))))

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
      {:nested [1 #{:a :b} "x"]}
      #?@(:clj  [(Date. 1740571200123)
                 (UUID/fromString "f81d4fae-7dec-11d0-a765-00a0c91e6bf6")]
          :cljs [(js/Date. 1740571200123)
                 (uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6")]))))

#?(:clj
   (deftest inst-round-trip-with-readers-test
     (testing "Nanosecond-precision Instant round-trips with cedn/readers"
       (let [inst (Instant/parse "2025-02-26T12:00:00.123456789Z")
             s1   (cedn/canonical-str inst)
             v2   (edn/read-string {:readers cedn/readers} s1)
             s2   (cedn/canonical-str v2)]
         (is (= s1 s2))
         (is (instance? Instant v2))))
     (testing "Millisecond-precision Date round-trips with cedn/readers"
       (let [d  (Date. 1740571200123)
             s1 (cedn/canonical-str d)
             v2 (edn/read-string {:readers cedn/readers} s1)
             s2 (cedn/canonical-str v2)]
         (is (= s1 s2))))))

#?(:clj
   (deftest uuid-round-trip-with-readers-test
     (testing "Mixed-case UUID canonicalizes to lowercase and round-trips"
       (let [uuid (UUID/fromString "F81D4FAE-7DEC-11D0-A765-00A0C91E6BF6")
             s1   (cedn/canonical-str uuid)
             v2   (edn/read-string {:readers cedn/readers} s1)
             s2   (cedn/canonical-str v2)]
         (is (= s1 s2))
         (is (= s1 "#uuid \"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\""))))))

#?(:cljs
   (deftest inst-round-trip-cljs-test
     (testing "ms-precision js/Date round-trips through canonical-str"
       (let [d  (js/Date. 1740571200123)
             s1 (cedn/canonical-str d)
             v2 (edn/read-string s1)
             s2 (cedn/canonical-str v2)]
         (is (= s1 s2))
         (is (= s1 "#inst \"2025-02-26T12:00:00.123000000Z\""))))))

#?(:cljs
   (deftest uuid-round-trip-cljs-test
     (testing "UUID canonicalizes to lowercase and round-trips"
       (let [u  (uuid "F81D4FAE-7DEC-11D0-A765-00A0C91E6BF6")
             s1 (cedn/canonical-str u)
             v2 (edn/read-string s1)
             s2 (cedn/canonical-str v2)]
         (is (= s1 s2))
         (is (= s1 "#uuid \"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\""))))))

;; --- version ---

(deftest version-test
  (is (= "1.2.0" cedn/version)))

;; --- #bytes round-trip ---

(deftest bytes-round-trip-test
  (testing "byte array round-trips through canonical-str + readers"
    (let [bs #?(:clj (byte-array [(unchecked-byte 0xde) (unchecked-byte 0xad)
                                   (unchecked-byte 0xbe) (unchecked-byte 0xef)])
                :cljs (js/Uint8Array. #js [0xde 0xad 0xbe 0xef]))
          s1 (cedn/canonical-str bs)
          _  (is (= "#bytes \"deadbeef\"" s1))
          v2 (edn/read-string {:readers cedn/readers} s1)
          s2 (cedn/canonical-str v2)]
      (is (= s1 s2))
      #?(:clj  (is (java.util.Arrays/equals ^bytes bs ^bytes v2))
         :cljs (is (= (vec bs) (vec v2)))))))

(deftest bytes-canonical-test
  (is (cedn/canonical? "#bytes \"deadbeef\"")))

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
    ;; SHA-256 is nil on CLJS (no built-in crypto)
    #?(:clj
       (do
         (is (string? (:sha-256 result)))
         (is (= 64 (count (:sha-256 result))))))
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
  (is (not (cedn/canonical? "not valid edn %%")))
  ;; Nanosecond #inst only round-trips on JVM (JS Date has ms precision)
  #?(:clj  (is (cedn/canonical? "#inst \"2025-02-26T12:00:00.123456789Z\""))
     :cljs (is (cedn/canonical? "#inst \"2025-02-26T12:00:00.123000000Z\"")))
  (is (cedn/canonical? "#uuid \"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\"")))

;; --- rank ---

(deftest rank-reexport-test
  (is (= [nil true 3.14 42 "str" :kw '(2) [1] #{} {}]
         (sort cedn/rank
               [:kw "str" true 42 nil [1] '(2) #{} {} 3.14]))))
