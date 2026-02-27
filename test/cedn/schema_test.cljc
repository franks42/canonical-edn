(ns cedn.schema-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cedn.schema :as schema])
  #?(:clj (:import [java.util Date UUID]
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
       (is (schema/valid? :cedn-p (UUID/randomUUID))))))

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
