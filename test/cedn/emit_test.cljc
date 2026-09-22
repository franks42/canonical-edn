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

;; --- Helpers for error-class assertions ---

(defn- error-class
  "The :cedn/error of the exception thrown by (emit-str :cedn-p v), or nil."
  [v]
  (try
    (emit/emit-str :cedn-p v)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
      (:cedn/error (ex-data e)))))

(defn- ba [xs]
  #?(:clj (byte-array (map unchecked-byte xs)) :cljs (js/Uint8Array. (clj->js xs))))

;; --- Output never depends on input iteration order (§8.1) ---

(deftest emit-order-independent-test
  (testing "map keys near 2^53"
    (is (= "{9007199254740992 :a 9007199254740994 :b}"
           (emit/emit-str :cedn-p (array-map 9007199254740994 :b 9007199254740992 :a))
           (emit/emit-str :cedn-p (array-map 9007199254740992 :a 9007199254740994 :b)))))
  #?(:clj
     (testing "longs above 2^53, as map keys and set elements"
       (is (= "{9007199254740992 :a 9007199254740993 :b}"
              (emit/emit-str :cedn-p (array-map 9007199254740993 :b 9007199254740992 :a))
              (emit/emit-str :cedn-p (array-map 9007199254740992 :a 9007199254740993 :b))))
       (is (= "#{9007199254740992 9007199254740993}"
              (emit/emit-str :cedn-p (sorted-set-by < 9007199254740992 9007199254740993))
              (emit/emit-str :cedn-p (sorted-set-by > 9007199254740992 9007199254740993))))
       (is (= "{9007199254740992.0 :d 9007199254740993 :i}"
              (emit/emit-str :cedn-p (array-map 9007199254740993 :i 9007199254740992.0 :d))
              (emit/emit-str :cedn-p (array-map 9007199254740992.0 :d 9007199254740993 :i))))))
  (testing "#inst values sorted chronologically"
    (is (= (str "#{#inst \"1970-01-01T00:00:00.000000000Z\""
                " #inst \"1970-01-02T00:00:00.000000000Z\""
                " #inst \"1970-01-06T00:00:00.000000000Z\"}")
           (emit/emit-str :cedn-p #?(:clj  #{(Date. 0) (Date. 86400000) (Date. 432000000)}
                                     :cljs #{(js/Date. 0) (js/Date. 86400000) (js/Date. 432000000)}))))))

;; --- Duplicates are identical canonical forms, not just (=) (§3.10, §3.11) ---

(defrecord Point [x y])

(deftest emit-duplicate-canonical-form-test
  #?(:clj
     (testing "Date and Instant for the same moment"
       (is (= :cedn/duplicate-element (error-class #{(Date. 0) Instant/EPOCH})))
       (is (= :cedn/duplicate-key (error-class {(Date. 0) 1 Instant/EPOCH 2})))
       (is (= "#{#inst \"1970-01-01T00:00:00.000000000Z\" #inst \"1970-01-01T00:00:00.000000001Z\"}"
              (emit/emit-str :cedn-p #{(Date. 0) (Instant/ofEpochSecond 0 1)})))))
  (testing "byte arrays with the same content"
    (is (= :cedn/duplicate-element (error-class (hash-set (ba [1 2]) (ba [1 2])))))
    (is (= :cedn/duplicate-key (error-class (hash-map (ba [1 2]) :a (ba [1 2]) :b))))
    (is (= "#{#bytes \"01\" #bytes \"0102\"}"
           (emit/emit-str :cedn-p #{(ba [1 2]) (ba [1])}))))
  (testing "a record and a map with the same entries"
    (is (= :cedn/duplicate-element (error-class #{(->Point 1 2) {:x 1 :y 2}}))))
  (testing "nested duplicates are found"
    (is (= :cedn/duplicate-element (error-class [{:k (hash-set (ba [9]) (ba [9]))}]))))
  #?(:cljs
     (testing "CLJS UUIDs differing only in case (UUID constructor keeps case)"
       (is (= :cedn/duplicate-element
              (error-class #{(UUID. "ABCDEF00-0000-0000-0000-000000000000" nil)
                             (UUID. "abcdef00-0000-0000-0000-000000000000" nil)}))))))

;; --- Unpaired surrogates (§3.5.4) ---

(deftest emit-invalid-unicode-test
  (testing "unpaired surrogates are rejected"
    (are [s] (= :cedn/invalid-unicode (error-class s))
      "\uD800"
      "\uDC00"
      "a\uD800"
      "\uDC00\uD800"))
  (testing "also inside collections and map keys"
    (is (= :cedn/invalid-unicode (error-class ["ok" "\uD800"])))
    (is (= :cedn/invalid-unicode (error-class {"\uDFFF" 1}))))
  (testing "valid surrogate pairs pass through as literal characters"
    (is (= "\"😀\"" (emit/emit-str :cedn-p "😀")))))

;; --- Keyword and symbol names (§3.6, §3.7) ---

(deftest emit-invalid-name-test
  (testing "names that would collide with other values are rejected"
    (are [v] (= :cedn/invalid-name (error-class v))
      (symbol "nil")          ; would emit as nil
      (symbol "true")         ; would emit as true
      (symbol "1")            ; would emit as the integer 1
      (symbol "-1.5")         ; would emit as a double
      (symbol ":a")           ; would emit as the keyword :a
      (symbol "#inst")        ; with a string, would emit as an #inst
      (keyword "a b")         ; [:a b] — keyword followed by symbol
      (symbol "a b")
      (symbol "[a]")
      (symbol "\"x\"")
      (keyword "a/b" "c")     ; :a/b/c is also (keyword "a" "b/c")
      (keyword "a" "b/c")
      (keyword "")
      (keyword "a\uD800")))
  (testing "nested names are checked"
    (is (= :cedn/invalid-name (error-class {:ok [(symbol "nil")]}))))
  (testing "valid edge-case names still emit"
    (are [v expected] (= expected (emit/emit-str :cedn-p v))
      (keyword "200")   ":200"
      (keyword "café")  ":café"
      '/                "/"
      'clojure.core//   "clojure.core//"
      'foo/nil          "foo/nil"
      '+                "+"
      '<=>              "<=>")))
