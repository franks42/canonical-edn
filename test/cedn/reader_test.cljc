(ns cedn.reader-test
  (:require [clojure.test :refer [deftest is are testing]]
            [clojure.edn :as edn]
            [cedn.core :as cedn]
            [cedn.reader :as reader]))

(defn- error-data
  "The ex-data of the exception thrown by (f), or nil."
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
      (ex-data e))))

(defn- ms [inst]
  #?(:clj (.toEpochMilli ^java.time.Instant inst) :cljs (.getTime inst)))

;; --- #bytes reader (§8.2: no silent truncation) ---

(defn- unsigned [bs] (mapv #(bit-and % 0xff) bs))

(deftest hex->bytes-valid-test
  (are [s expected] (= expected (unsigned (reader/hex->bytes s)))
    ""         []
    "00"       [0]
    "0102"     [1 2]
    ;; uppercase is not canonical, but reads
    "DEADBEEF" [0xde 0xad 0xbe 0xef])
  (testing "round-trips through canonical output"
    (is (cedn/canonical? "#bytes \"deadbeef\""))
    (is (not (cedn/canonical? "#bytes \"DEADBEEF\"")))))

(deftest hex->bytes-invalid-test
  (testing "odd length is rejected, not truncated"
    (let [d (error-data #(reader/hex->bytes "abc"))]
      (is (= :cedn/invalid-tag-form (:cedn/error d)))
      (is (= "odd number of hex digits" (:cedn/reason d)))))
  (testing "non-hex is rejected with a CEDN error, not a parse exception"
    (are [s] (= :cedn/invalid-tag-form
                (:cedn/error (error-data #(reader/hex->bytes s))))
      "zz"
      "12g4"
      "0x12"
      " 12"))
  (testing "through the readers map"
    (is (= :cedn/invalid-tag-form
           (:cedn/error (error-data #(edn/read-string {:readers cedn/readers}
                                                      "#bytes \"abc\"")))))))

;; --- #inst reader: the full EDN grammar (§3.12) ---

(deftest parse-inst-grammar-test
  (testing "shortened forms default to January 1st, 00:00:00 UTC"
    (are [s expected-ms] (= expected-ms (ms (reader/parse-inst s)))
      "1970"                        0
      "1970-01"                     0
      "1970-01-01"                  0
      "1970-01-01T00:00"            0
      "1970-01-01T00:00:00"         0
      "1970-01-01T00:00:00Z"        0
      "1970-01-02"                  86400000
      "1970-01-01T00:00:00.5Z"      500
      "1970-01-01T00:00:00.123456789Z" 123))
  (testing "a missing offset means UTC on every platform"
    ;; js/Date. would read this as local time
    (is (= 37200000 (ms (reader/parse-inst "1970-01-01T10:20")))))
  (testing "offsets are applied"
    (is (= -3600000 (ms (reader/parse-inst "1970-01-01T00:00:00+01:00"))))
    (is (= 3600000 (ms (reader/parse-inst "1970-01-01T00:00:00-01:00"))))
    (is (= 0 (ms (reader/parse-inst "1970-01-01T00:00:00+00:00")))))
  #?(:clj
     (testing "nanosecond precision is preserved on the JVM"
       (is (= 123456789 (.getNano ^java.time.Instant
                         (reader/parse-inst "1970-01-01T00:00:00.123456789Z"))))))
  (testing "years 0-99 are literal, not 1900-based"
    (is (= "#inst \"0001-01-01T00:00:00.000000000Z\""
           (cedn/canonical-str (reader/parse-inst "0001-01-01"))))
    (is (= "#inst \"0099-12-31T00:00:00.000000000Z\""
           (cedn/canonical-str (reader/parse-inst "0099-12-31"))))))

(deftest parse-inst-invalid-test
  (are [s reason] (= reason (:cedn/reason (error-data #(reader/parse-inst s))))
    "not-a-date"            "not an RFC 3339 / EDN timestamp"
    "20-01-01"              "not an RFC 3339 / EDN timestamp"
    "1970-01-01 00:00:00"   "not an RFC 3339 / EDN timestamp"
    "1970-13-01"            "month out of range"
    "1970-00-01"            "month out of range"
    "1970-02-30"            "day out of range for month"
    "2019-02-29"            "day out of range for month"
    "1970-01-01T24:00"      "hour out of range"
    "1970-01-01T00:60"      "minute out of range"
    "2016-12-31T23:59:60Z"  "leap second is not representable")
  (testing "leap days are allowed in leap years"
    (is (some? (reader/parse-inst "2020-02-29")))
    (is (some? (reader/parse-inst "2000-02-29")))
    (is (= "day out of range for month"
           (:cedn/reason (error-data #(reader/parse-inst "1900-02-29")))))))

(deftest read-inst-through-readers-test
  (testing "valid EDN that the old JVM reader rejected"
    (is (= "#inst \"2020-01-01T00:00:00.000000000Z\""
           (cedn/canonical-str (edn/read-string {:readers cedn/readers}
                                                "#inst \"2020-01-01\""))))
    (is (= "#inst \"2019-12-31T23:00:00.000000000Z\""
           (cedn/canonical-str (edn/read-string {:readers cedn/readers}
                                                "#inst \"2020-01-01T00:00:00+01:00\"")))))
  (testing "canonical output round-trips"
    ;; CLJS js/Date holds milliseconds, so only the JVM round-trips
    ;; sub-millisecond digits (see spec §3.12).
    (let [s #?(:clj  "#inst \"2020-06-15T12:34:56.123456789Z\""
               :cljs "#inst \"2020-06-15T12:34:56.123000000Z\"")]
      (is (cedn/canonical? s))
      (is (= s (cedn/canonical-str (edn/read-string {:readers cedn/readers} s)))))))
