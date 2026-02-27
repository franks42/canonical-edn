(ns cedn.number-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cedn.number :as number]
            #?(:clj [clojure.java.io :as io]))
  #?@(:bb []
      :clj [(:import [org.erdtman.jcs NumberToJSON])]))

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

#?(:cljs nil ;; reference test is JVM/bb only
   :clj
   (deftest format-double-cross-platform-reference-test
     (testing "format-double matches JVM-generated reference (1051 doubles)"
       (let [ref-data (read-string (slurp (io/resource "cedn/number-reference.edn")))]
         (doseq [[bits expected] ref-data]
           (let [x (Double/longBitsToDouble bits)]
             (is (= expected (number/format-double x))
                 (str "Reference mismatch for " x
                      " (bits=" bits
                      "): expected=" expected
                      " got=" (number/format-double x)))))))))

#?(:bb nil ;; JCS not available in bb
   :clj
   (deftest ecma-reformat-cross-validation-test
     (let [ecma-reformat @(resolve 'cedn.number/ecma-reformat)]
       (testing "ecma-reformat matches JCS for Appendix B vectors and edge cases"
         (doseq [x [1.0 -1.0 10.0 100.0 0.1 0.01 0.0001 0.000001
                    1e-7 9007199254740992.0 9007199254740994.0
                    1e21 10000.0 5.0 3.0 3.141592653589793
                    -3.14 4.5 1e20 1e7 1e-3 1e-6
                    1e-10 1e-20 1e15 1e20 1e-4 1e-5
                    123456789.0 0.123456789 1.23e15 9.99e-5
                     ;; Double/MIN_VALUE excluded: JDK Schubfach outputs
                     ;; "4.9E-324" (2 sig digits) but ECMAScript uses "5e-324"
                     ;; (1 sig digit). This is the only known divergence.
                    Double/MAX_VALUE (- Double/MAX_VALUE)
                    1.7976931348623157E308
                    2.2250738585072014E-308
                    2.225073858507201E-308]]
           (let [jcs-result (NumberToJSON/serializeNumber x)
                 reformat-result (ecma-reformat (Double/toString x))]
             (is (= jcs-result reformat-result)
                 (str "Mismatch for " x
                      ": JCS=" jcs-result
                      " reformat=" reformat-result)))))
       (testing "ecma-reformat matches JCS for random doubles"
         (let [rng (java.util.Random. 42)]
           (dotimes [_ 10000]
             (let [;; Generate doubles across many magnitudes
                   x (.nextDouble rng)
                   scale (Math/pow 10.0 (- (.nextInt rng 40) 20))
                   v (* x scale)
                   ;; Also test negative values
                   vs [v (- v)]]
               (doseq [d vs]
                 (when-not (or (Double/isNaN d) (Double/isInfinite d) (zero? d))
                   (let [jcs-result (NumberToJSON/serializeNumber d)
                         reformat-result (ecma-reformat (Double/toString d))]
                     (is (= jcs-result reformat-result)
                         (str "Mismatch for " d
                              ": JCS=" jcs-result
                              " reformat=" reformat-result))))))))))))
