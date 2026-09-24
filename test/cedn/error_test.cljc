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
  ;; 22/7 is a Ratio on JVM, not a valid CLJS constant
  #?(:clj
     (do
       (testing "throws with correct error keyword"
         (is (= :cedn/unsupported-type (error-keyword err/throw-unsupported-type 22/7))))
       (testing "includes value and type"
         (let [data (error-data err/throw-unsupported-type 22/7)]
           (is (= 22/7 (:cedn/value data)))
           (is (some? (:cedn/type data)))))
       (testing "accepts optional path"
         (let [data (error-data err/throw-unsupported-type 22/7 [:a :b])]
           (is (= [:a :b] (:cedn/path data)))))))
  ;; Test with a regex (unsupported on all platforms)
  (testing "regex throws"
    (is (= :cedn/unsupported-type
           (error-keyword err/throw-unsupported-type #"regex")))))

(deftest invalid-number-test
  (is (= :cedn/invalid-number (error-keyword err/throw-invalid-number ##NaN)))
  (is (= :cedn/invalid-number (error-keyword err/throw-invalid-number ##Inf)))
  (let [data (error-data err/throw-invalid-number ##NaN [:x])]
    (is (= [:x] (:cedn/path data)))))

(deftest out-of-range-test
  (is (= :cedn/out-of-range (error-keyword err/throw-out-of-range 999)))
  (let [data (error-data err/throw-out-of-range 999 [:n])]
    (is (= 999 (:cedn/value data)))
    (is (= [:n] (:cedn/path data)))))

(deftest duplicate-key-test
  (is (= :cedn/duplicate-key (error-keyword err/throw-duplicate-key :a)))
  (let [data (error-data err/throw-duplicate-key :a [:m])]
    (is (= :a (:cedn/value data)))
    (is (= [:m] (:cedn/path data)))))

(deftest duplicate-element-test
  (is (= :cedn/duplicate-element (error-keyword err/throw-duplicate-element 1)))
  (let [data (error-data err/throw-duplicate-element 1 [:s])]
    (is (= 1 (:cedn/value data)))
    (is (= [:s] (:cedn/path data)))))

(deftest invalid-unicode-test
  (is (= :cedn/invalid-unicode (error-keyword err/throw-invalid-unicode "bad")))
  (let [data (error-data err/throw-invalid-unicode "bad" [:str])]
    (is (= "bad" (:cedn/value data)))
    (is (= [:str] (:cedn/path data)))))

(deftest invalid-tag-form-test
  (is (= :cedn/invalid-tag-form (error-keyword err/throw-invalid-tag-form 'foo)))
  (let [data (error-data err/throw-invalid-tag-form 'foo [:t])]
    (is (= 'foo (:cedn/value data)))
    (is (= [:t] (:cedn/path data)))))

(deftest invalid-name-test
  (is (= :cedn/invalid-name (error-keyword err/throw-invalid-name 'x "reason")))
  (let [data (error-data err/throw-invalid-name 'x "leading digit" [:k])]
    (is (= 'x (:cedn/value data)))
    (is (= "leading digit" (:cedn/reason data)))
    (is (= [:k] (:cedn/path data)))))

#_{:clj-kondo/ignore [:deprecated-var]}
(deftest deprecated-bang-names-still-work
  ;; Until 1.6.0 these were the public names. They stay, deprecated, with
  ;; exactly the old behaviour: the same :cedn/error and data.
  (doseq [[old new args] [[err/unsupported-type!   err/throw-unsupported-type   [:v [:p]]]
                          [err/invalid-number!     err/throw-invalid-number     [:v [:p]]]
                          [err/out-of-range!       err/throw-out-of-range       [:v [:p]]]
                          [err/duplicate-key!      err/throw-duplicate-key      [:v [:p]]]
                          [err/duplicate-element!  err/throw-duplicate-element  [:v [:p]]]
                          [err/invalid-unicode!    err/throw-invalid-unicode    [:v [:p]]]
                          [err/reader-error!       err/throw-reader-error       ['inst "x" "bad"]]
                          [err/invalid-name!       err/throw-invalid-name       [:v "why" [:p]]]
                          [err/invalid-tag-form!   err/throw-invalid-tag-form   [:v [:p]]]]]
    (is (= (dissoc (apply error-data new args) :cedn/type)
           (dissoc (apply error-data old args) :cedn/type))
        (str old))
    (is (some? (apply error-data old args)) "it throws"))
  (is (every? #(:deprecated (meta %))
              [#'err/unsupported-type! #'err/invalid-number! #'err/out-of-range!
               #'err/duplicate-key! #'err/duplicate-element! #'err/invalid-unicode!
               #'err/reader-error! #'err/invalid-name! #'err/invalid-tag-form!])))
