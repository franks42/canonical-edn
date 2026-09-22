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

;; --- #uuid ---

(def ^:private uuid-pattern
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

#?(:cljs
   (def ^:private uuid-ctor
     ;; Scittle's sci runtime exposes neither cljs.core/uuid nor the UUID
     ;; constructor, but it does have random-uuid — so take the
     ;; constructor from an instance and build UUIDs through it.
     (.-constructor (random-uuid))))

(defn parse-uuid*
  "Parse a UUID string in canonical 8-4-4-4-12 form.

  java.util.UUID/fromString accepts sloppy input such as \"1-2-3-4-5\"
  and cljs.core/uuid accepts anything at all; both would read a value
  that is not what the text says (spec §3.13)."
  [s]
  (when-not (and (string? s) (re-matches uuid-pattern s))
    (err/reader-error! "uuid" s "not a 8-4-4-4-12 hex UUID"))
  #?(:clj  (java.util.UUID/fromString s)
     ;; lowercased to match canonical form, as cljs.core/uuid does
     :cljs (js/Reflect.construct uuid-ctor #js [(.toLowerCase s) nil])))

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
