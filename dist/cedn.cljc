;; Auto-generated — do not edit. Run: bb build:scittle
;; Concatenated CEDN source in dependency order for Scittle/browser.

(ns cedn.error
  "Error constructors for CEDN canonicalization errors.

  All errors are ex-info with a map containing at minimum:
    :cedn/error  — keyword identifying the error class
    :cedn/value  — the offending value
    :cedn/path   — (optional) path to the value in the structure")

(defn unsupported-type!
  "Throw :cedn/unsupported-type for a value with no canonical form."
  ([value]      (unsupported-type! value nil))
  ([value path] (throw (ex-info "CEDN: unsupported type"
                                {:cedn/error :cedn/unsupported-type
                                 :cedn/value value
                                 :cedn/type  (type value)
                                 :cedn/path  path}))))

(defn invalid-number!
  "Throw :cedn/invalid-number for NaN or Infinity."
  ([value]      (invalid-number! value nil))
  ([value path] (throw (ex-info "CEDN: invalid number"
                                {:cedn/error :cedn/invalid-number
                                 :cedn/value value
                                 :cedn/path  path}))))

(defn out-of-range!
  "Throw :cedn/out-of-range for integers outside 64-bit signed range."
  ([value]      (out-of-range! value nil))
  ([value path] (throw (ex-info "CEDN: integer out of range"
                                {:cedn/error :cedn/out-of-range
                                 :cedn/value value
                                 :cedn/path  path}))))

(defn duplicate-key!
  "Throw :cedn/duplicate-key for maps with equal keys after normalization."
  ([key]        (duplicate-key! key nil))
  ([key path]   (throw (ex-info "CEDN: duplicate map key"
                                {:cedn/error :cedn/duplicate-key
                                 :cedn/value key
                                 :cedn/path  path}))))

(defn duplicate-element!
  "Throw :cedn/duplicate-element for sets with equal elements."
  ([elem]       (duplicate-element! elem nil))
  ([elem path]  (throw (ex-info "CEDN: duplicate set element"
                                {:cedn/error :cedn/duplicate-element
                                 :cedn/value elem
                                 :cedn/path  path}))))

(defn invalid-unicode!
  "Throw :cedn/invalid-unicode for strings with unpaired surrogates."
  ([value]      (invalid-unicode! value nil))
  ([value path] (throw (ex-info "CEDN: invalid unicode"
                                {:cedn/error :cedn/invalid-unicode
                                 :cedn/value value
                                 :cedn/path  path}))))

(defn invalid-tag-form!
  "Throw :cedn/invalid-tag-form for tagged literals that can't be canonicalized."
  ([value]      (invalid-tag-form! value nil))
  ([value path] (throw (ex-info "CEDN: invalid tagged literal"
                                {:cedn/error :cedn/invalid-tag-form
                                 :cedn/value value
                                 :cedn/path  path}))))


(ns cedn.number
  "ECMAScript-compatible double formatting per ECMA-262 §7.1.12.1,
  with EDN .0 suffix adaptation.

  On JVM/Babashka: pure Clojure reformatter post-processes Double/toString
  (JDK 17+ Schubfach) into ECMAScript format, then applies .0 suffix rule.

  On JS: trivial — Number.prototype.toString() IS the spec."
  (:require [cedn.error :as err]
            [clojure.string :as str]))

#?(:clj
   (defn- ecma-reformat
     "Reformat Double/toString output (JDK 17+ Schubfach) into
  ECMAScript Number::toString format per ECMA-262 §7.1.12.1.

  Differences handled:
  - Scientific notation thresholds (Java: 1e7/1e-3, ES: 1e21/1e-6)
  - Exponent case and sign (Java: E7, ES: e+7)
  - Integer doubles (Java: 100.0, ES: 100)

  Assumes non-zero, finite input (zero and special values handled
  by format-double before calling this)."
     [^String java-str]
     (let [negative? (= (.charAt java-str 0) \-)
           s (if negative? (subs java-str 1) java-str)
           ;; Split on E for scientific notation
           e-idx (.indexOf s "E")
           has-exp? (not (neg? e-idx))
           mantissa (if has-exp? (subs s 0 e-idx) s)
           exp-val (if has-exp? (Long/parseLong (subs s (inc e-idx))) 0)
           ;; Remove decimal point, track fractional digit count
           dot-idx (.indexOf ^String mantissa ".")
           int-part (if (neg? dot-idx) mantissa (subs mantissa 0 dot-idx))
           frac-part (if (neg? dot-idx) "" (subs mantissa (inc dot-idx)))
           raw-digits (str int-part frac-part)
           frac-len (count frac-part)
           ;; Strip trailing zeros
           last-nz (loop [i (dec (count raw-digits))]
                     (if (and (>= i 0) (= (.charAt ^String raw-digits i) \0))
                       (recur (dec i))
                       i))
           digits-trimmed (if (neg? last-nz) "0" (subs raw-digits 0 (inc last-nz)))
           trailing-zeros (- (count raw-digits) (count digits-trimmed))
           ;; Strip leading zeros
           first-nz (loop [i 0]
                      (if (and (< i (count digits-trimmed))
                               (= (.charAt ^String digits-trimmed i) \0))
                        (recur (inc i))
                        i))
           digits (if (= first-nz (count digits-trimmed))
                    "0"
                    (subs digits-trimmed first-nz))
           ;; Compute ECMA-262 n: value = digits × 10^exp, n = exp + k
           ;; exp = -frac-len + exp-val + trailing-zeros
           exp (+ (- frac-len) exp-val trailing-zeros)
           k (count digits)
           n (+ exp k)
           sign-prefix (if negative? "-" "")]
       (cond
         ;; Rule 1: k ≤ n ≤ 21 → integer form (digits + trailing zeros)
         (and (<= k n) (<= n 21))
         (str sign-prefix digits (apply str (repeat (- n k) \0)))

         ;; Rule 2: 0 < n < k → decimal point within digits
         (and (pos? n) (< n k))
         (str sign-prefix (subs digits 0 n) "." (subs digits n))

         ;; Rule 3: -5 ≤ n ≤ 0 → 0.000...digits
         (and (<= -5 n) (<= n 0))
         (str sign-prefix "0." (apply str (repeat (- n) \0)) digits)

         ;; Rule 4: scientific notation
         :else
         (let [e (dec n)
               e-sign (if (neg? e) "-" "+")
               e-str (str (abs e))]
           (if (= k 1)
             (str sign-prefix digits "e" e-sign e-str)
             (str sign-prefix (subs digits 0 1) "." (subs digits 1)
                  "e" e-sign e-str)))))))

