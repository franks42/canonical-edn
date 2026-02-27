(ns cedn.error-test
  (:require [clojure.test :refer [deftest is testing]]
            [cedn.error :as err]))

(defn error-keyword [f & args]
  (try
    (apply f args)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
      (:cedn/error (ex-data e)))))

(defn error-data [f & args]
  (try
    (apply f args)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
      (ex-data e))))

(deftest unsupported-type-test
  (testing "throws with correct error keyword"
    (is (= :cedn/unsupported-type (error-keyword err/unsupported-type! 22/7))))
  (testing "includes value and type"
    (let [data (error-data err/unsupported-type! 22/7)]
      (is (= 22/7 (:cedn/value data)))
      (is (some? (:cedn/type data)))))
  (testing "accepts optional path"
    (let [data (error-data err/unsupported-type! 22/7 [:a :b])]
      (is (= [:a :b] (:cedn/path data))))))

(deftest invalid-number-test
  (is (= :cedn/invalid-number (error-keyword err/invalid-number! ##NaN)))
  (is (= :cedn/invalid-number (error-keyword err/invalid-number! ##Inf)))
  (let [data (error-data err/invalid-number! ##NaN [:x])]
    (is (= [:x] (:cedn/path data)))))

(deftest out-of-range-test
  (is (= :cedn/out-of-range (error-keyword err/out-of-range! 999)))
  (let [data (error-data err/out-of-range! 999 [:n])]
    (is (= 999 (:cedn/value data)))
    (is (= [:n] (:cedn/path data)))))

(deftest duplicate-key-test
  (is (= :cedn/duplicate-key (error-keyword err/duplicate-key! :a)))
  (let [data (error-data err/duplicate-key! :a [:m])]
    (is (= :a (:cedn/value data)))
    (is (= [:m] (:cedn/path data)))))

(deftest duplicate-element-test
  (is (= :cedn/duplicate-element (error-keyword err/duplicate-element! 1)))
  (let [data (error-data err/duplicate-element! 1 [:s])]
    (is (= 1 (:cedn/value data)))
    (is (= [:s] (:cedn/path data)))))

(deftest invalid-unicode-test
  (is (= :cedn/invalid-unicode (error-keyword err/invalid-unicode! "bad")))
  (let [data (error-data err/invalid-unicode! "bad" [:str])]
    (is (= "bad" (:cedn/value data)))
    (is (= [:str] (:cedn/path data)))))

(deftest invalid-tag-form-test
  (is (= :cedn/invalid-tag-form (error-keyword err/invalid-tag-form! 'foo)))
  (let [data (error-data err/invalid-tag-form! 'foo [:t])]
    (is (= 'foo (:cedn/value data)))
    (is (= [:t] (:cedn/path data)))))
