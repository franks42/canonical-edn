;; ============================================================
;; CEDN-P Type Contract — Malli
;; ============================================================
;; 
;; Validates that a value consists exclusively of types allowed
;; by the CEDN-P (Portable) profile.  Any value passing this
;; schema can be deterministically canonicalized across JVM,
;; ClojureScript, Babashka, and Node.js.
;;
;; Usage:
;;   (require '[malli.core :as m])
;;   (m/validate cedn-p-value {:a 1 :b "hello"})  ;=> true
;;   (m/validate cedn-p-value 22/7)                ;=> false
;;   (m/explain  cedn-p-value 42N)                 ;=> {...}
;; ============================================================

(ns cedn.schema.malli
  (:require [malli.core :as m]
            [malli.registry :as mr]))

;; --- Leaf types ---

;; CEDN-P integers: 64-bit signed range
;; On JVM this is naturally Long; on ClojureScript we need the
;; range check since all numbers are doubles.
(def cedn-p-integer
  [:and :int
   [:>= -9223372036854775808]
   [:<= 9223372036854775807]])

;; CEDN-P doubles: finite, non-NaN
;; NaN, Infinity, -Infinity are rejected by the canonicalizer,
;; but we catch them here at the schema level.
(def cedn-p-double
  [:and :double [:fn {:error/message "must be finite (no NaN/Infinity)"}
                 #?(:clj  #(Double/isFinite %)
                    :cljs #(js/isFinite %))]])

;; #inst — java.util.Date or java.time.Instant on JVM, js/Date on ClojureScript
(def cedn-p-inst
  #?(:clj  [:fn {:error/message "must be #inst (java.util.Date or java.time.Instant)"}
            #(or (instance? java.util.Date %)
                 (instance? java.time.Instant %))]
     :cljs [:fn {:error/message "must be #inst (js/Date)"}
            #(instance? js/Date %)]))

;; #uuid — java.util.UUID on JVM, cljs.core/UUID on ClojureScript
(def cedn-p-uuid
  #?(:clj  [:fn {:error/message "must be #uuid (java.util.UUID)"}
            #(instance? java.util.UUID %)]
     :cljs [:fn {:error/message "must be #uuid (cljs.core/UUID)"}
            #(instance? cljs.core/UUID %)]))

;; --- Recursive schema via registry ---

(def cedn-p-registry
  {::value   [:or
              :nil
              :boolean
              [:ref ::integer]
              [:ref ::double]
              :string
              :keyword
              :symbol
              [:ref ::list]
              [:ref ::vector]
              [:ref ::set]
              [:ref ::map]
              [:ref ::inst]
              [:ref ::uuid]]
   ::integer cedn-p-integer
   ::double  cedn-p-double
   ::inst    cedn-p-inst
   ::uuid    cedn-p-uuid
   ::list    [:sequential [:ref ::value]]     ;; seqs render as lists
   ::vector  [:vector [:ref ::value]]
   ::set     [:set [:ref ::value]]
   ::map     [:map-of [:ref ::value] [:ref ::value]]})

;; The top-level schema
(def cedn-p-value
  (m/schema [:ref ::value]
            {:registry (mr/composite-registry
                        (m/default-schemas)
                        cedn-p-registry)}))

;; --- Convenience API ---

(defn valid?
  "Returns true if v consists exclusively of CEDN-P types."
  [v]
  (m/validate cedn-p-value v))

(defn explain
  "Returns nil if valid, or an explanation map showing which
   sub-value violates the CEDN-P type contract."
  [v]
  (m/explain cedn-p-value v))


;; ============================================================
;; CEDN-P Type Contract — clojure.spec
;; ============================================================
;;
;; Same contract expressed in clojure.spec.alpha.
;; Works on JVM and ClojureScript (with appropriate reader
;; conditionals for inst/uuid checks).
;;
;; Usage:
;;   (require '[clojure.spec.alpha :as s])
;;   (s/valid? ::cedn-p-value {:a 1 :b "hello"})  ;=> true
;;   (s/valid? ::cedn-p-value 22/7)                ;=> false
;;   (s/explain ::cedn-p-value 42N)                ;=> ...
;; ============================================================

(comment  ;; in a separate ns: cedn.schema.spec

(ns cedn.schema.spec
  (:require [clojure.spec.alpha :as s]))

;; --- Leaf predicates ---

(defn cedn-p-integer?
  "64-bit signed integer (Long on JVM, safe integer on JS)."
  [x]
  (and (int? x)
       (<= -9223372036854775808 x 9223372036854775807)))

(defn cedn-p-double?
  "Finite IEEE 754 double (no NaN, no Infinity)."
  [x]
  (and #?(:clj  (instance? Double x)
          :cljs (and (number? x) (not (int? x))))
       #?(:clj  (Double/isFinite x)
          :cljs (js/isFinite x))))

(defn inst? [x]
  #?(:clj  (instance? java.util.Date x)
     :cljs (instance? js/Date x)))

(defn uuid? [x]
  #?(:clj  (instance? java.util.UUID x)
     :cljs (instance? cljs.core/UUID x)))

;; --- Recursive spec ---

(s/def ::cedn-p-value
  (s/or :nil       nil?
        :boolean   boolean?
        :integer   cedn-p-integer?
        :double    cedn-p-double?
        :string    string?
        :keyword   keyword?
        :symbol    symbol?
        :inst      inst?
        :uuid      uuid?
        :list      (s/and seq?    (s/coll-of ::cedn-p-value))
        :vector    (s/and vector? (s/coll-of ::cedn-p-value))
        :set       (s/and set?    (s/coll-of ::cedn-p-value))
        :map       (s/and map?    (s/every-kv ::cedn-p-value ::cedn-p-value))))

) ;; end comment


;; ============================================================
;; Comparison notes
;; ============================================================
;;
;; Malli advantages for CEDN-P:
;;   - Schema is data → can be serialized as EDN, shipped, inspected
;;   - Cross-platform: identical behavior JVM + ClojureScript
;;   - Better error messages out of the box (humanized)
;;   - Composable: easy to extend for CEDN-R by adding types
;;   - Transformation support (could coerce values to CEDN-P)
;;
;; clojure.spec advantages:
;;   - Built into Clojure (no dependency)
;;   - Generative testing via test.check integration
;;   - More established in the ecosystem
;;
;; Recommendation: Use Malli for the library's public contract.
;; Optionally provide spec aliases for teams already using spec.