(defn format-double
  "Format a double to its canonical string representation.

  - NaN, Infinity, -Infinity → throws :cedn/invalid-number
  - -0.0 → \"0.0\"
  - Integer-looking output → appends \".0\"
  - Otherwise → ECMAScript shortest representation

  Cross-platform: identical output on JVM, Babashka, and JS."
  [x]
  #?(:clj
     (do
       (when (or (Double/isNaN x) (Double/isInfinite x))
         (err/invalid-number! x))
       (if (and (zero? x) (neg? (Math/copySign 1.0 x)))
         "0.0"
         (let [s (ecma-reformat (Double/toString x))]
           (if (or (str/includes? s ".") (str/includes? s "e"))
             s
             (str s ".0")))))
     :cljs
     (do
       (when (or (js/isNaN x) (not (js/isFinite x)))
         (err/invalid-number! x))
       (if (and (zero? x) (neg? (/ 1.0 x)))
         "0.0"
         (let [s (.toString x)]
           (if (or (str/includes? s ".") (str/includes? s "e"))
             s
             (str s ".0")))))))


(ns cedn.order
  "Total ordering over canonical EDN values (Section 5).

  Exposed as cedn.core/rank for advanced use cases like
  building custom sorted collections.")

(defn type-priority
  "Returns the integer priority for a value's type.
  nil=0, boolean=1, number=2, string=3, keyword=4,
  symbol=5, list=6, vector=7, set=8, map=9,
  tagged(bytes/inst/uuid)=10."
  [value]
  (cond
    (nil? value)     0
    (boolean? value) 1
    (number? value)  2
    (string? value)  3
    (keyword? value) 4
    (symbol? value)  5
    (seq? value)     6
    (vector? value)  7
    (set? value)     8
    (map? value)     9
    :else            10))

(declare rank)

(defn- compare-strings
  "Compare two strings by Unicode codepoint order.
  Equivalent to comparing UTF-8 byte sequences."
  [^String a ^String b]
  #?(:bb
     (let [alen (.length a)
           blen (.length b)]
       (loop [ai 0 bi 0]
         (let [a-end (>= ai alen)
               b-end (>= bi blen)]
           (cond
             (and a-end b-end) 0
             a-end -1
             b-end  1
             :else
             (let [ac (.codePointAt a ai)
                   bc (.codePointAt b bi)
                   c  (compare ac bc)]
               (if (zero? c)
                 (recur (+ ai (Character/charCount ac))
                        (+ bi (Character/charCount bc)))
                 c))))))
     :clj
     (let [ai (.iterator (.codePoints a))
           bi (.iterator (.codePoints b))]
       (loop []
         (let [a-has (.hasNext ai)
               b-has (.hasNext bi)]
           (cond
             (and (not a-has) (not b-has)) 0
             (not a-has) -1
             (not b-has)  1
             :else
             (let [ac (.nextInt ai)
                   bc (.nextInt bi)
                   c  (compare ac bc)]
               (if (zero? c)
                 (recur)
                 c))))))
     :cljs
     (let [aa (js/Array.from a)
           ba (js/Array.from b)
           alen (.-length aa)
           blen (.-length ba)
           limit (min alen blen)]
       (loop [i 0]
         (if (< i limit)
           (let [ac (.codePointAt (aget aa i) 0)
                 bc (.codePointAt (aget ba i) 0)
                 c  (compare ac bc)]
             (if (zero? c)
               (recur (inc i))
               c))
           (compare alen blen))))))

