(ns cedn.xplatform-test
  "Cross-platform byte comparison tests.
  Proves all platforms produce identical canonical bytes for the same inputs.
  Values are chosen to be unambiguously the same type on all platforms,
  avoiding the JS int/double boundary (where 0.0, 1.0, etc. are integers).

  Test vectors are stored in cedn-p-compliance-vectors.edn (like IETF RFC
  test vectors). The file is the single source of truth — any CEDN-P
  implementation can read it to verify compliance."
  (:require [clojure.test :refer [deftest is testing]]
            [cedn.core :as cedn]
            [clojure.edn :as edn]
            #?(:clj [clojure.java.io :as io])))

(defn bytes->hex
  "Convert canonical byte array to lowercase hex string."
  [bs]
  #?(:clj  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) (seq bs)))
     :cljs (apply str (map (fn [b] (.padStart (.toString b 16) 2 "0"))
                           (js/Array.from bs)))))

;; =================================================================
;; File-based compliance test vectors (the authoritative source)
;; =================================================================

(defn- read-vectors-file
  "Read cedn-p-compliance-vectors.edn from the test resources."
  []
  (edn/read-string
   #?(:clj  (slurp (io/resource "cedn/cedn-p-compliance-vectors.edn"))
      :cljs (.readFileSync (js/require "fs")
                           "test/cedn/cedn-p-compliance-vectors.edn" "utf-8"))))

(deftest compliance-from-file-test
  (let [{[input expected-str expected-hex] :compliance
         vectors :vectors} (read-vectors-file)]
    (testing "composite compliance — canonical-str matches golden string"
      (is (= expected-str (cedn/canonical-str input))))
    (testing "composite compliance — canonical-bytes matches golden hex"
      (is (= expected-hex (bytes->hex (cedn/canonical-bytes input)))))
    (testing "per-value canonical-str from file"
      (doseq [[label value expected _] vectors]
        (testing label
          (is (= expected (cedn/canonical-str value))))))
    (testing "per-value canonical-bytes from file"
      (doseq [[label value _ exp-hex] vectors]
        (testing label
          (is (= exp-hex (bytes->hex (cedn/canonical-bytes value)))))))))

;; =================================================================
;; Inline reference data (safety net — works without the file)
;; =================================================================

