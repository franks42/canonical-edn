(ns cedn.schema-test
  (:require [clojure.test :refer [deftest is are testing]]
            #?(:clj [cedn.test-util :as tu])
            [cedn.schema :as schema])
  #?(:clj (:import [java.util Date]
                   [java.time Instant])))

(deftest valid-scalars-test
  (testing "nil, booleans, numbers, strings, keywords, symbols"
    (are [v]
         (schema/valid? :cedn-p v)
      nil
      true
      false
      0
      42
      -7
      3.14
      1.0
      ""
      "hello"
      :foo
      :ns/bar
      'foo
      'ns/bar)))

(deftest valid-collections-test
  (testing "lists, vectors, sets, maps"
    (are [v]
         (schema/valid? :cedn-p v)
      '()
      '(1 2 3)
      []
      [1 2 3]
      #{}
      #{1 2 3}
      {}
      {:a 1 :b 2})))

(deftest valid-nested-test
  (is (schema/valid? :cedn-p {:a [1 2 #{:x :y}] :b '(true nil "hi")})))

#?(:clj
   (deftest valid-inst-uuid-test
     (testing "inst and uuid"
       (is (schema/valid? :cedn-p (Date.)))
       (is (schema/valid? :cedn-p (Instant/now)))
       (is (schema/valid? :cedn-p #uuid "29558297-e4b8-47af-bf3d-84942b5b40b8")))))

(deftest inst-range-test
  ;; valid?/explain agree with emit: an #inst outside years 0000-9999
  ;; cannot canonicalize (docs/review-devin-20260926.md, finding 4).
  (let [too-late #?(:clj (Instant/parse "+10000-01-01T00:00:00Z")
                    :cljs (js/Date. (js/Date.UTC 10000 0 1)))
        too-early #?(:clj (Instant/parse "-0001-12-31T00:00:00Z")
                     :cljs (let [d (js/Date. 0)] (.setUTCFullYear d -1) d))]
    (doseq [v [too-late too-early]]
      (is (not (schema/valid? :cedn-p v)))
      (is (= :cedn/out-of-range (:cedn/error (schema/explain :cedn-p v))))
      (is (= [:k 0] (:cedn/path (schema/explain :cedn-p {:k [v]})))))
    (is (schema/valid? :cedn-p #?(:clj (Instant/parse "9999-12-31T23:59:59.999999999Z")
                                  :cljs (js/Date. (js/Date.UTC 9999 11 31 23 59 59 999))))))
  #?(:cljs
     (testing "an invalid js/Date (NaN time)"
       (is (not (schema/valid? :cedn-p (js/Date. js/NaN))))))
  #?(:clj
     (do
       (testing "java.sql.Date/Time have no instant: not valid"
         (is (not (schema/valid? :cedn-p (java.sql.Date. 0))))
         (when-let [t (tu/sql-time)] (is (not (schema/valid? :cedn-p t))))
         (when-let [ts (tu/on-jvm '(java.sql.Timestamp. 0))]
           (is (schema/valid? :cedn-p ts))))
       (testing "any Date whose .toInstant throws is not an inst (Devin verification, nit 1)"
         (when-let [d (tu/on-jvm '(proxy [java.util.Date] []
                                    (toInstant [] (throw (UnsupportedOperationException.)))))]
           (is (not (schema/valid? :cedn-p d)))
           (is (= :cedn/unsupported-type (:cedn/error (schema/explain :cedn-p d)))))))))

(deftest valid-bytes-test
  (testing "byte arrays are valid"
    (is (schema/valid? :cedn-p #?(:clj (byte-array [1 2 3])
                                  :cljs (js/Uint8Array. #js [1 2 3]))))
    (is (schema/valid? :cedn-p #?(:clj (byte-array 0)
                                  :cljs (js/Uint8Array. 0))))))

(deftest invalid-types-test
  (testing "unsupported types"
    (are [v]
         (not (schema/valid? :cedn-p v))
      #?(:clj 22/7)
      #?(:clj 42N)
      ##NaN
      ##Inf
      ##-Inf)))

(deftest explain-returns-nil-for-valid-test
  (is (nil? (schema/explain :cedn-p {:a 1 :b [2 3]}))))

(deftest explain-returns-data-for-invalid-test
  (is (some? (schema/explain :cedn-p ##NaN))))

(deftest unknown-profile-throws-test
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (schema/schema-for :cedn-unknown))))

;; --- Lexical checks agree with emit (§3.5.4, §3.6, §3.7) ---

(deftest invalid-unicode-test
  (is (not (schema/valid? :cedn-p "\uD800")))
  (is (not (schema/valid? :cedn-p ["ok" "a\uDC00"])))
  (is (schema/valid? :cedn-p "😀"))
  (is (= {:cedn/error :cedn/invalid-unicode
          :cedn/value "\uD800"
          :cedn/path  [:s 1]}
         (schema/explain :cedn-p {:s ["ok" "\uD800"]}))))

(deftest invalid-name-test
  (are [v] (not (schema/valid? :cedn-p v))
    (symbol "nil")
    (symbol "1")
    (keyword "a b")
    (keyword "a/b" "c")
    #{(symbol "true")}
    {(keyword "") 1})
  (are [v] (schema/valid? :cedn-p v)
    (keyword "200")
    'clojure.core//
    'foo/nil)
  (let [e (schema/explain :cedn-p [:ok (symbol "nil")])]
    (is (= :cedn/invalid-name (:cedn/error e)))
    (is (= (symbol "nil") (:cedn/value e)))
    (is (= [1] (:cedn/path e)))
    (is (= "reserved literal name" (:cedn/reason e)))))