(defn- compare-numbers
  "Compare two numbers by mathematical value.
  When equal, integer ranks before double."
  [a b]
  (let [cmp (compare (double a) (double b))]
    (if (zero? cmp)
      ;; Same mathematical value: int < double
      (let [a-int? (int? a)
            b-int? (int? b)]
        (cond
          (and a-int? (not b-int?)) -1
          (and (not a-int?) b-int?)  1
          :else                      0))
      cmp)))

(defn- compare-named
  "Compare keywords or symbols by namespace then name.
  Absent namespace sorts before any present namespace."
  [a b]
  (let [ans (namespace a)
        bns (namespace b)]
    (cond
      (and (nil? ans) (nil? bns))
      (compare-strings (name a) (name b))

      (nil? ans) -1
      (nil? bns)  1

      :else
      (let [nsc (compare-strings ans bns)]
        (if (zero? nsc)
          (compare-strings (name a) (name b))
          nsc)))))

(defn- compare-sequential
  "Compare sequences element-by-element. Shorter first."
  [a b]
  (let [sa (seq a)
        sb (seq b)]
    (loop [sa sa sb sb]
      (cond
        (and (nil? sa) (nil? sb)) 0
        (nil? sa) -1
        (nil? sb)  1
        :else
        (let [c (rank (first sa) (first sb))]
          (if (zero? c)
            (recur (next sa) (next sb))
            c))))))

(defn- compare-sets
  "Compare sets: by cardinality first, then pairwise sorted elements."
  [a b]
  (let [cc (compare (count a) (count b))]
    (if (not (zero? cc))
      cc
      (compare-sequential (sort rank a) (sort rank b)))))

