(ns cedn.gen
  "Property-based testing generators for CEDN values.

  Built on clojure.test.check.generators — generates arbitrary
  values that conform to the CEDN-P type contract."
  (:require [clojure.test.check.generators :as gen])
  #?(:clj (:import [java.util Date])))

;; Custom generators for types that test.check can't generate natively

(def gen-finite-double
  "Generator for finite, non-NaN doubles."
  (gen/such-that
   #?(:clj  #(Double/isFinite %)
      :cljs #(js/isFinite %))
   (gen/double* {:NaN? false :infinite? false})
   100))

#?(:clj
   (def gen-inst
     "Generator for java.time.Instant values, including sub-millisecond
  ones.  Instants (not Dates) so that two generated values are equal
  exactly when they are the same moment: a Date and an Instant for one
  moment are not (=) but canonicalize identically, which is a duplicate
  inside a generated set.  Use gen-date for the Date type."
     (gen/fmap
      (fn [[ms nanos]] (.plusNanos (java.time.Instant/ofEpochMilli ms) nanos))
      (gen/tuple (gen/choose 0 4102444800000)
                 (gen/choose 0 999999))))

   :cljs
   (def gen-inst
     "Generator for js/Date values (millisecond resolution)."
     (gen/fmap
      (fn [ms] (js/Date. ms))
      (gen/choose 0 4102444800000))))

#?(:clj
   (def gen-date
     "Generator for java.util.Date values (millisecond resolution)."
     (gen/fmap (fn [ms] (Date. ^long ms)) (gen/choose 0 4102444800000)))

   :cljs
   (def gen-date gen-inst))

