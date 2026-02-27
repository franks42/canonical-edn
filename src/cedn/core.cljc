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
        :errors    [(if (instance? #?(:clj clojure.lang.ExceptionInfo
                                      :cljs ExceptionInfo) e)
                      (ex-data e)
                      {:message #?(:clj (.getMessage ^Exception e)
                                   :cljs (.-message e))})]
        :profile   profile}))))

;; =============================================================
;; 4. Canonical readers
;; =============================================================

(def readers
  "EDN readers that produce canonical Clojure data types.

  Use with clojure.edn/read-string for precision-preserving round-trips:
    (edn/read-string {:readers cedn/readers} canonical-edn-str)"
  #?(:clj  {'inst #(Instant/parse %)
            'uuid #(UUID/fromString %)}
     :cljs {'inst #(js/Date. %)
            'uuid cljs.core/uuid}))

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
