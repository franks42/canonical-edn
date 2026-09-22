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

(defn reader-error!
  "Throw :cedn/invalid-tag-form for tagged-literal text that cannot be
  read back as exactly one canonical value."
  [tag text reason]
  (throw (ex-info (str "CEDN: cannot read #" tag " literal: " reason)
                  {:cedn/error  :cedn/invalid-tag-form
                   :cedn/tag    tag
                   :cedn/value  text
                   :cedn/reason reason})))

(defn invalid-name!
  "Throw :cedn/invalid-name for keywords/symbols whose namespace or name
  cannot be serialized as a distinct EDN token."
  ([value reason]      (invalid-name! value reason nil))
  ([value reason path] (throw (ex-info "CEDN: invalid keyword or symbol name"
                                       {:cedn/error  :cedn/invalid-name
                                        :cedn/value  value
                                        :cedn/reason reason
                                        :cedn/path   path}))))

(defn invalid-tag-form!
  "Throw :cedn/invalid-tag-form for tagged literals that can't be canonicalized."
  ([value]      (invalid-tag-form! value nil))
  ([value path] (throw (ex-info "CEDN: invalid tagged literal"
                                {:cedn/error :cedn/invalid-tag-form
                                 :cedn/value value
                                 :cedn/path  path}))))


(ns cedn.token
  "Lexical validity of strings, keywords, and symbols (§3.5.4, §3.6, §3.7).

  Canonical text must be injective: two different values must never
  serialize to the same bytes.  Strings with unpaired surrogates and
  keyword/symbol names that contain EDN delimiters or collide with
  other token types (e.g. the symbol `nil`, or a keyword named \"a b\")
  break that property, so they are rejected.

  Not a public API — used by cedn.emit and cedn.schema."
  (:require [clojure.string :as str]))

(defn- code-at
  "UTF-16 code unit at index i."
  [s i]
  #?(:clj  (int (.charAt ^String s (int i)))
     :cljs (.charCodeAt s i)))

(defn- high-surrogate? [c] (and (>= c 0xD800) (<= c 0xDBFF)))
(defn- low-surrogate?  [c] (and (>= c 0xDC00) (<= c 0xDFFF)))
(defn- digit?          [c] (and (>= c 0x30) (<= c 0x39)))

(defn well-formed-unicode?
  "True if s contains no unpaired UTF-16 surrogates."
  [s]
  (let [n (count s)]
    (loop [i 0]
      (if (< i n)
        (let [c (code-at s i)]
          (cond
            (high-surrogate? c)
            (if (and (< (inc i) n) (low-surrogate? (code-at s (inc i))))
              (recur (+ i 2))
              false)

            (low-surrogate? c) false
            :else              (recur (inc i))))
        true))))

(defn- forbidden-char?
  "Characters that may not appear anywhere in a keyword/symbol component:
  whitespace, control characters, comma (EDN whitespace), the solidus
  (namespace separator), and EDN/Clojure delimiters and macro characters."
  [c]
  (or (<= c 0x20)
      (= c 0x7F)
      (case (int c)
        (0x22 0x28 0x29 0x2C 0x2F 0x3B 0x40 0x5B 0x5C 0x5D 0x5E 0x60 0x7B 0x7D 0x7E) true
        false)))