(defn- compare-maps
  "Compare maps: by entry count, then sorted keys, then sorted values."
  [a b]
  (let [cc (compare (count a) (count b))]
    (if (not (zero? cc))
      cc
      (let [ak (sort rank (keys a))
            bk (sort rank (keys b))
            kc (compare-sequential ak bk)]
        (if (not (zero? kc))
          kc
          (let [av (map #(get a %) ak)
                bv (map #(get b %) bk)]
            (compare-sequential av bv)))))))

(defn- compare-bytes
  "Compare byte arrays lexicographically by unsigned byte value."
  [a b]
  (let [alen #?(:clj (alength ^bytes a) :cljs (.-length a))
        blen #?(:clj (alength ^bytes b) :cljs (.-length b))
        limit (min alen blen)]
    (loop [i 0]
      (if (< i limit)
        (let [ab (bit-and #?(:clj (aget ^bytes a i) :cljs (aget a i)) 0xff)
              bb (bit-and #?(:clj (aget ^bytes b i) :cljs (aget b i)) 0xff)
              c  (compare ab bb)]
          (if (zero? c)
            (recur (inc i))
            c))
        (compare alen blen)))))

(defn- tag-kind
  "Returns a keyword for the tagged-literal kind, for sub-ordering.
  :bytes < :inst < :uuid (alphabetical)."
  [v]
  (cond
    #?(:clj  (bytes? v)
       :cljs (instance? js/Uint8Array v))               :bytes
    #?(:clj  (or (instance? java.util.Date v)
                 (instance? java.time.Instant v))
       :cljs (instance? js/Date v))                      :inst
    (uuid? v)                                             :uuid
    :else                                                 :unknown))

(defn- compare-tagged
  "Compare two tagged values (bytes/inst/uuid).
  First by tag-kind (alphabetical), then by value within same kind."
  [a b]
  (let [ka (tag-kind a)
        kb (tag-kind b)]
    (if (not= ka kb)
      (compare ka kb)
      (case ka
        :bytes (compare-bytes a b)
        :inst  (compare (str a) (str b))
        :uuid  (compare (str a) (str b))
        0))))

(defn rank
  "Comparator implementing the total order from Section 5.

  1. Compare by type-priority
  2. Within same type, compare by type-specific rules:
     - numbers: mathematical value (int before double if equal)
     - strings: lexicographic (Unicode codepoint order)
     - keywords/symbols: namespace then name
     - seqs/vectors: element-by-element, shorter first
     - sets: cardinality, then pairwise elements
     - maps: count, then keys, then values
     - tagged: tag kind (bytes < inst < uuid), then value"
  [a b]
  (if (identical? a b)
    0
    (let [pa (type-priority a)
          pb (type-priority b)]
      (if (not= pa pb)
        (compare pa pb)
        (case (int pa)
          0 0  ;; nil vs nil
          1 (compare a b)  ;; booleans: false < true
          2 (compare-numbers a b)
          3 (compare-strings a b)  ;; strings: Unicode codepoint order
          4 (compare-named a b)
          5 (compare-named a b)
          6 (compare-sequential a b)  ;; lists/seqs
          7 (compare-sequential a b)  ;; vectors
          8 (compare-sets a b)
          9 (compare-maps a b)
          ;; default: tagged (bytes/inst/uuid)
          (compare-tagged a b))))))


(ns cedn.schema
  "Hand-written predicates for CEDN-P type contracts.
  Simple recursive walk over the closed CEDN-P type set.")

;; --- Leaf predicates ---

(defn- finite-double?
  [x]
  (and (double? x)
       #?(:clj  (Double/isFinite x)
          :cljs (js/isFinite x))))

(defn- inst-value?
  [x]
  #?(:clj  (or (instance? java.util.Date x)
               (instance? java.time.Instant x))
     :cljs (instance? js/Date x)))

(defn- uuid-value?
  [x]
  (uuid? x))

(defn- bytes-value?
  [x]
  #?(:clj  (bytes? x)
     :cljs (instance? js/Uint8Array x)))

;; --- Core recursive predicate ---

(defn- cedn-p-valid?
  "Returns true if v is a valid CEDN-P value."
  [v]
  (cond
    (nil? v)     true
    (boolean? v) true
    (string? v)  true
    (keyword? v) true
    (symbol? v)  true
    (int? v)     true
    (double? v)  (finite-double? v)
    (inst-value? v) true
    (uuid-value? v) true
    (bytes-value? v) true
    (seq? v)     (every? cedn-p-valid? v)
    (vector? v)  (every? cedn-p-valid? v)
    (set? v)     (every? cedn-p-valid? v)
    (map? v)     (every? (fn [[k val]] (and (cedn-p-valid? k) (cedn-p-valid? val)))
                         v)
    :else        false))

