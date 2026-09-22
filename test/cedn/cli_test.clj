(ns cedn.cli-test
  "Integration tests for bin/cedn (the canonical-EDN CLI filter).

  Each test shells out to the script and asserts on stdout, stderr,
  and exit code. The script must be executable and present at
  bin/cedn relative to the working directory (the repo root when
  invoked via `bb test:cli`)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def cedn-bin "bin/cedn")

(defn run
  "Invoke the CLI with the given args and optional stdin. Returns
  {:exit, :out, :err}."
  ([args]            (run args nil))
  ([args stdin]
   (apply shell/sh
          (concat [cedn-bin] args
                  (when stdin [:in stdin])))))

;; ----- success cases -----

(deftest version-flag
  (let [{:keys [exit out]} (run ["--version"])]
    (is (= 0 exit))
    (is (str/starts-with? out "cedn "))))

(deftest help-flag
  (let [{:keys [exit out]} (run ["--help"])]
    (is (= 0 exit))
    (is (str/includes? out "USAGE"))
    (is (str/includes? out "--input"))
    (is (str/includes? out "--objects"))))

(deftest single-form-via-stdin
  (let [{:keys [exit out]} (run [] "{:b 2 :a 1}")]
    (is (= 0 exit))
    (is (= "{:a 1 :b 2}\n" out))))

(deftest single-form-via-edn-flag
  (let [{:keys [exit out]} (run ["--edn" "{:b 2 :a 1}"])]
    (is (= 0 exit))
    (is (= "{:a 1 :b 2}\n" out))))

(deftest empty-input
  (let [{:keys [exit out]} (run [] "")]
    (is (= 0 exit))
    (is (= "" out))))

(deftest multi-form-default-newline-separated
  (let [{:keys [exit out]} (run [] "{:n 3} {:n 1}\n{:n 2}\n")]
    (is (= 0 exit))
    (is (= "{:n 3}\n{:n 1}\n{:n 2}\n" out))))

(deftest multi-form-objects-space-separated
  (let [{:keys [exit out]} (run ["--objects"] "{:n 3} {:n 1} {:n 2}")]
    (is (= 0 exit))
    ;; Space between forms, no trailing space, no trailing newline.
    (is (= "{:n 3} {:n 1} {:n 2}" out))))

(deftest tagged-literal-bytes
  (let [{:keys [exit out]} (run ["--edn" "#bytes \"deadbeef\""])]
    (is (= 0 exit))
    (is (str/includes? out "#bytes \"deadbeef\""))))

(deftest tagged-literal-uuid
  (let [{:keys [exit out]} (run ["--edn"
                                 "#uuid \"550e8400-e29b-41d4-a716-446655440000\""])]
    (is (= 0 exit))
    (is (str/includes? out "#uuid"))
    (is (str/includes? out "550e8400-e29b-41d4-a716-446655440000"))))

(deftest tagged-literal-inst
  (let [{:keys [exit out]} (run ["--edn" "#inst \"2024-01-15T10:30:00Z\""])]
    (is (= 0 exit))
    (is (str/includes? out "#inst"))
    (is (str/includes? out "2024-01-15"))))

(deftest sort-keys-deterministic
  (testing "different input key order yields identical output"
    (let [out1 (:out (run ["--edn" "{:c 3 :a 1 :b 2}"]))
          out2 (:out (run ["--edn" "{:a 1 :b 2 :c 3}"]))]
      (is (= out1 out2 "{:a 1 :b 2 :c 3}\n")))))

(deftest idempotent
  (testing "running cedn twice produces the same bytes"
    (let [once  (:out (run ["--edn" "{:c 3 :a 1 :b 2}"]))
          twice (:out (run [] once))]
      (is (= once twice)))))

;; ----- file I/O -----

