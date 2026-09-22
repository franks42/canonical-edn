(ns cedn.property-test
  (:require [clojure.test.check.properties :as prop #?@(:cljs [:include-macros true])]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cedn.core :as cedn]
            [cedn.gen :as cgen])
  #?(:clj (:import [java.util Arrays])))

(def num-tests 200)

;; Property 1: Idempotency — canonical text is stable across roundtrips
(defspec canonical-idempotent num-tests
  (prop/for-all [v (cgen/gen-cedn-p {:max-size 3})]
                ;; cedn/readers, not the default reader: #inst must come
                ;; back at full precision to re-canonicalize identically.
                (let [s1 (cedn/canonical-str v)
                      v2 (edn/read-string {:readers cedn/readers} s1)
                      s2 (cedn/canonical-str v2)]
                  (= s1 s2))))

;; Property 2: Valid EDN — canonical output can always be read back
(defspec canonical-is-valid-edn num-tests
  (prop/for-all [v (cgen/gen-cedn-p {:max-size 3})]
                (let [s (cedn/canonical-str v)
                      read-ok? (try (edn/read-string s) true
                                    (catch #?(:clj Exception :cljs :default) _ false))]
                  read-ok?)))

;; Property 3: Determinism — same value always produces same bytes
(defspec canonical-deterministic num-tests
  (prop/for-all [v (cgen/gen-cedn-p {:max-size 3})]
                (let [b1 (cedn/canonical-bytes v)
                      b2 (cedn/canonical-bytes v)]
                  #?(:clj  (Arrays/equals ^bytes b1 ^bytes b2)
                     :cljs (= (vec b1) (vec b2))))))

;; Property 4: canonical-str agrees with canonical-bytes
(defspec str-matches-bytes num-tests
  (prop/for-all [v (cgen/gen-cedn-p {:max-size 3})]
                (let [s (cedn/canonical-str v)
                      bs (cedn/canonical-bytes v)]
                  (= s #?(:clj  (String. ^bytes bs "UTF-8")
                          :cljs (.decode (js/TextDecoder.) bs))))))

;; =============================================================
;; Adversarial properties: injectivity and input-order independence
;; =============================================================

(defn- outcome
  "[:ok canonical-str] or [:error error-class] — never throws."
  [v]
  (try
    [:ok (cedn/canonical-str v)]
    (catch #?(:clj Exception :cljs :default) e
      [:error (:cedn/error (ex-data e))])))

;; --- Keywords and symbols built from hostile characters ---

(def ^:private name-alphabet
  ["a" "Z" "\u00e9" "1" "0" " " "/" ":" "#" "+" "-" "." "'" "?" "*"
   "\"" "(" ")" "[" "]" "{" "}" "," ";" "\\" "@" "^" "`" "~" "\n" "\uD800"])

(def ^:private gen-name-part
  (gen/one-of
   [(gen/elements ["nil" "true" "false" "/" "1" "-1" "+a" ".5" "#inst" "" "a"])
    (gen/fmap str/join (gen/vector (gen/elements name-alphabet) 1 4))]))

(def ^:private gen-named
  (gen/fmap (fn [[kw? ns-part name-part]]
              (if kw? (keyword ns-part name-part) (symbol ns-part name-part)))
            (gen/tuple gen/boolean
                       (gen/one-of [(gen/return nil) gen-name-part])
                       gen-name-part)))

;; Property 5: Every keyword/symbol that canonicalizes reads back as
;; itself — so no two distinct names share canonical bytes (§3.6, §3.7).
(defspec named-values-round-trip num-tests
  (prop/for-all [x gen-named]
                (let [[status s] (outcome [x])]
                  (or (= :error status)
                      (= [x] (edn/read-string s))))))

;; --- Strings built from arbitrary UTF-16 code units ---

(def ^:private gen-utf16-string
  (gen/fmap (fn [units]
              (apply str (map #?(:clj char :cljs #(.fromCharCode js/String %)) units)))
            (gen/vector (gen/one-of [(gen/choose 0 0x7F)
                                     (gen/choose 0xD800 0xDFFF)
                                     (gen/choose 0 0xFFFF)])
                        0 6)))

(defn- survives-utf8?
  "Independent oracle: true iff s has no unpaired surrogates."
  [s]
  #?(:clj  (= s (String. (.getBytes ^String s "UTF-8") "UTF-8"))
     :cljs (= s (.decode (js/TextDecoder.) (.encode (js/TextEncoder.) s)))))

;; Property 6: Well-formed strings round-trip; strings with unpaired
;; surrogates are rejected rather than silently replaced (§3.5.4).
(defspec strings-round-trip-or-reject num-tests
  (prop/for-all [s gen-utf16-string]
                (let [[status out] (outcome s)]
                  (if (survives-utf8? s)
                    (and (= :ok status) (= s (edn/read-string out)))
                    (= [:error :cedn/invalid-unicode] [status out])))))

;; --- Map keys chosen to stress rank ties ---

(def ^:private gen-tricky-key
  (gen/one-of
   [;; integers/doubles around 2^53 (and 2^62 on the JVM)
    #?(:clj  (gen/fmap (fn [[base off dbl?]]
                         (let [n (+ base off)] (if dbl? (double n) n)))
                       (gen/tuple (gen/elements [0 9007199254740992 -9007199254740992
                                                 4611686018427387904])
                                  (gen/choose -3 3)
                                  gen/boolean))
       :cljs (gen/fmap (fn [[base off]] (+ base (* 2 off)))
                       (gen/tuple (gen/elements [0 9007199254740992])
                                  (gen/choose -3 3))))
    ;; instants on a few nearby days; on the JVM a mix of Date and Instant
    #?(:clj  (gen/fmap (fn [[ms nanos instant?]]
                         (if instant?
                           (.plusNanos (java.time.Instant/ofEpochMilli ms) nanos)
                           (java.util.Date. (long ms))))
                       (gen/tuple (gen/elements [-1 0 1 86400000 432000000])
                                  (gen/elements [0 0 1])
                                  gen/boolean))
       :cljs (gen/fmap #(js/Date. %) (gen/elements [-1 0 1 86400000 432000000])))
    ;; byte arrays (identity-equal only, so duplicates survive into the map)
    (gen/fmap #?(:clj  #(byte-array (map unchecked-byte %))
                 :cljs #(js/Uint8Array. (clj->js %)))
              (gen/vector (gen/choose 0 255) 0 2))
    (gen/elements [nil true :a :b "a" 'a])]))

;; Property 7: The same logical map yields the same result — identical
;; bytes, or the same error class — whatever order its keys arrive in.
(defspec map-output-independent-of-key-order num-tests
  (prop/for-all [ks (gen/fmap distinct (gen/vector gen-tricky-key 0 8))]
                ;; distinct: CLJS array-map can keep (=) duplicate keys,
                ;; building a malformed map.  Byte arrays are never (=),
                ;; so same-content arrays still reach the duplicate check.
                (let [as-map (fn [xs] (apply array-map (mapcat (fn [k] [k k]) xs)))]
                  (= (outcome (as-map ks))
                     (outcome (as-map (reverse ks)))))))

;; Property 8: UUID generation is seed-derived — every value comes from
;; the fixed pool, so a reported seed replays and shrinking works.
(defspec uuids-come-from-the-fixed-pool num-tests
  (prop/for-all [u cgen/gen-uuid]
                (contains? (set cgen/uuid-pool) u)))

;; Property 9: byte arrays canonicalize and round-trip wherever they
;; appear (kept out of gen-cedn-p — see cedn.gen/gen-bytes).
(defspec bytes-round-trip num-tests
  (prop/for-all [bs (gen/vector cgen/gen-bytes 0 4)
                 k  (gen/elements [:a :b])]
                (let [v  {k (vec bs)}
                      s1 (cedn/canonical-str v)
                      v2 (edn/read-string {:readers cedn/readers} s1)]
                  (= s1 (cedn/canonical-str v2)))))
