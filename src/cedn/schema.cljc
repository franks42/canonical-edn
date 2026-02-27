(ns cedn.schema
  "Malli schemas for CEDN-P type contracts.
  Adapted from docs/cedn-p-schema.cljc."
  (:require [malli.core :as m]
            [malli.registry :as mr]))

;; --- Leaf types ---

(def cedn-p-integer
  [:and :int
   [:>= -9223372036854775808]
   [:<= 9223372036854775807]])

(def cedn-p-double
  [:and :double [:fn {:error/message "must be finite (no NaN/Infinity)"}
                 #?(:clj  #(Double/isFinite %)
                    :cljs #(js/isFinite %))]])

(def cedn-p-inst
  #?(:clj  [:fn {:error/message "must be #inst (java.util.Date or java.time.Instant)"}
            #(or (instance? java.util.Date %)
                 (instance? java.time.Instant %))]
     :cljs [:fn {:error/message "must be #inst (js/Date)"}
            #(instance? js/Date %)]))

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
   ::list    [:and [:fn seq?] [:sequential [:ref ::value]]]
   ::vector  [:vector [:ref ::value]]
   ::set     [:set [:ref ::value]]
   ::map     [:map-of [:ref ::value] [:ref ::value]]})

(def cedn-p-value
  (m/schema [:ref ::value]
            {:registry (mr/composite-registry
                        (m/default-schemas)
                        cedn-p-registry)}))

;; --- Public API ---

(defn schema-for
  "Returns the Malli schema for the given profile keyword."
  [profile]
  (case profile
    :cedn-p cedn-p-value
    (throw (ex-info (str "Unknown CEDN profile: " profile)
                    {:profile profile}))))

(defn valid?
  "Schema-level type check. Fast, no canonicalization."
  [profile value]
  (m/validate (schema-for profile) value))

(defn explain
  "Schema-level explanation. Returns nil or error map."
  [profile value]
  (m/explain (schema-for profile) value))
