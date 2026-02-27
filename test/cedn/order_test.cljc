(ns cedn.order-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cedn.order :as order]))

(deftest type-priority-test
  (testing "type priorities"
    (are [v expected]
         (= expected (order/type-priority v))
      nil      0
      false    1
      true     1
      42       2
      3.14     2
      "hello"  3
      :foo     4
      'bar     5
      '()      6
      [1]      7
      #{}      8
      {}       9)))

(deftest rank-nil-test
  (is (zero? (order/rank nil nil))))

(deftest rank-boolean-test
  (is (neg? (order/rank false true)))
  (is (pos? (order/rank true false)))
  (is (zero? (order/rank true true))))

(deftest rank-numbers-test
  (testing "integers by value"
    (is (neg? (order/rank 1 2)))
    (is (pos? (order/rank 5 3))))
  (testing "doubles by value"
    (is (neg? (order/rank 1.0 2.0)))
    (is (pos? (order/rank 5.0 3.0))))
  (testing "cross-type: same value, int < double"
    (is (neg? (order/rank 1 1.0)))
    (is (pos? (order/rank 1.0 1))))
  (testing "cross-type: different values"
    (is (neg? (order/rank 3.14 42)))
    (is (neg? (order/rank 1 2.5)))))

(deftest rank-strings-test
  (is (neg? (order/rank "a" "b")))
  (is (pos? (order/rank "b" "a")))
  (is (zero? (order/rank "abc" "abc"))))

(deftest rank-keywords-test
  (testing "unqualified"
    (is (neg? (order/rank :a :b)))
    (is (pos? (order/rank :z :a))))
  (testing "nil namespace < any namespace"
    (is (neg? (order/rank :foo :ns/foo))))
  (testing "qualified by namespace then name"
    (is (neg? (order/rank :a/z :b/a)))
    (is (neg? (order/rank :ns/a :ns/b)))))

(deftest rank-symbols-test
  (is (neg? (order/rank 'a 'b)))
  (is (neg? (order/rank 'foo 'ns/foo))))

(deftest rank-sequential-test
  (testing "element-by-element"
    (is (neg? (order/rank [1 2] [1 3])))
    (is (pos? (order/rank [2 1] [1 1]))))
  (testing "shorter first"
    (is (neg? (order/rank [1] [1 2])))
    (is (pos? (order/rank [1 2] [1])))))

(deftest rank-sets-test
  (testing "cardinality first"
    (is (neg? (order/rank #{} #{1})))
    (is (neg? (order/rank #{1} #{1 2}))))
  (testing "same cardinality: pairwise"
    (is (neg? (order/rank #{1} #{2})))))

(deftest rank-maps-test
  (testing "entry count first"
    (is (neg? (order/rank {} {:a 1}))))
  (testing "same count: compare keys"
    (is (neg? (order/rank {:a 1} {:b 1}))))
  (testing "same keys: compare values"
    (is (neg? (order/rank {:a 1} {:a 2})))))

(deftest cross-type-ordering-test
  (testing "Appendix C.3: mixed-type set ordering"
    (is (= [nil true 3.14 42 "str" :kw '(2) [1] #{} {}]
           (sort order/rank
                 [:kw "str" true 42 nil [1] '(2) #{} {} 3.14])))))

(deftest rank-lists-vs-vectors-test
  (testing "all lists before all vectors (type priority 6 vs 7)"
    (is (neg? (order/rank '(999) [1])))))
