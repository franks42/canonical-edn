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
  #?(:clj (:import [java.security MessageDigest])))

(def version "1.5.0")

;; =============================================================
;; 1. Core canonicalization
;; =============================================================

(defn canonical-bytes
  "Canonicalize an EDN value to a UTF-8 byte array.

  Options:
    :profile  — :cedn-p (the only implemented profile)
    :validate — if true, run schema validation before canonicalization

  Throws on any other profile rather than quietly producing CEDN-P
  bytes for a caller who asked for something else (spec §8.6)."
  ([value]
   (canonical-bytes value {}))
  ([value {:keys [profile validate]
           :or   {profile :cedn-p validate false}}]
   (schema/schema-for profile)
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
   (schema/schema-for profile)
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
     (schema/schema-for profile)
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
  identically on every platform.  #uuid requires canonical 8-4-4-4-12
  form and #bytes an even-length hex string.  Malformed input throws
  rather than reading a partial or different value."
  {'inst  reader/parse-inst
   'uuid  reader/parse-uuid*
   'bytes reader/hex->bytes})

(defn canonical?
  "Given an EDN string, returns true if it is already in canonical form."
  ([edn-str]
   (canonical? edn-str {}))
  ([edn-str {:keys [profile] :or {profile :cedn-p}}]
   (schema/schema-for profile)
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