(defn- component-error
  "Returns nil if s is a valid namespace or name component, else a
  short reason string.  leading-rules? applies the symbol rules for
  the first character (everything except an unqualified keyword name)."
  [s leading-rules?]
  (let [n (count s)]
    (cond
      (zero? n)                        "empty component"
      (not (well-formed-unicode? s))   "unpaired surrogate"
      (some #(forbidden-char? (code-at s %)) (range n))
      "whitespace, delimiter, or reserved character"
      (= 0x3A (code-at s 0))           "leading colon"
      (= 0x3A (code-at s (dec n)))     "trailing colon"
      (str/includes? s "::")           "double colon"

      (not leading-rules?)             nil
      (digit? (code-at s 0))           "leading digit"
      (= 0x23 (code-at s 0))           "leading #"
      (and (> n 1)
           (case (int (code-at s 0)) (0x2B 0x2D 0x2E) true false)
           (digit? (code-at s 1)))     "leading +, -, or . followed by a digit"
      :else                            nil)))

(defn- named-error
  "Shared keyword/symbol check.  The name \"/\" is always allowed."
  [ns-part name-part keyword?]
  (or (when ns-part (component-error ns-part true))
      (when-not (= "/" name-part)
        (component-error name-part (or (some? ns-part) (not keyword?))))))

(defn keyword-error
  "Returns nil if kw can be serialized as a distinct EDN keyword token,
  else a short reason string."
  [kw]
  (named-error (namespace kw) (name kw) true))

(defn symbol-error
  "Returns nil if sym can be serialized as a distinct EDN symbol token,
  else a short reason string."
  [sym]
  (let [ns-part   (namespace sym)
        name-part (name sym)]
    (if (and (nil? ns-part) (#{"nil" "true" "false"} name-part))
      "reserved literal name"
      (named-error ns-part name-part false))))


(ns cedn.number
  "ECMAScript-compatible double formatting per ECMA-262 §7.1.12.1,
  with EDN .0 suffix adaptation.

  On JVM/Babashka: pure Clojure reformatter post-processes Double/toString
  (JDK 19+ Schubfach) into ECMAScript format, then applies .0 suffix rule.

  REQUIRES JDK 19 or newer.  Double/toString only became guaranteed
  shortest-round-trip in JDK 19 (JDK-4511638); on older JDKs it can
  emit extra digits (e.g. -36897728365593032.0 where JS and JDK 19+
  give -36897728365593030.0), so canonical bytes would diverge from
  every other platform.  test/cedn/number-reference.edn catches this.

  On JS: trivial — Number.prototype.toString() IS the spec."
  (:require [cedn.error :as err]
            [clojure.string :as str]))

#?(:clj
   (defn- ecma-reformat
     "Reformat Double/toString output (JDK 19+ Schubfach) into
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

#?(:clj
   ;; Fail fast at load time rather than emit bytes that silently differ
   ;; from every other platform.  Probes the actual defect instead of
   ;; comparing version strings, so a runtime whose reported java.version
   ;; does not match its formatter (e.g. a Babashka native image) is
   ;; judged on behaviour.  On JDK 19+ this is "3.689772836559303E16";
   ;; before JDK 19, Double/toString returns a longer, non-shortest form.
   (let [actual (Double/toString 3.6897728365593032E16)]
     (when-not (= "3.689772836559303E16" actual)
       (throw (ex-info
               (str "cedn requires JDK 19 or newer. This JVM ("
                    (System/getProperty "java.version")
                    ") does not produce shortest round-trip doubles: "
                    "Double/toString gave " actual
                    " instead of 3.689772836559303E16 (JDK-4511638). "
                    "Canonical bytes from this JVM would differ from "
                    "JavaScript, Babashka and nbb, so cedn refuses to load.")
               {:cedn/error       :cedn/unsupported-runtime
                :cedn/java-version (System/getProperty "java.version")
                :cedn/expected    "3.689772836559303E16"
                :cedn/actual      actual})))))

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

#?(:clj
   (defn- exact-decimal
     "Exact BigDecimal value of an integer or double (no rounding)."
     [x]
     (if (int? x)
       (java.math.BigDecimal/valueOf (long x))
       (java.math.BigDecimal. (double x)))))

(defn- compare-numbers
  "Compare two numbers by exact mathematical value.
  When equal, integer ranks before double.

  On the JVM, integers are never widened to double: distinct longs
  above 2^53 would compare equal, and their order would then depend
  on the input order (§5.3.3).  On JS every number is a double, so
  plain comparison is already exact."
  [a b]
  (let [a-int? (int? a)
        b-int? (int? b)
        cmp #?(:clj  (cond
                       (and a-int? b-int?) (compare (long a) (long b))
                       (or a-int? b-int?)  (compare (exact-decimal a) (exact-decimal b))
                       :else               (compare (double a) (double b)))
               :cljs (compare a b))]
    (if (zero? cmp)
      ;; Same mathematical value: int < double
      (cond
        (and a-int? (not b-int?)) -1
        (and (not a-int?) b-int?)  1
        :else                      0)
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

#?(:clj
   (defn- ->instant
     "Normalize java.util.Date / java.time.Instant to Instant."
     ^java.time.Instant [v]
     (if (instance? java.time.Instant v)
       v
       (.toInstant ^java.util.Date v))))

(defn- compare-tagged
  "Compare two tagged values (bytes/inst/uuid).
  First by tag-kind (alphabetical), then by value within same kind.

  Within a kind, the order matches the canonical text of the value:
  #inst is chronological (epoch seconds, then nanos) and #uuid is by
  its lowercase string.  Never compare platform toString output —
  Date.toString is timezone- and locale-dependent (§5.3.10)."
  [a b]
  (let [ka (tag-kind a)
        kb (tag-kind b)]
    (if (not= ka kb)
      (compare ka kb)
      (case ka
        :bytes (compare-bytes a b)
        :inst  #?(:clj  (compare (->instant a) (->instant b))
                  :cljs (compare (.getTime a) (.getTime b)))
        :uuid  (compare (.toLowerCase (str a)) (.toLowerCase (str b)))
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
  Simple recursive walk over the closed CEDN-P type set."
  (:require [cedn.token :as token]))

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
    (string? v)  (token/well-formed-unicode? v)
    (keyword? v) (nil? (token/keyword-error v))
    (symbol? v)  (nil? (token/symbol-error v))
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
    (string? v)     (when-not (token/well-formed-unicode? v)
                      {:cedn/error :cedn/invalid-unicode
                       :cedn/value v
                       :cedn/path  path})
    (keyword? v)    (when-let [reason (token/keyword-error v)]
                      {:cedn/error  :cedn/invalid-name
                       :cedn/value  v
                       :cedn/reason reason
                       :cedn/path   path})
    (symbol? v)     (when-let [reason (token/symbol-error v)]
                      {:cedn/error  :cedn/invalid-name
                       :cedn/value  v
                       :cedn/reason reason
                       :cedn/path   path})
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
            [cedn.token  :as token]
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
  "Emit a canonical string with proper escaping.
  Unpaired surrogates have no UTF-8 encoding (§3.5.4) — the platform
  encoder would silently replace them, colliding with \"?\" or U+FFFD."
  [^StringBuilder sb s]
  (when-not (token/well-formed-unicode? s)
    (err/invalid-unicode! s))
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
           nano (.getNano ^Instant inst)
           year (.getYear zdt)]
       ;; RFC 3339 has exactly four year digits (§3.12)
       (when-not (<= 0 year 9999)
         (err/out-of-range! v))
       (format "%04d-%02d-%02dT%02d:%02d:%02d.%09dZ"
               year (.getMonthValue zdt) (.getDayOfMonth zdt)
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
           _ (when-not (and (>= y 0) (<= y 9999))
               ;; RFC 3339 has exactly four year digits (§3.12)
               (err/out-of-range! v))
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

(declare emit emit-str)

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

(defn- sort-canonical
  "Canonicalize (key-fn item) for each item and sort by rank.
  Returns [canonical-text item] pairs in canonical order.

  Duplicates are items whose keys have identical canonical text, not
  just (=) keys: a java.util.Date and a java.time.Instant for the same
  moment, two byte arrays with the same content, or a record and a map
  with the same entries are not (=) but serialize identically (§3.10,
  §3.11).  Rank ties are broken by canonical text so the output never
  depends on input iteration order, even if rank were not total."
  [profile key-fn dup! items]
  (let [pairs (sort (fn [[sa a] [sb b]]
                      (let [c (order/rank (key-fn a) (key-fn b))]
                        (if (zero? c) (compare sa sb) c)))
                    (map (fn [item] [(emit-str profile (key-fn item)) item])
                         items))]
    (doseq [[[sa a] [sb _]] (partition 2 1 pairs)]
      (when (= sa sb)
        (dup! (key-fn a))))
    pairs))

(defn- emit-set
  "Emit a set: sort by rank, reject duplicate canonical forms, emit."
  [^StringBuilder sb profile s]
  (.append sb "#{")
  (loop [first? true
         pairs (seq (sort-canonical profile identity err/duplicate-element! s))]
    (when pairs
      (when-not first?
        (.append sb \space))
      (.append sb ^String (ffirst pairs))
      (recur false (next pairs))))
  (.append sb \}))

(defn- emit-map
  "Emit a map: sort entries by key rank, reject duplicate canonical keys, emit."
  [^StringBuilder sb profile m]
  (.append sb \{)
  (loop [first? true
         pairs (seq (sort-canonical profile key err/duplicate-key! m))]
    (when pairs
      (when-not first?
        (.append sb \space))
      (let [[k-str entry] (first pairs)]
        (.append sb ^String k-str)
        (.append sb \space)
        (emit sb profile (val entry)))
      (recur false (next pairs))))
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
      (when-let [reason (token/keyword-error value)]
        (err/invalid-name! value reason))
      (.append sb \:)
      (when ns
        (.append sb ns)
        (.append sb \/))
      (.append sb n))

    (symbol? value)
    (let [ns (namespace value)
          n  (name value)]
      (when-let [reason (token/symbol-error value)]
        (err/invalid-name! value reason))
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


(ns cedn.reader
  "Readers for the CEDN tagged literals (#inst, #bytes).

  Reading is the inverse of canonicalization, so it is strict: input
  that cannot be read back as exactly one value is rejected rather
  than silently truncated (spec §8.2).

  The #inst grammar is parsed by shared code on every platform.  The
  platform readers disagree otherwise: java.time.Instant/parse rejects
  `#inst \"2020-01-01\"`, which is valid EDN, and JavaScript reads
  `new Date(\"2020-01-01T10:20\")` as local time where EDN means UTC.

  Not a public API — use cedn.core/readers."
  (:require [cedn.error :as err])
  #?(:clj (:import [java.time LocalDateTime ZoneOffset])))

;; --- #bytes ---

(def ^:private hex-pattern #"(?:[0-9a-fA-F]{2})*")

(defn hex->bytes
  "Parse a hex string into a byte array (byte[] / js/Uint8Array).

  Requires an even number of hexadecimal digits.  Canonical output is
  lowercase; uppercase is accepted when reading."
  [s]
  (when-not (string? s)
    (err/reader-error! "bytes" s "expected a string"))
  (when-not (re-matches hex-pattern s)
    (err/reader-error! "bytes" s
                       (if (odd? (count s))
                         "odd number of hex digits"
                         "not a lowercase or uppercase hex string")))
  (let [n (quot (count s) 2)
        digits (fn [i] (subs s (* i 2) (+ (* i 2) 2)))]
    #?(:clj  (let [bs (byte-array n)]
               (dotimes [i n]
                 (aset bs i (unchecked-byte (Integer/parseInt (digits i) 16))))
               bs)
       :cljs (let [arr (js/Uint8Array. n)]
               (dotimes [i n]
                 (aset arr i (js/parseInt (digits i) 16)))
               arr))))

;; --- #inst ---

;; The EDN timestamp grammar: yyyy, yyyy-MM, yyyy-MM-dd, with optional
;; THH:mm[:ss[.fraction]] and optional Z or ±HH:mm offset.  Matches the
;; grammar clojure.instant implements; a missing offset means UTC.
(def ^:private timestamp-pattern
  #"(\d\d\d\d)(?:-(\d\d)(?:-(\d\d)(?:[T](\d\d)(?::(\d\d)(?::(\d\d)(?:[.](\d+))?)?)?)?)?)?(?:[Zz]|([-+])(\d\d):(\d\d))?")

(defn- parse-long*
  [s]
  #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))

(defn- leap-year? [y]
  (and (zero? (mod y 4))
       (or (pos? (mod y 100)) (zero? (mod y 400)))))

(defn- days-in-month [y m]
  (case (int m)
    (1 3 5 7 8 10 12) 31
    (4 6 9 11)        30
    2                 (if (leap-year? y) 29 28)
    0))

(defn- nanos-of
  "A fractional-second string to nanoseconds, zero-padded or truncated
  to 9 digits."
  [frac]
  (if frac
    (let [padded (subs (str frac "00000000") 0 9)]
      (parse-long* padded))
    0))

(defn parse-inst
  "Parse an EDN timestamp string into the platform inst type:
  java.time.Instant (nanosecond precision) on the JVM, js/Date
  (millisecond precision) on JavaScript.

  Accepts the full EDN grammar, not just canonical output, so input
  from other tools reads correctly.  A missing offset means UTC on
  every platform."
  [s]
  (when-not (string? s)
    (err/reader-error! "inst" s "expected a string"))
  (let [m (re-matches timestamp-pattern s)]
    (when-not m
      (err/reader-error! "inst" s "not an RFC 3339 / EDN timestamp"))
    (let [[_ y mo d h mi sec frac off-sign off-h off-mi] m
          y      (parse-long* y)
          mo     (if mo (parse-long* mo) 1)
          d      (if d (parse-long* d) 1)
          h      (if h (parse-long* h) 0)
          mi     (if mi (parse-long* mi) 0)
          sec    (if sec (parse-long* sec) 0)
          nanos  (nanos-of frac)
          off-h  (if off-h (parse-long* off-h) 0)
          off-mi (if off-mi (parse-long* off-mi) 0)
          fail!  (fn [reason] (err/reader-error! "inst" s reason))]
      (cond
        (not (<= 1 mo 12))                 (fail! "month out of range")
        (not (<= 1 d (days-in-month y mo))) (fail! "day out of range for month")
        (not (<= 0 h 23))                  (fail! "hour out of range")
        (not (<= 0 mi 59))                 (fail! "minute out of range")
        ;; A leap second has no instant on either platform: java.time
        ;; rejects it and JS silently rolls into the next minute.
        (= 60 sec)                         (fail! "leap second is not representable")
        (not (<= 0 sec 59))                (fail! "second out of range")
        (not (<= 0 off-h 23))              (fail! "offset hour out of range")
        (not (<= 0 off-mi 59))             (fail! "offset minute out of range"))
      (let [off-minutes (* (if (= "-" off-sign) -1 1)
                           (+ (* 60 off-h) off-mi))]
        #?(:clj
           (-> (LocalDateTime/of (int y) (int mo) (int d)
                                 (int h) (int mi) (int sec) (int nanos))
               (.toInstant (ZoneOffset/ofTotalSeconds (* 60 off-minutes))))
           :cljs
           ;; Date.UTC maps years 0-99 to 1900+y, so set the year explicitly.
           (let [ms (quot nanos 1000000)
                 dt (js/Date. (js/Date.UTC y (dec mo) d h mi sec ms))]
             (.setUTCFullYear dt y)
             (js/Date. (- (.getTime dt) (* 60000 off-minutes)))))))))


(ns cedn.core
  "Canonical EDN — deterministic serialization for cryptographic use.

  Primary entry point:
    (canonical-bytes value)  → byte array

  Everything else is built on this."
  (:require [cedn.emit   :as emit]
            [cedn.order  :as order]
            [cedn.reader :as reader]
            [cedn.schema :as schema]
            [clojure.edn :as edn])
  #?(:clj (:import [java.security MessageDigest]
                   [java.util UUID])))

(def version "1.4.0")

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

(def readers
  "EDN readers that produce canonical Clojure data types.

  Use with clojure.edn/read-string for precision-preserving round-trips:
    (edn/read-string {:readers cedn/readers} canonical-edn-str)

  #inst yields java.time.Instant (nanosecond precision) on the JVM and
  js/Date on JS; the full EDN timestamp grammar is accepted and parsed
  identically on every platform.  #bytes requires an even-length hex
  string.  Malformed input throws rather than reading a partial value."
  #?(:clj  {'inst  reader/parse-inst
            'uuid  #(UUID/fromString %)
            'bytes reader/hex->bytes}
     ;; CLJS: built-in #uuid reader already produces cljs.core/UUID.
     :cljs {'inst  reader/parse-inst
            'bytes reader/hex->bytes}))

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

