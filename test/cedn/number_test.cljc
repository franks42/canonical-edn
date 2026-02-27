(ns cedn.number-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cedn.number :as number]))

(deftest format-double-appendix-b-test
  (testing "Appendix B test vectors"
    (are [input expected]
         (= expected (number/format-double input))
      0.0                    "0.0"
      -0.0                   "0.0"
      1.0                    "1.0"
      -1.0                   "-1.0"
      10.0                   "10.0"
      100.0                  "100.0"
      0.1                    "0.1"
      0.01                   "0.01"
      0.0001                 "0.0001"
      0.000001               "0.000001"
      1e-7                   "1e-7"
      9007199254740992.0     "9007199254740992.0"
      9007199254740994.0     "9007199254740994.0"
      1e21                   "1e+21"
      10000.0                "10000.0"
      5.0                    "5.0"
      3.0                    "3.0"
      3.141592653589793      "3.141592653589793")))

(deftest format-double-additional-test
  (testing "basic values"
    (is (= "-3.14" (number/format-double -3.14)))
    (is (= "4.5" (number/format-double 4.5)))
    (is (= "3.14" (number/format-double 3.14)))
    (is (= "100000000000000000000.0" (number/format-double 1e20)))))

(deftest format-double-errors-test
  (testing "NaN throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (number/format-double ##NaN))))
  (testing "Infinity throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (number/format-double ##Inf))))
  (testing "-Infinity throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (number/format-double ##-Inf)))))
