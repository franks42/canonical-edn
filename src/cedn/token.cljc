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
