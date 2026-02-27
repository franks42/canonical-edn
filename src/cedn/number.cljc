(ns cedn.number
  "ECMAScript-compatible double formatting per ECMA-262 §7.1.12.1,
  with EDN .0 suffix adaptation.

  On JVM: delegates to org.erdtman.jcs.NumberToJSON.serializeNumber(),
  then applies .0 suffix rule and -0.0 normalization.

  On JS: trivial — Number.prototype.toString() IS the spec."
  (:require [cedn.error :as err]
            [clojure.string :as str])
  #?(:clj (:import [org.erdtman.jcs NumberToJSON])))

(defn format-double
  "Format a double to its canonical string representation.

  - NaN, Infinity, -Infinity → throws :cedn/invalid-number
  - -0.0 → \"0.0\"
  - Integer-looking output → appends \".0\"
  - Otherwise → ECMAScript shortest representation

  Cross-platform: identical output on JVM and JS."
  [x]
  #?(:clj
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