;; --- Explain (depth-first, returns first error) ---

(declare cedn-p-explain)

(defn- explain-sequential
  "Walk a sequential (list/vector) checking each element. path-fn builds the path entry."
  [coll path]
  (reduce
   (fn [_ [i elem]]
     (when-let [err (cedn-p-explain elem (conj path i))]
       (reduced err)))
   nil
   (map-indexed vector coll)))

(defn- explain-set
  [s path]
  (reduce
   (fn [_ elem]
     (when-let [err (cedn-p-explain elem (conj path elem))]
       (reduced err)))
   nil
   s))

(defn- explain-map
  [m path]
  (reduce
   (fn [_ [k val]]
     (or (when-let [err (cedn-p-explain k (conj path k))]
           (reduced err))
         (when-let [err (cedn-p-explain val (conj path k))]
           (reduced err))))
   nil
   m))

(defn- cedn-p-explain
  "Returns nil if v is valid, or an error map for the first invalid sub-value."
  [v path]
  (cond
    (nil? v)        nil
    (boolean? v)    nil
    (string? v)     nil
    (keyword? v)    nil
    (symbol? v)     nil
    (int? v)        nil
    (double? v)     (when-not (finite-double? v)
                      {:cedn/error :cedn/invalid-number
                       :cedn/value v
                       :cedn/path  path})
    (inst-value? v) nil
    (uuid-value? v) nil
    (bytes-value? v) nil
    (seq? v)        (explain-sequential v path)
    (vector? v)     (explain-sequential v path)
    (set? v)        (explain-set v path)
    (map? v)        (explain-map v path)
    :else           {:cedn/error :cedn/unsupported-type
                     :cedn/value v
                     :cedn/path  path}))

;; --- Public API ---

(defn schema-for
  "Returns the profile keyword for the given profile.
  Validates that the profile is known."
  [profile]
  (case profile
    :cedn-p :cedn-p
    (throw (ex-info (str "Unknown CEDN profile: " profile)
                    {:profile profile}))))

(defn valid?
  "Schema-level type check. Fast, no canonicalization."
  [profile value]
  (schema-for profile)
  (cedn-p-valid? value))

(defn explain
  "Schema-level explanation. Returns nil on success or an error map
  with :cedn/error, :cedn/value, and :cedn/path on first invalid sub-value."
  [profile value]
  (schema-for profile)
  (cedn-p-explain value []))


(ns cedn.emit
  "Per-type canonical text emission.

  The emit function dispatches on value type and writes canonical
  text to a StringBuilder.  This is the inner loop of
  canonicalization.

  Not a public API — use cedn.core/canonical-bytes."
  (:require [cedn.order  :as order]
            [cedn.number :as number]
            [cedn.error  :as err])
  #?(:clj  (:import [java.lang StringBuilder]
                    [java.time Instant ZoneOffset]
                    [java.util Date UUID])
     :cljs (:import [goog.string StringBuffer])))

;; --- CLJS negative-zero note ---
;; In JS, -0.0 === 0 and (= -0.0 0) is true: they are the same value.
;; We do NOT special-case negative zero on CLJS because:
;; 1. "0.0" cannot round-trip through edn/read-string (reads as integer 0)
;; 2. -0.0 and 0 are indistinguishable in JS for all practical purposes
;; Both emit as "0" via the integer path.

;; --- String escaping per §3.5 ---

(defn- escape-control-char
  "Format a control char as \\uNNNN."
  [c]
  #?(:clj  (format "\\u%04x" (int c))
     :cljs (str "\\u" (.padStart (.toString (.charCodeAt c 0) 16) 4 "0"))))

(defn- emit-string-char
  "Append the canonical escape for a single character."
  [^StringBuilder sb ch]
  (let [c #?(:clj (int ch) :cljs (.charCodeAt ch 0))]
    (case ch
      \" (.append sb "\\\"")
      \\ (.append sb "\\\\")
      \newline (.append sb "\\n")
      \return (.append sb "\\r")
      \tab (.append sb "\\t")
      ;; Control chars: U+0000-U+001F (minus named above) and U+007F
      (if (or (and (>= c 0) (<= c 0x1F))
              (= c 0x7F))
        (.append sb (escape-control-char ch))
        (.append sb ch)))))