(def uuid-pool
  "A fixed pool of UUIDs for generation.

  test.check generators must be pure functions of the seed: a generator
  calling randomUUID produces values that cannot be replayed from a
  reported seed and cannot shrink, so a failing case is unreproducible.
  Drawing from a fixed pool keeps generation seed-derived.

  Includes the nil and max UUIDs and the signed-64-bit boundaries, which
  is where ordering implementations tend to go wrong."
  [#uuid "00000000-0000-0000-0000-000000000000" #uuid "00000000-0000-0000-0000-000000000001"
   #uuid "7fffffff-ffff-ffff-ffff-ffffffffffff" #uuid "80000000-0000-0000-0000-000000000000"
   #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff" #uuid "0f0e0d0c-0b0a-0908-0706-050403020100"
   #uuid "29558297-e4b8-47af-bf3d-84942b5b40b8" #uuid "7a649e72-0de1-45ae-b869-8c0bbf478c50"
   #uuid "2c70999c-8654-45de-a8e5-367915c57aea" #uuid "b5355c76-bd25-460c-8a6d-d39e961dfe7c"
   #uuid "460bb3a3-ca97-4157-a9ad-e93fe67f6387" #uuid "70a3a5de-ad37-41d4-8f4c-271771e40c09"
   #uuid "2fc66b74-2f8d-4929-9381-17fd4d1611cc" #uuid "80da28e7-b39e-4e1d-9465-f4d47d2361d5"
   #uuid "8b33cc2d-5979-477c-820a-9f570bf1b1a8" #uuid "1470ff77-75cc-4c3e-b5ff-5df6e14fffa6"
   #uuid "fc87ae92-1656-4945-a9e2-698214eaceea" #uuid "6c445ab5-e061-4023-8b8a-988bdbd8ac04"
   #uuid "c43c5587-90e9-4ba2-a3fa-7238a3ed9ed5" #uuid "36b5a3bc-3436-4a8a-9105-89482e8c50a5"
   #uuid "d2a14308-ca77-4096-bde2-593e1b388cdd" #uuid "2c41ea5f-d1a3-455e-b2d9-defbbb2f412b"
   #uuid "afc8cc1f-0759-4523-9a42-c95e6977e1b5" #uuid "0d00b7ea-4fc6-4f19-a839-44ecc22731ea"
   #uuid "afb36a0e-118e-408e-8651-2343358ae0d0" #uuid "a73dae86-f8bd-46ee-9aeb-cb763a8f0932"
   #uuid "f1375432-2681-454f-aa50-ba3545911b81" #uuid "e4ee8793-1405-4c36-a18a-c86bbeff6da1"
   #uuid "9d6444f9-1179-4c80-8a34-6b40801df653" #uuid "66b60937-ac9c-462e-abc8-e218a5efc2f6"
   #uuid "71f3b2a1-9b88-425c-af7c-c9385062c64b" #uuid "832efe81-e9ba-429f-b139-40d4bd7e58cb"])

(def gen-uuid
  "Generator for UUIDs, drawn from uuid-pool (replayable and shrinkable)."
  (gen/elements uuid-pool))

;; EDN keywords and symbols must have non-empty names that start with
;; an alphabetic character. gen/string-alphanumeric can produce ""
;; which creates invalid tokens like `:`.
(def ^:private gen-edn-name
  "Generator for valid EDN keyword/symbol names."
  (gen/fmap
   (fn [[c s]] (str c s))
   (gen/tuple
    (gen/fmap char (gen/choose 97 122))
    gen/string-alphanumeric)))

(def ^:private interesting-chars
  ;; Characters that exercise the escaping rules (§3.5) and the
  ;; codepoint-ordering rules (§5.3.4): the mandatory escapes, control
  ;; characters, U+007F, non-ASCII, and astral-plane pairs.
  ["\"" "\\" "\n" "\r" "\t" "\u0000" "\u0001" "\u001f" "\u007f"
   " " "a" "Z" "0" "~" "\u00e9" "\u00df" "\u4e2d" "\uffff"
   "\ud83d\ude00" "\ud800\udc00"])

(def gen-string
  "Generator for CEDN-P strings: escapes, control characters, non-ASCII
  and astral-plane pairs, never an unpaired surrogate."
  (gen/one-of
   [gen/string-alphanumeric
    (gen/fmap #(apply str %)
              (gen/vector (gen/elements interesting-chars) 0 6))]))

(def gen-bytes
  "Generator for byte arrays (byte[] / js/Uint8Array).

  Deliberately not part of gen-cedn-p: two byte arrays with the same
  content are not (=), so a generated set could hold both, and they
  canonicalize identically — a duplicate-element error rather than a
  useful test case."
  (gen/fmap #?(:clj  (fn [xs] (byte-array (map unchecked-byte xs)))
               :cljs (fn [xs] (js/Uint8Array. (clj->js xs))))
            (gen/vector (gen/choose 0 255) 0 8)))

(def gen-cedn-p-leaf
  "Generator for CEDN-P leaf values."
  (gen/one-of
   [(gen/return nil)
    gen/boolean
    ;; JVM: full Long range; JS: limited to Number.MAX_SAFE_INTEGER
    (gen/large-integer* {:min #?(:clj -9223372036854775808 :cljs -9007199254740991)
                         :max #?(:clj  9223372036854775807 :cljs  9007199254740991)})
    gen-finite-double
    gen-string
    (gen/fmap keyword gen-edn-name)
    (gen/fmap symbol gen-edn-name)
    ;; namespaced keywords/symbols exercise the namespace ordering rules
    (gen/fmap (fn [[ns n]] (keyword ns n)) (gen/tuple gen-edn-name gen-edn-name))
    (gen/fmap (fn [[ns n]] (symbol ns n)) (gen/tuple gen-edn-name gen-edn-name))
    gen-inst
    gen-uuid]))

(defn gen-cedn-p
  "Returns a test.check generator that produces arbitrary CEDN-P values.

  Options:
    :max-size  — maximum collection size (default 5)"
  ([] (gen-cedn-p {}))
  ([{:keys [max-size]
     :or   {max-size 5}}]
   (gen/recursive-gen
    (fn [inner]
      (gen/one-of
       [(gen/list inner)
        (gen/vector inner 0 max-size)
        (gen/set inner {:max-elements max-size})
        (gen/map
         ;; Use only hashable leaf types as keys
         (gen/one-of
          [(gen/return nil)
           gen/boolean
           (gen/large-integer* {:min -1000 :max 1000})
           gen-string
           (gen/fmap keyword gen-edn-name)
           (gen/fmap symbol gen-edn-name)])
         inner
         {:max-elements max-size})]))
    gen-cedn-p-leaf)))
