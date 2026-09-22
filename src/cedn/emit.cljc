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
