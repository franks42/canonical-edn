(ns cedn.emit-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cedn.emit :as emit])
  #?(:clj (:import [java.util Date UUID]
                   [java.time Instant])))

;; --- C.1 Round-Trip Identity ---

(deftest emit-nil-test
  (is (= "nil" (emit/emit-str :cedn-p nil))))

(deftest emit-boolean-test
  (is (= "true" (emit/emit-str :cedn-p true)))
  (is (= "false" (emit/emit-str :cedn-p false))))

(deftest emit-integer-test
  (are [input expected]
       (= expected (emit/emit-str :cedn-p input))
    42  "42"
    -7  "-7"
    0   "0"))

(deftest emit-double-test
  (is (= "3.14" (emit/emit-str :cedn-p 3.14)))
  ;; In JS, -0.0 === 0 (same value), 1.0 and 0.0 are integers
  #?(:clj
     (do
       (is (= "0.0" (emit/emit-str :cedn-p -0.0)))
       (is (= "1.0" (emit/emit-str :cedn-p 1.0)))
       (is (= "0.0" (emit/emit-str :cedn-p 0.0))))
     :cljs
     (is (= "0" (emit/emit-str :cedn-p -0.0)))))

(deftest emit-string-basic-test
  (are [input expected]
       (= expected (emit/emit-str :cedn-p input))
    ""       "\"\""
    "hello"  "\"hello\""))

(deftest emit-keyword-test
  (are [input expected]
       (= expected (emit/emit-str :cedn-p input))
    :foo     ":foo"
    :ns/bar  ":ns/bar"))