(defn- emit-string
  "Emit a canonical string with proper escaping."
  [^StringBuilder sb s]
  (.append sb \")
  (doseq [ch s]
    (emit-string-char sb ch))
  (.append sb \"))

;; --- #inst formatting ---

#?(:clj
   (defn- format-inst
     "Format an inst value to canonical #inst string.
     Always 9 fractional digits, UTC Z suffix."
     [v]
     (let [inst (cond
                  (instance? Instant v) v
                  (instance? Date v) (.toInstant ^Date v)
                  :else (err/unsupported-type! v))
           zdt (.atZone ^Instant inst ZoneOffset/UTC)
           nano (.getNano ^Instant inst)]
       (format "%04d-%02d-%02dT%02d:%02d:%02d.%09dZ"
               (.getYear zdt) (.getMonthValue zdt) (.getDayOfMonth zdt)
               (.getHour zdt) (.getMinute zdt) (.getSecond zdt) nano))))

#?(:cljs
   (defn- format-inst
     "Format a js/Date to canonical #inst string.
     Always 9 fractional digits (ms precision + 6 zeros), UTC Z suffix."
     [v]
     (when-not (instance? js/Date v)
       (err/unsupported-type! v))
     (let [pad (fn [n w] (let [s (str n)]
                           (str (apply str (repeat (- w (count s)) "0")) s)))
           y (.getUTCFullYear v)
           m (inc (.getUTCMonth v))
           d (.getUTCDate v)
           h (.getUTCHours v)
           mn (.getUTCMinutes v)
           sec (.getUTCSeconds v)
           millis (.getUTCMilliseconds v)]
       (str (pad y 4) "-" (pad m 2) "-" (pad d 2)
            "T" (pad h 2) ":" (pad mn 2) ":" (pad sec 2)
            "." (pad millis 3) "000000Z"))))

;; --- #uuid formatting ---

#?(:clj
   (defn- format-uuid
     "Format a UUID to canonical lowercase 8-4-4-4-12."
     [^UUID v]
     (.toLowerCase (.toString v))))

#?(:cljs
   (defn- format-uuid
     "Format a UUID to canonical lowercase 8-4-4-4-12."
     [v]
     (.toLowerCase (str v))))

;; --- #bytes formatting ---

(defn- format-bytes
  "Format a byte array as lowercase hex string."
  [value]
  (let [hex-char (fn [b]
                   #?(:clj  (format "%02x" (bit-and (int b) 0xff))
                      :cljs (-> (.toString (bit-and b 0xff) 16)
                                (.padStart 2 "0"))))]
    #?(:clj  (apply str (map hex-char (seq value)))
       :cljs (apply str (map (fn [i] (hex-char (aget value i)))
                             (range (.-length value)))))))

;; --- Core emit ---

(declare emit)

(defn- emit-elements
  "Emit a sequence of values separated by spaces."
  [^StringBuilder sb profile coll]
  (loop [first? true
         items (seq coll)]
    (when items
      (when-not first?
        (.append sb \space))
      (emit sb profile (first items))
      (recur false (next items)))))

(defn- emit-set
  "Emit a set: sort by rank, check for duplicates, emit."
  [^StringBuilder sb profile s]
  (.append sb "#{")
  (let [sorted (sort order/rank s)]
    ;; Check adjacent elements for duplicates
    (when (> (count sorted) 1)
      (doseq [[a b] (partition 2 1 sorted)]
        (when (= a b)
          (err/duplicate-element! a))))
    (emit-elements sb profile sorted))
  (.append sb \}))

(defn- emit-map
  "Emit a map: sort entries by key rank, check for duplicate keys, emit."
  [^StringBuilder sb profile m]
  (.append sb \{)
  (let [entries (sort-by first order/rank m)
        ks (map first entries)]
    ;; Check adjacent keys for duplicates
    (when (> (count entries) 1)
      (doseq [[a b] (partition 2 1 ks)]
        (when (= a b)
          (err/duplicate-key! a))))
    (loop [first? true
           entries (seq entries)]
      (when entries
        (when-not first?
          (.append sb \space))
        (let [[k v] (first entries)]
          (emit sb profile k)
          (.append sb \space)
          (emit sb profile v))
        (recur false (next entries)))))
  (.append sb \}))

