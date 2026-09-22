(ns cedn.order-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cedn.order :as order]))

(deftest type-priority-test
  (testing "type priorities"
    (are [v expected]
         (= expected (order/type-priority v))
      nil      0
      false    1
      true     1
      42       2
      3.14     2
      "hello"  3
      :foo     4
      'bar     5
      '()      6
      [1]      7
      #{}      8
      {}       9)))

(deftest rank-nil-test
  (is (zero? (order/rank nil nil))))

(deftest rank-boolean-test
  (is (neg? (order/rank false true)))
  (is (pos? (order/rank true false)))
  (is (zero? (order/rank true true))))

(deftest rank-numbers-test
  (testing "integers by value"
    (is (neg? (order/rank 1 2)))
    (is (pos? (order/rank 5 3))))
  (testing "doubles by value"
    (is (neg? (order/rank 1.0 2.0)))
    (is (pos? (order/rank 5.0 3.0))))
  #?(:clj
     (testing "cross-type: same value, int < double"
       (is (neg? (order/rank 1 1.0)))
       (is (pos? (order/rank 1.0 1)))))
  (testing "cross-type: different values"
    (is (neg? (order/rank 3.14 42)))
    (is (neg? (order/rank 1 2.5)))))

(deftest rank-strings-test
  (is (neg? (order/rank "a" "b")))
  (is (pos? (order/rank "b" "a")))
  (is (zero? (order/rank "abc" "abc"))))

(deftest rank-strings-codepoint-order-test
  (testing "astral-plane: codepoint order, not UTF-16 code unit order"
    ;; U+FFFF (65535) < U+10000 (65536) in codepoint order
    ;; but U+10000 encodes as surrogate pair D800 DC00 in UTF-16,
    ;; so UTF-16 code unit order would put U+10000 before U+FFFF
    (is (neg? (order/rank "\uFFFF" "\uD800\uDC00"))
        "U+FFFF (65535) < U+10000 (65536) in codepoint order")
    ;; U+1F600 (128512) > U+10000 (65536)
    (is (pos? (order/rank "\uD83D\uDE00" "\uD800\uDC00"))
        "U+1F600 (128512) > U+10000 (65536) in codepoint order")
    ;; prefix test: "a" < "a𐀀"
    (is (neg? (order/rank "a" "a\uD800\uDC00"))
        "prefix: shorter string sorts first")))

(deftest rank-keywords-test
  (testing "unqualified"
    (is (neg? (order/rank :a :b)))
    (is (pos? (order/rank :z :a))))
  (testing "nil namespace < any namespace"
    (is (neg? (order/rank :foo :ns/foo))))
  (testing "qualified by namespace then name"
    (is (neg? (order/rank :a/z :b/a)))
    (is (neg? (order/rank :ns/a :ns/b)))))

(deftest rank-keywords-codepoint-order-test
  (testing "astral-plane characters in keyword names use codepoint order"
    (is (neg? (order/rank (keyword "\uFFFF") (keyword "\uD800\uDC00")))
        "keyword with U+FFFF < keyword with U+10000 in codepoint order")))

(deftest rank-symbols-test
  (is (neg? (order/rank 'a 'b)))
  (is (neg? (order/rank 'foo 'ns/foo))))

(deftest rank-sequential-test
  (testing "element-by-element"
    (is (neg? (order/rank [1 2] [1 3])))
    (is (pos? (order/rank [2 1] [1 1]))))
  (testing "shorter first"
    (is (neg? (order/rank [1] [1 2])))
    (is (pos? (order/rank [1 2] [1])))))

