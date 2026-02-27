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

;; --- String escaping per §3.5 ---

(defn- escape-control-char
  "Format a control char as \\uNNNN."
  [c]
  #?(:clj  (format "\\u%04x" (int c))
     :cljs (str "\\u" (.padStart (.toString (int c) 16) 4 "0"))))

(defn- emit-string-char
  "Append the canonical escape for a single character."
  [^StringBuilder sb ch]
  (let [c (int ch)]
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

    #?(:clj  (instance? UUID value)
       :cljs (instance? cljs.core/UUID value))
    (do
      (.append sb "#uuid \"")
      (.append sb (format-uuid value))
      (.append sb \"))

    :else
    (err/unsupported-type! value)))

(defn emit-str
  "Convenience: emit value to a new string."
  [profile value]
  (let [sb (#?(:clj StringBuilder. :cljs StringBuffer.))]
    (emit sb profile value)
    (.toString sb)))