(def xplatform-cases
  "Inline copy of per-value test vectors.
  Same data as :vectors in cedn-p-compliance-vectors.edn."
  [;; nil
   ["nil" nil "nil" "6e696c"]
   ;; boolean
   ["true" true "true" "74727565"]
   ["false" false "false" "66616c7365"]
   ;; integer
   ["int 0" 0 "0" "30"]
   ["int 1" 1 "1" "31"]
   ["int -1" -1 "-1" "2d31"]
   ["int 42" 42 "42" "3432"]
   ["int -7" -7 "-7" "2d37"]
   ["int max-safe" 9007199254740991 "9007199254740991"
    "39303037313939323534373430393931"]
   ;; double (unambiguously non-integer on all platforms)
   ["double pi" 3.141592653589793 "3.141592653589793"
    "332e313431353932363533353839373933"]
   ["double 0.5" 0.5 "0.5" "302e35"]
   ["double -1.5" -1.5 "-1.5" "2d312e35"]
   ["double 0.1" 0.1 "0.1" "302e31"]
   ["double 1e-7" 1e-7 "1e-7" "31652d37"]
   ;; string
   ["string empty" "" "\"\"" "2222"]
   ["string hello" "hello" "\"hello\"" "2268656c6c6f22"]
   ["string cafe" "café" "\"café\"" "22636166c3a922"]
   ["string tab" "a\tb" "\"a\\tb\"" "22615c746222"]
   ["string newline" "a\nb" "\"a\\nb\"" "22615c6e6222"]
   ["string quote" "a\"b" "\"a\\\"b\"" "22615c226222"]
   ["string backslash" "a\\b" "\"a\\\\b\"" "22615c5c6222"]
   ["string null" "\u0000" "\"\\u0000\"" "225c753030303022"]
   ;; keyword
   ["keyword :foo" :foo ":foo" "3a666f6f"]
   ["keyword :ns/bar" :ns/bar ":ns/bar" "3a6e732f626172"]
   ;; symbol
   ["symbol foo" 'foo "foo" "666f6f"]
   ["symbol ns/bar" 'ns/bar "ns/bar" "6e732f626172"]
   ;; list
   ["list ()" () "()" "2829"]
   ["list (1 2 3)" '(1 2 3) "(1 2 3)" "28312032203329"]
   ;; vector
   ["vec []" [] "[]" "5b5d"]
   ["vec [1 2 3]" [1 2 3] "[1 2 3]" "5b31203220335d"]
   ["vec mixed" [nil true 42 "x" :a] "[nil true 42 \"x\" :a]"
    "5b6e696c207472756520343220227822203a615d"]
   ;; set
   ["set #{}" #{} "#{}" "237b7d"]
   ["set #{1 2 3}" #{3 1 2} "#{1 2 3}" "237b31203220337d"]
   ;; map
   ["map {}" {} "{}" "7b7d"]
   ["map sorted" {:b 2 :a 1} "{:a 1 :b 2}" "7b3a612031203a6220327d"]
   ;; #uuid
   ["uuid" #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"
    "#uuid \"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\""
    "2375756964202266383164346661652d376465632d313164302d613736352d30306130633931653662663622"]
   ;; #inst (ms precision — common denominator for JVM Date and JS Date)
   ["inst epoch" #inst "1970-01-01T00:00:00.000Z"
    "#inst \"1970-01-01T00:00:00.000000000Z\""
    "23696e73742022313937302d30312d30315430303a30303a30302e3030303030303030305a22"]
   ["inst 2025" #inst "2025-02-26T12:00:00.123Z"
    "#inst \"2025-02-26T12:00:00.123000000Z\""
    "23696e73742022323032352d30322d32365431323a30303a30302e3132333030303030305a22"]
   ;; nested
   ["nested" {:a [1 2] :b #{:x :y} :c "hello"}
    "{:a [1 2] :b #{:x :y} :c \"hello\"}"
    "7b3a61205b3120325d203a6220237b3a78203a797d203a63202268656c6c6f227d"]
   ;; cross-type set (Appendix C.3)
   ["cross-type" #{nil true 3.14 42 "str" :kw '(2) [1] #{} {}}
    "#{nil true 3.14 42 \"str\" :kw (2) [1] #{} {}}"
    "237b6e696c207472756520332e3134203432202273747222203a6b7720283229205b315d20237b7d207b7d7d"]])

(deftest cross-platform-canonical-str-test
  (testing "canonical-str matches inline reference on all platforms"
    (doseq [[label value expected _] xplatform-cases]
      (testing label
        (is (= expected (cedn/canonical-str value)))))))

(deftest cross-platform-canonical-bytes-test
  (testing "canonical-bytes matches inline reference on all platforms"
    (doseq [[label value _ expected-hex] xplatform-cases]
      (testing label
        (is (= expected-hex (bytes->hex (cedn/canonical-bytes value))))))))

;; =================================================================
;; Byte array cross-platform vectors (not EDN-representable literals,
;; so kept as a separate def + test)
;; =================================================================

(def xplatform-bytes-cases
  "Cross-platform byte array test vectors.
  Each entry is [label byte-array-value expected-str expected-hex]."
  [["bytes empty"
    #?(:clj (byte-array 0) :cljs (js/Uint8Array. 0))
    "#bytes \"\""
    "236279746573202222"]
   ["bytes 010203"
    #?(:clj (byte-array [1 2 3]) :cljs (js/Uint8Array. #js [1 2 3]))
    "#bytes \"010203\""
    "236279746573202230313032303322"]
   ["bytes deadbeef"
    #?(:clj (byte-array [(unchecked-byte 0xde) (unchecked-byte 0xad)
                          (unchecked-byte 0xbe) (unchecked-byte 0xef)])
       :cljs (js/Uint8Array. #js [0xde 0xad 0xbe 0xef]))
    "#bytes \"deadbeef\""
    "2362797465732022646561646265656622"]
   ["bytes 00ff80"
    #?(:clj (byte-array [(byte 0x00) (unchecked-byte 0xff) (unchecked-byte 0x80)])
       :cljs (js/Uint8Array. #js [0x00 0xff 0x80]))
    "#bytes \"00ff80\""
    "236279746573202230306666383022"]])

(deftest cross-platform-bytes-canonical-str-test
  (testing "canonical-str for byte arrays matches on all platforms"
    (doseq [[label value expected _] xplatform-bytes-cases]
      (testing label
        (is (= expected (cedn/canonical-str value)))))))

(deftest cross-platform-bytes-canonical-bytes-test
  (testing "canonical-bytes for byte arrays matches on all platforms"
    (doseq [[label value _ expected-hex] xplatform-bytes-cases]
      (testing label
        (is (= expected-hex (bytes->hex (cedn/canonical-bytes value))))))))

;; =================================================================
;; Composite compliance vector (inline copy for platforms that
;; cannot read the file, e.g. Scittle)
;; =================================================================

(def cedn-p-compliance-vector
  "Single composite value exercising every CEDN-P type."
  {:bools       [true false]
   :collections {:list '(1 2 3) :map {:b 2 :a 1} :set #{3 1 2} :vec [1 2 3]}
   :doubles     [3.141592653589793 0.5 -1.5 0.1 1e-7]
   :inst        #inst "1970-01-01T00:00:00.000Z"
   :ints        [0 1 -1 42 -7 9007199254740991]
   :keywords    [:foo :ns/bar]
   :nested      {:a [1 #{:x :y}] :b "hello"}
   :nil-val     nil
   :strings     ["" "hello" "café" "a\tb" "a\nb" "a\"b" "a\\b" "\u0000"]
   :symbols     ['foo 'ns/bar]
   :uuid        #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"})

(def cedn-p-compliance-expected
  "Golden canonical string — identical on all platforms."
  "{:bools [true false] :collections {:list (1 2 3) :map {:a 1 :b 2} :set #{1 2 3} :vec [1 2 3]} :doubles [3.141592653589793 0.5 -1.5 0.1 1e-7] :inst #inst \"1970-01-01T00:00:00.000000000Z\" :ints [0 1 -1 42 -7 9007199254740991] :keywords [:foo :ns/bar] :nested {:a [1 #{:x :y}] :b \"hello\"} :nil-val nil :strings [\"\" \"hello\" \"café\" \"a\\tb\" \"a\\nb\" \"a\\\"b\" \"a\\\\b\" \"\\u0000\"] :symbols [foo ns/bar] :uuid #uuid \"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\"}")

(def cedn-p-compliance-hex
  "Golden UTF-8 hex — identical on all platforms."
  "7b3a626f6f6c73205b747275652066616c73655d203a636f6c6c656374696f6e73207b3a6c6973742028312032203329203a6d6170207b3a612031203a6220327d203a73657420237b31203220337d203a766563205b31203220335d7d203a646f75626c6573205b332e31343135393236353335383937393320302e35202d312e3520302e312031652d375d203a696e73742023696e73742022313937302d30312d30315430303a30303a30302e3030303030303030305a22203a696e7473205b302031202d31203432202d3720393030373139393235343734303939315d203a6b6579776f726473205b3a666f6f203a6e732f6261725d203a6e6573746564207b3a61205b3120237b3a78203a797d5d203a62202268656c6c6f227d203a6e696c2d76616c206e696c203a737472696e6773205b2222202268656c6c6f222022636166c3a9222022615c7462222022615c6e62222022615c2262222022615c5c622220225c7530303030225d203a73796d626f6c73205b666f6f206e732f6261725d203a75756964202375756964202266383164346661652d376465632d313164302d613736352d303061306339316536626636227d")

(deftest cedn-p-compliance-test
  (testing "composite compliance vector — canonical-str"
    (is (= cedn-p-compliance-expected
           (cedn/canonical-str cedn-p-compliance-vector))))
  (testing "composite compliance vector — canonical-bytes hex"
    (is (= cedn-p-compliance-hex
           (bytes->hex (cedn/canonical-bytes cedn-p-compliance-vector))))))