(deftest rank-sets-test
  (testing "cardinality first"
    (is (neg? (order/rank #{} #{1})))
    (is (neg? (order/rank #{1} #{1 2}))))
  (testing "same cardinality: pairwise"
    (is (neg? (order/rank #{1} #{2})))))

(deftest rank-maps-test
  (testing "entry count first"
    (is (neg? (order/rank {} {:a 1}))))
  (testing "same count: compare keys"
    (is (neg? (order/rank {:a 1} {:b 1}))))
  (testing "same keys: compare values"
    (is (neg? (order/rank {:a 1} {:a 2})))))

(deftest rank-bytes-test
  (testing "byte array type priority is 10 (tagged)"
    (is (= 10 (order/type-priority #?(:clj (byte-array [1])
                                      :cljs (js/Uint8Array. #js [1]))))))
  (testing "bytes sorts after map"
    (is (pos? (order/rank #?(:clj (byte-array [1]) :cljs (js/Uint8Array. #js [1]))
                          {}))))
  (testing "lexicographic comparison"
    (is (neg? (order/rank #?(:clj (byte-array [1 2]) :cljs (js/Uint8Array. #js [1 2]))
                          #?(:clj (byte-array [1 3]) :cljs (js/Uint8Array. #js [1 3])))))
    (is (neg? (order/rank #?(:clj (byte-array [1]) :cljs (js/Uint8Array. #js [1]))
                          #?(:clj (byte-array [1 2]) :cljs (js/Uint8Array. #js [1 2])))))
    (is (pos? (order/rank #?(:clj (byte-array [2]) :cljs (js/Uint8Array. #js [2]))
                          #?(:clj (byte-array [1]) :cljs (js/Uint8Array. #js [1]))))))
  (testing "bytes < inst < uuid within tagged"
    (is (neg? (order/rank #?(:clj (byte-array [1]) :cljs (js/Uint8Array. #js [1]))
                          #?(:clj (java.util.Date.) :cljs (js/Date.)))))
    (is (neg? (order/rank #?(:clj (byte-array [1]) :cljs (js/Uint8Array. #js [1]))
                          #uuid "29558297-e4b8-47af-bf3d-84942b5b40b8")))))

(deftest cross-type-ordering-test
  (testing "Appendix C.3: mixed-type set ordering"
    (is (= [nil true 3.14 42 "str" :kw '(2) [1] #{} {}]
           (sort order/rank
                 [:kw "str" true 42 nil [1] '(2) #{} {} 3.14])))))

(deftest rank-lists-vs-vectors-test
  (testing "all lists before all vectors (type priority 6 vs 7)"
    (is (neg? (order/rank '(999) [1])))))

;; --- Exact numeric comparison (§5.3.3) ---

(deftest rank-numbers-exact-test
  (testing "integers near 2^53 exactly representable on every platform"
    (is (neg? (order/rank 9007199254740992 9007199254740994)))
    (is (pos? (order/rank 9007199254740994 9007199254740992))))
  #?(:clj
     (testing "longs above 2^53 are not widened to double"
       (is (neg? (order/rank 9007199254740992 9007199254740993)))
       (is (pos? (order/rank 9007199254740993 9007199254740992)))
       (is (neg? (order/rank (dec Long/MAX_VALUE) Long/MAX_VALUE)))))
  #?(:clj
     (testing "long vs double compared by exact value"
       (is (pos? (order/rank 9007199254740993 9007199254740992.0)))
       (is (neg? (order/rank 9007199254740992.0 9007199254740993)))
       ;; 2^62 as a double; its shortest decimal form (…7900) is not its
       ;; exact value (…7904), so comparison must not go through strings
       (is (neg? (order/rank 4611686018427387903 4.611686018427387904E18)))
       (is (pos? (order/rank 4611686018427387905 4.611686018427387904E18)))
       ;; 2^63 as a double exceeds every long
       (is (neg? (order/rank Long/MAX_VALUE 9.223372036854775807E18)))
       (is (pos? (order/rank Long/MIN_VALUE -9.223372036854777E18)))))
  #?(:clj
     (testing "-0.0 and 0.0 are the same mathematical value"
       (is (zero? (order/rank -0.0 0.0))))))

;; --- Tagged literal ordering (§5.3.10) ---

(defn- date [ms] #?(:clj (java.util.Date. (long ms)) :cljs (js/Date. ms)))

(def ^:private day 86400000)

(deftest rank-inst-chronological-test
  (testing "chronological, not by Date.toString (weekday/month names)"
    ;; Thu 1970-01-01, Fri 01-02, Tue 01-06: toString order would be Fri < Thu < Tue
    (is (= [0 day (* 5 day)]
           (map #(.getTime %) (sort order/rank [(date (* 5 day)) (date day) (date 0)]))))
    ;; Sun 2023-01-01 vs Mon 2023-01-02 vs Fri 2022-12-30 (month and year roll back)
    (is (= [1672358400000 1672531200000 1672617600000]
           (map #(.getTime %) (sort order/rank [(date 1672617600000)
                                                (date 1672358400000)
                                                (date 1672531200000)])))))
  (testing "same day, different hour"
    (is (neg? (order/rank (date 0) (date (quot day 2))))))
  (testing "pre-epoch dates"
    (is (neg? (order/rank (date -1) (date 0))))))

;; JVM only: bb's native image cannot change the default TimeZone.
#?(:bb  nil
   :clj
   (deftest rank-inst-timezone-independent-test
     (testing "order does not depend on the JVM default timezone"
       (let [original (java.util.TimeZone/getDefault)
             dates    (map #(date (* % 7 3600000)) (range 20))
             expected (map #(.getTime ^java.util.Date %) dates)]
         (try
           (doseq [tz ["UTC" "America/New_York" "Pacific/Kiritimati" "Asia/Kolkata"]]
             (java.util.TimeZone/setDefault (java.util.TimeZone/getTimeZone ^String tz))
             (is (= expected (map #(.getTime ^java.util.Date %)
                                  (sort order/rank (reverse dates))))
                 tz))
           (finally
             (java.util.TimeZone/setDefault original)))))))

#?(:clj
   (deftest rank-instant-test
     (testing "fractional seconds rank before the next whole second"
       (is (neg? (order/rank (java.time.Instant/ofEpochMilli 500)
                             (java.time.Instant/ofEpochSecond 1)))))
     (testing "nanosecond resolution"
       (is (neg? (order/rank (java.time.Instant/ofEpochSecond 0 1)
                             (java.time.Instant/ofEpochSecond 0 2)))))
     (testing "Date and Instant share one timeline"
       (is (zero? (order/rank (java.util.Date. 0) java.time.Instant/EPOCH)))
       (is (neg? (order/rank (java.util.Date. 0) (java.time.Instant/ofEpochSecond 0 1))))
       (is (pos? (order/rank (java.util.Date. 1) (java.time.Instant/ofEpochSecond 0 999999)))))))

(deftest rank-uuid-test
  (testing "by canonical (lowercase) string"
    (is (neg? (order/rank #uuid "00000000-0000-0000-0000-000000000001"
                          #uuid "00000000-0000-0000-0000-000000000002")))
    ;; UUID.compareTo is signed: it would put 8000… before 0000…
    (is (neg? (order/rank #uuid "00000000-0000-0000-0000-000000000000"
                          #uuid "80000000-0000-0000-0000-000000000000"))))
  #?(:cljs
     ;; cljs.core/uuid lowercases, but the UUID constructor keeps case
     (testing "CLJS UUIDs can keep input case; rank must ignore it"
       (is (pos? (order/rank (UUID. "B0000000-0000-0000-0000-000000000000" nil)
                             (UUID. "a0000000-0000-0000-0000-000000000000" nil))))
       (is (zero? (order/rank (UUID. "ABCDEF00-0000-0000-0000-000000000000" nil)
                              (UUID. "abcdef00-0000-0000-0000-000000000000" nil)))))))
