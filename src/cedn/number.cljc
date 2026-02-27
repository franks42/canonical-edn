(ns cedn.number
  "ECMAScript-compatible double formatting per ECMA-262 §7.1.12.1,
  with EDN .0 suffix adaptation.

  On JVM: delegates to org.erdtman.jcs.NumberToJSON.serializeNumber(),
  then applies .0 suffix rule and -0.0 normalization.

  On Babashka: pure Clojure reformatter post-processes Double/toString
  into ECMAScript format, then applies .0 suffix rule.

  On JS: trivial — Number.prototype.toString() IS the spec."
  (:require [cedn.error :as err]
            [clojure.string :as str])
  #?@(:bb []
      :clj [(:import [org.erdtman.jcs NumberToJSON])]))

#?(:clj
   ;; Used by :bb branch of format-double; invisible to clj-kondo's :clj analysis
   #_{:clj-kondo/ignore [:unused-private-var]}
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
  #?(:bb
     (do
       (when (or (Double/isNaN x) (Double/isInfinite x))
         (err/invalid-number! x))
       (if (and (zero? x) (neg? (Math/copySign 1.0 x)))
         "0.0"
         (let [s (ecma-reformat (Double/toString x))]
           (if (or (str/includes? s ".") (str/includes? s "e"))
             s
             (str s ".0")))))
     :clj
     (do
       (when (or (Double/isNaN x) (Double/isInfinite x))
         (err/invalid-number! x))
       (if (and (zero? x) (neg? (Math/copySign 1.0 x)))
         "0.0"
         (let [s (NumberToJSON/serializeNumber x)]
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