(defn emit
  "Emit the canonical text of value into the StringBuilder sb.

  profile is :cedn-p or :cedn-r.

  Dispatches on type. Type universe is deliberately closed."
  [^StringBuilder sb profile value]
  (cond
    (nil? value)
    (.append sb "nil")

    (boolean? value)
    (.append sb (if value "true" "false"))

    (int? value)
    (do
      #?(:clj
         (when-not (and (>= (long value) -9223372036854775808)
                        (<= (long value) 9223372036854775807))
           (err/out-of-range! value)))
      (.append sb (str value)))

    #?(:clj  (instance? Double value)
       :cljs (and (number? value)
                  (not (int? value))))
    (.append sb (number/format-double value))

    (string? value)
    (emit-string sb value)

    (keyword? value)
    (let [ns (namespace value)
          n  (name value)]
      (.append sb \:)
      (when ns
        (.append sb ns)
        (.append sb \/))
      (.append sb n))

    (symbol? value)
    (let [ns (namespace value)
          n  (name value)]
      (when ns
        (.append sb ns)
        (.append sb \/))
      (.append sb n))

    (seq? value)
    (do
      (.append sb \()
      (emit-elements sb profile value)
      (.append sb \)))

    (vector? value)
    (do
      (.append sb \[)
      (emit-elements sb profile value)
      (.append sb \]))

    (set? value)
    (emit-set sb profile value)

    (map? value)
    (emit-map sb profile value)

    #?(:clj  (or (instance? Date value) (instance? Instant value))
       :cljs (instance? js/Date value))
    (do
      (.append sb "#inst \"")
      (.append sb (format-inst value))
      (.append sb \"))

    (uuid? value)
    (do
      (.append sb "#uuid \"")
      (.append sb (format-uuid value))
      (.append sb \"))

    #?(:clj  (bytes? value)
       :cljs (instance? js/Uint8Array value))
    (do
      (.append sb "#bytes \"")
      (.append sb (format-bytes value))
      (.append sb \"))

    :else
    (err/unsupported-type! value)))

(defn emit-str
  "Convenience: emit value to a new string."
  [profile value]
  (let [sb (#?(:clj StringBuilder. :cljs StringBuffer.))]
    (emit sb profile value)
    (.toString sb)))


(ns cedn.core
  "Canonical EDN — deterministic serialization for cryptographic use.

  Primary entry point:
    (canonical-bytes value)  → byte array

  Everything else is built on this."
  (:require [cedn.emit   :as emit]
            [cedn.order  :as order]
            [cedn.schema :as schema]
            [clojure.edn :as edn])
  #?(:clj (:import [java.security MessageDigest]
                   [java.time Instant]
                   [java.util UUID])))

(def version "1.2.0")

;; =============================================================
;; 1. Core canonicalization
;; =============================================================

(defn canonical-bytes
  "Canonicalize an EDN value to a UTF-8 byte array.

  Options:
    :profile  — :cedn-p (default) or :cedn-r
    :validate — if true, run schema validation before canonicalization"
  ([value]
   (canonical-bytes value {}))
  ([value {:keys [profile validate]
           :or   {profile :cedn-p validate false}}]
   (when validate
     (when-let [explanation (schema/explain profile value)]
       (throw (ex-info "CEDN type violation" explanation))))
   (let [s (emit/emit-str profile value)]
     #?(:clj  (.getBytes ^String s "UTF-8")
        :cljs (let [encoder (js/TextEncoder.)]
                (.encode encoder s))))))

(defn canonical-str
  "Canonicalize an EDN value to its canonical string representation.

  Same options as canonical-bytes."
  ([value]
   (canonical-str value {}))
  ([value {:keys [profile] :or {profile :cedn-p}}]
   (emit/emit-str profile value)))

;; =============================================================
;; 2. Validation
;; =============================================================

(defn valid?
  "Returns true if value consists exclusively of types allowed
  by the given profile."
  ([value]
   (valid? value {}))
  ([value {:keys [profile] :or {profile :cedn-p}}]
   (schema/valid? profile value)))

(defn explain
  "Returns nil if value is valid for the given profile, or an
  explanation map showing which sub-value violates the type contract."
  ([value]
   (explain value {}))
  ([value {:keys [profile] :or {profile :cedn-p}}]
   (schema/explain profile value)))

(defn assert!
  "Like valid?, but throws ex-info on failure."
  ([value]
   (assert! value {}))
  ([value opts]
   (when-let [explanation (explain value opts)]
     (throw (ex-info "CEDN type violation" explanation)))))

;; =============================================================
;; 3. Inspection
;; =============================================================

#?(:clj
   (defn- sha-256-hex
     "Compute SHA-256 hex digest of a byte array."
     [^bytes bs]
     (let [md (MessageDigest/getInstance "SHA-256")
           digest (.digest md bs)]
       (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

#?(:cljs
   (defn- sha-256-hex
     "SHA-256 not available synchronously in all CLJS envs.
     Returns nil when crypto is unavailable."
     [_bs]
     nil))

(defn inspect
  "Canonicalize with full diagnostics. Returns a map:

    {:status     :ok | :error
     :canonical  \"...\"
     :bytes      #bytes[...]
     :sha-256    \"a1b2c3...\"
     :errors     [{...} ...]
     :profile    :cedn-p}

  Never throws."
  ([value]
   (inspect value {}))
  ([value {:keys [profile] :or {profile :cedn-p}}]
   (try
     (let [bs (canonical-bytes value {:profile profile})
           s  #?(:clj (String. ^bytes bs "UTF-8")
                 :cljs (let [decoder (js/TextDecoder.)]
                         (.decode decoder bs)))]
       {:status    :ok
        :canonical s
        :bytes     bs
        :sha-256   (sha-256-hex bs)
        :errors    nil
        :profile   profile})
     (catch #?(:clj Exception :cljs :default) e
       {:status    :error
        :canonical nil
        :bytes     nil
        :sha-256   nil
        :errors    [(or (ex-data e)
                        {:message #?(:clj (.getMessage ^Exception e)
                                     :cljs (.-message e))})]
        :profile   profile}))))

;; =============================================================
;; 4. Canonical readers
;; =============================================================

(defn- hex->bytes
  "Parse a hex string into a byte array."
  [s]
  #?(:clj  (let [n (/ (count s) 2)
                 bs (byte-array n)]
             (dotimes [i n]
               (aset bs i (unchecked-byte
                           (Integer/parseInt (subs s (* i 2) (+ (* i 2) 2)) 16))))
             bs)
     :cljs (let [n (/ (count s) 2)
                 arr (js/Uint8Array. n)]
             (dotimes [i n]
               (aset arr i (js/parseInt (.substring s (* i 2) (+ (* i 2) 2)) 16)))
             arr)))

(def readers
  "EDN readers that produce canonical Clojure data types.

  Use with clojure.edn/read-string for precision-preserving round-trips:
    (edn/read-string {:readers cedn/readers} canonical-edn-str)"
  #?(:clj  {'inst  #(Instant/parse %)
            'uuid  #(UUID/fromString %)
            'bytes hex->bytes}
     ;; CLJS: built-in #uuid reader already produces cljs.core/UUID.
     ;; Only override #inst to ensure js/Date construction.
     :cljs {'inst  #(js/Date. %)
            'bytes hex->bytes}))

(defn canonical?
  "Given an EDN string, returns true if it is already in canonical form."
  ([edn-str]
   (canonical? edn-str {}))
  ([edn-str {:keys [profile] :or {profile :cedn-p}}]
   (try
     (let [value (edn/read-string {:readers readers} edn-str)
           result (canonical-str value {:profile profile})]
       (= edn-str result))
     (catch #?(:clj Exception :cljs :default) _
       false))))

;; =============================================================
;; 5. Re-exported from cedn.order
;; =============================================================

(def rank
  "Total ordering comparator over canonical EDN values.
  Implements Section 5 of the CEDN specification."
  order/rank)