(deftest input-output-files
  (let [in-path  "/tmp/cedn-cli-test-in.edn"
        out-path "/tmp/cedn-cli-test-out.edn"]
    (try
      (spit in-path "{:b 2 :a 1}")
      (let [{:keys [exit]} (run ["--input" in-path "--output" out-path])]
        (is (= 0 exit))
        (is (= "{:a 1 :b 2}\n" (slurp out-path))))
      (finally
        (.delete (java.io.File. in-path))
        (.delete (java.io.File. out-path))))))

;; ----- error cases -----

(deftest malformed-edn-exits-1
  (let [{:keys [exit err]} (run [] "{:a 1")]
    (is (= 1 exit))
    (is (str/includes? err "parse error"))))

(deftest input-file-not-found-exits-1
  (let [{:keys [exit err]} (run ["--input" "/tmp/does-not-exist.edn-12345"])]
    (is (= 1 exit))
    (is (str/includes? err "input file not found"))))

(deftest unknown-flag-exits-2
  (let [{:keys [exit err]} (run ["--bogus"])]
    (is (= 2 exit))
    (is (str/includes? err "Unknown option"))))

(deftest mutually-exclusive-edn-and-input-exits-2
  (let [{:keys [exit err]} (run ["--edn" "{:a 1}"
                                 "--input" "/tmp/anything.edn"])]
    (is (= 2 exit))
    (is (str/includes? err "mutually exclusive"))))

(deftest missing-flag-value-exits-2
  (let [{:keys [exit]} (run ["--input"])]
    (is (= 2 exit))))

;; ----- I/O errors and flag changes -----

(deftest objects-flag-long-form-only
  (testing "--objects works"
    (let [{:keys [exit out]} (run ["--objects" "--edn" "{:b 2 :a 1} [3 1]"])]
      (is (= 0 exit))
      (is (= "{:a 1 :b 2} [3 1]" out))))
  (testing "-o is rejected loudly, never silently reinterpreted"
    ;; -o used to mean --objects; it is not reassigned to --output,
    ;; which would write to a file named like the next argument.
    (let [{:keys [exit err]} (run ["-o" "--edn" "{:a 1}"])]
      (is (= 2 exit))
      (is (str/includes? err "-o is no longer an alias")))))

(deftest write-error-is-reported
  (testing "an unwritable --output exits 1, not 0"
    (let [{:keys [exit err]} (run ["--edn" "{:a 1}" "--output" "/nonexistent-dir/out.edn"])]
      (is (= 1 exit))
      (is (str/includes? err "could not open output file"))))
  (testing "a directory as --output exits 1"
    (let [{:keys [exit]} (run ["--edn" "{:a 1}" "--output"
                               (System/getProperty "java.io.tmpdir")])]
      (is (= 1 exit)))))

(deftest closed-downstream-pipe-is-not-an-error
  (testing "cedn | head exits 0 with no error output"
    ;; Only a broken pipe is swallowed; other IOExceptions exit 1.
    (let [err-file (doto (java.io.File/createTempFile "cedn-pipe" ".err")
                     (.deleteOnExit))
          script (str "for i in $(seq 1 5000); do echo '{:a 1 :b [1 2 3]}'; done"
                      " | " cedn-bin " > >(head -1 >/dev/null) 2>" (.getPath err-file)
                      "; echo $?")
          {:keys [out]} (shell/sh "bash" "-c" script)]
      (is (= "0" (str/trim out)))
      (is (str/blank? (slurp err-file))))))

(deftest cli-output-is-a-fixed-point
  (testing "cedn of cedn output is byte-identical (spec §1.2.3)"
    (let [input "{:b 2 :a 1 :s #{3 1 2} :t #inst \"2020-01-01\" :u #uuid \"F81D4FAE-7DEC-11D0-A765-00A0C91E6BF6\" :d 1.50 :bs #bytes \"DEAD\"}"
          once  (:out (run [] input))
          twice (:out (run [] once))
          third (:out (run [] twice))]
      (is (= once twice))
      (is (= twice third))
      ;; and the first pass really did normalize something
      (is (not= input (str/trim once))))))