(deftest emit-symbol-test
  (is (= "foo" (emit/emit-str :cedn-p 'foo)))
  (is (= "ns/bar" (emit/emit-str :cedn-p 'ns/bar))))

(deftest emit-list-test
  (is (= "()" (emit/emit-str :cedn-p '())))
  (is (= "(1 2 3)" (emit/emit-str :cedn-p '(1 2 3)))))

(deftest emit-vector-test
  (is (= "[]" (emit/emit-str :cedn-p [])))
  (is (= "[1 2 3]" (emit/emit-str :cedn-p [1 2 3]))))

(deftest emit-set-test
  (is (= "#{}" (emit/emit-str :cedn-p #{})))
  (is (= "#{1 2 3}" (emit/emit-str :cedn-p #{3 1 2}))))

(deftest emit-map-test
  (is (= "{}" (emit/emit-str :cedn-p {})))
  (is (= "{:a 1 :b 2}" (emit/emit-str :cedn-p {:b 2 :a 1}))))

;; --- C.1 additional vectors ---

(deftest emit-c1-tab-string-test
  (is (= "\"a\\tb\"" (emit/emit-str :cedn-p "a\tb"))))

;; --- C.3 Cross-Type Ordering ---

(deftest emit-cross-type-set-test
  (testing "Appendix C.3: mixed-type set"
    (is (= "#{nil true 3.14 42 \"str\" :kw (2) [1] #{} {}}"
           (emit/emit-str :cedn-p
                          #{:kw "str" true 42 nil [1] '(2) #{} {} 3.14})))))

;; --- C.5 String Escaping ---

(deftest emit-string-escaping-test
  (testing "Appendix C.5 string escaping vectors"
    (are [input expected]
         (= expected (emit/emit-str :cedn-p input))
      "hello"           "\"hello\""
      "say \"hi\""      "\"say \\\"hi\\\"\""
      "line1\nline2"    "\"line1\\nline2\""
      "tab\there"       "\"tab\\there\""
      "back\\slash"     "\"back\\\\slash\""
      "\u0000"          "\"\\u0000\""
      "café"            "\"café\""
      "\u00e9"          "\"é\"")))

(deftest emit-control-char-escape-test
  (testing "control characters use \\uNNNN"
    (is (= "\"\\u0008\"" (emit/emit-str :cedn-p "\u0008")))
    (is (= "\"\\u000c\"" (emit/emit-str :cedn-p "\u000c")))
    (is (= "\"\\u001f\"" (emit/emit-str :cedn-p "\u001f")))
    (is (= "\"\\u007f\"" (emit/emit-str :cedn-p "\u007f")))))

;; --- #inst ---

#?(:clj
   (deftest emit-inst-date-test
     (testing "java.util.Date (ms precision, last 6 digits zero)"
       (let [d (Date. 1740571200123)]
         (is (re-matches #"#inst \"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{9}Z\""
                         (emit/emit-str :cedn-p d)))
         (is (.endsWith ^String (emit/emit-str :cedn-p d) "000000Z\""))))))

#?(:clj
   (deftest emit-inst-instant-test
     (testing "java.time.Instant (ns precision)"
       (let [inst (Instant/ofEpochSecond 1740571200 123456789)]
         (is (.contains ^String (emit/emit-str :cedn-p inst) ".123456789Z"))))))

#?(:clj
   (deftest emit-inst-epoch-test
     (testing "epoch instant"
       (is (= "#inst \"1970-01-01T00:00:00.000000000Z\""
              (emit/emit-str :cedn-p (Date. 0)))))))

#?(:cljs
   (deftest emit-inst-js-date-test
     (testing "js/Date (ms precision, last 6 digits zero)"
       (let [d (js/Date. 1740571200123)
             s (emit/emit-str :cedn-p d)]
         (is (re-matches #"#inst \"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{9}Z\"" s))
         (is (.endsWith s "000000Z\""))))
     (testing "epoch js/Date"
       (is (= "#inst \"1970-01-01T00:00:00.000000000Z\""
              (emit/emit-str :cedn-p (js/Date. 0)))))))

;; --- #uuid ---

#?(:clj
   (deftest emit-uuid-test
     (testing "lowercase 8-4-4-4-12"
       (let [u (UUID/fromString "F81D4FAE-7DEC-11D0-A765-00A0C91E6BF6")]
         (is (= "#uuid \"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\""
                (emit/emit-str :cedn-p u)))))))

#?(:cljs
   (deftest emit-uuid-cljs-test
     (testing "lowercase 8-4-4-4-12"
       (let [u (uuid "F81D4FAE-7DEC-11D0-A765-00A0C91E6BF6")]
         (is (= "#uuid \"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\""
                (emit/emit-str :cedn-p u)))))))

;; --- #bytes ---

(deftest emit-bytes-empty-test
  (testing "empty byte array"
    (is (= "#bytes \"\""
           (emit/emit-str :cedn-p #?(:clj (byte-array 0)
                                      :cljs (js/Uint8Array. 0)))))))

(deftest emit-bytes-single-test
  (testing "single byte"
    (is (= "#bytes \"2a\""
           (emit/emit-str :cedn-p #?(:clj (byte-array [0x2a])
                                      :cljs (js/Uint8Array. #js [0x2a])))))))

(deftest emit-bytes-multi-test
  (testing "multi-byte"
    (is (= "#bytes \"010203\""
           (emit/emit-str :cedn-p #?(:clj (byte-array [1 2 3])
                                      :cljs (js/Uint8Array. #js [1 2 3])))))))

(deftest emit-bytes-high-test
  (testing "high bytes (0xff)"
    (is (= "#bytes \"00ff80\""
           (emit/emit-str :cedn-p #?(:clj (byte-array [(byte 0x00) (unchecked-byte 0xff) (unchecked-byte 0x80)])
                                      :cljs (js/Uint8Array. #js [0x00 0xff 0x80])))))))

(deftest emit-bytes-hash-test
  (testing "typical SHA-256 hash prefix"
    (is (= "#bytes \"deadbeef\""
           (emit/emit-str :cedn-p #?(:clj (byte-array [(unchecked-byte 0xde) (unchecked-byte 0xad)
                                                        (unchecked-byte 0xbe) (unchecked-byte 0xef)])
                                      :cljs (js/Uint8Array. #js [0xde 0xad 0xbe 0xef])))))))

;; --- Error cases ---

(deftest emit-unsupported-type-test
  ;; In JS, 22/7 evaluates to a double (3.142857...) — not a ratio
  #?(:clj
     (testing "ratios throw"
       (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"unsupported type"
            (emit/emit-str :cedn-p 22/7)))))
  (testing "regex throws"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"unsupported type"
         (emit/emit-str :cedn-p #"regex")))))

(deftest emit-invalid-number-test
  (testing "NaN throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (emit/emit-str :cedn-p ##NaN))))
  (testing "Infinity throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (emit/emit-str :cedn-p ##Inf)))))

;; --- Nested structures ---

(deftest emit-nested-test
  (is (= "{:a [1 2 3] :b #{:x :y}}"
         (emit/emit-str :cedn-p {:b #{:y :x} :a [1 2 3]}))))

(deftest emit-map-sort-test
  (testing "keys sorted across types"
    (is (= "{1 \"one\" 2 \"two\"}"
           (emit/emit-str :cedn-p {2 "two" 1 "one"})))))

;; --- Normalization (C.2) ---

(deftest emit-normalization-test
  (testing "Appendix C.2: non-canonical inputs"
    (is (= "{:a 1 :b 2}" (emit/emit-str :cedn-p {:b 2 :a 1})))
    (is (= "#{1 2 3}" (emit/emit-str :cedn-p #{3 1 2})))
    (is (= "{:a 2 :m 3 :z 1}" (emit/emit-str :cedn-p {:z 1 :a 2 :m 3})))
    (is (= "[1 2 3]" (emit/emit-str :cedn-p [1 2 3])))))
