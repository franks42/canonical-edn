(ns cedn.gen
  "Property-based testing generators for CEDN values.

  Built on clojure.test.check.generators — generates arbitrary
  values that conform to the CEDN-P type contract."
  (:require [clojure.test.check.generators :as gen])
  #?(:clj (:import [java.util Date UUID])))

;; Custom generators for types that test.check can't generate natively

(def gen-finite-double
  "Generator for finite, non-NaN doubles."
  (gen/such-that
   #?(:clj  #(Double/isFinite %)
      :cljs #(js/isFinite %))
   (gen/double* {:NaN? false :infinite? false})
   100))

#?(:clj
   (def gen-inst
     "Generator for java.util.Date values."
     (gen/fmap
      (fn [ms] (Date. ^long ms))
      (gen/choose 0 4102444800000)))

   :cljs
   (def gen-inst
     "Generator for js/Date values."
     (gen/fmap
      (fn [ms] (js/Date. ms))
      (gen/choose 0 4102444800000))))

#?(:clj
   (def gen-uuid
     "Generator for java.util.UUID values."
     (gen/fmap
      (fn [_] (UUID/randomUUID))
      (gen/return nil)))

   :cljs
   (def gen-uuid
     "Generator for cljs.core/UUID values."
     (gen/fmap
      (fn [_] (random-uuid))
      (gen/return nil))))

;; EDN keywords and symbols must have non-empty names that start with
;; an alphabetic character. gen/string-alphanumeric can produce ""
;; which creates invalid tokens like `:`.
(def ^:private gen-edn-name
  "Generator for valid EDN keyword/symbol names."
  (gen/fmap
   (fn [[c s]] (str c s))
   (gen/tuple
    (gen/fmap char (gen/choose 97 122))
    gen/string-alphanumeric)))

(def gen-cedn-p-leaf
  "Generator for CEDN-P leaf values."
  (gen/one-of
   [(gen/return nil)
    gen/boolean
    (gen/large-integer* {:min -9223372036854775808
                         :max  9223372036854775807})
    gen-finite-double
    gen/string-alphanumeric
    (gen/fmap keyword gen-edn-name)
    (gen/fmap symbol gen-edn-name)
    gen-inst
    gen-uuid]))

(defn gen-cedn-p
  "Returns a test.check generator that produces arbitrary CEDN-P values.

  Options:
    :max-size  — maximum collection size (default 5)"
  ([] (gen-cedn-p {}))
  ([{:keys [max-size]
     :or   {max-size 5}}]
   (gen/recursive-gen
    (fn [inner]
      (gen/one-of
       [(gen/list inner)
        (gen/vector inner 0 max-size)
        (gen/set inner {:max-elements max-size})
        (gen/map
         ;; Use only hashable leaf types as keys
         (gen/one-of
          [(gen/return nil)
           gen/boolean
           (gen/large-integer* {:min -1000 :max 1000})
           gen/string-alphanumeric
           (gen/fmap keyword gen-edn-name)
           (gen/fmap symbol gen-edn-name)])
         inner
         {:max-elements max-size})]))
    gen-cedn-p-leaf)))
