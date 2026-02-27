(ns cedn.property-test
  (:require [clojure.test.check.properties :as prop #?@(:cljs [:include-macros true])]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.edn :as edn]
            [cedn.core :as cedn]
            [cedn.gen :as cgen])
  #?(:clj (:import [java.util Arrays])))

(def num-tests 200)

;; Property 1: Idempotency — canonical text is stable across roundtrips
(defspec canonical-idempotent num-tests
  (prop/for-all [v (cgen/gen-cedn-p {:max-size 3})]
                (let [s1 (cedn/canonical-str v)
                      v2 (edn/read-string s1)
                      s2 (cedn/canonical-str v2)]
                  (= s1 s2))))

;; Property 2: Valid EDN — canonical output can always be read back
(defspec canonical-is-valid-edn num-tests
  (prop/for-all [v (cgen/gen-cedn-p {:max-size 3})]
                (let [s (cedn/canonical-str v)
                      read-ok? (try (edn/read-string s) true
                                    (catch #?(:clj Exception :cljs :default) _ false))]
                  read-ok?)))

;; Property 3: Determinism — same value always produces same bytes
(defspec canonical-deterministic num-tests
  (prop/for-all [v (cgen/gen-cedn-p {:max-size 3})]
                (let [b1 (cedn/canonical-bytes v)
                      b2 (cedn/canonical-bytes v)]
                  #?(:clj  (Arrays/equals ^bytes b1 ^bytes b2)
                     :cljs (= (vec b1) (vec b2))))))

;; Property 4: canonical-str agrees with canonical-bytes
(defspec str-matches-bytes num-tests
  (prop/for-all [v (cgen/gen-cedn-p {:max-size 3})]
                (let [s (cedn/canonical-str v)
                      bs (cedn/canonical-bytes v)]
                  (= s #?(:clj  (String. ^bytes bs "UTF-8")
                          :cljs (.decode (js/TextDecoder.) bs))))))
