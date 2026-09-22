(ns cedn.token-test
  (:require [clojure.test :refer [deftest are testing]]
            [cedn.token :as token]))

(deftest well-formed-unicode-test
  (testing "valid strings"
    (are [s] (token/well-formed-unicode? s)
      ""
      "hello"
      "café"
      "𐀀"          ; U+10000, a proper surrogate pair
      "a😀b"))      ; U+1F600 between ASCII
  (testing "unpaired surrogates (§3.5.4)"
    (are [s] (not (token/well-formed-unicode? s))
      "\uD800"                ; lone high
      "\uDC00"                ; lone low
      "a\uD800"               ; high at end of string
      "\uD800a"               ; high followed by non-surrogate
      "\uDC00\uD800"          ; reversed pair
      "\uD800𐀀"))) ; extra high before a valid pair

(deftest keyword-valid-test
  (testing "ordinary and edge-case keywords that read back unchanged"
    (are [kw] (nil? (token/keyword-error kw))
      :a
      :ns/a
      :a.b/c-d?
      :a:b
      (keyword "café")
      (keyword "😀")
      ;; Unqualified keyword names may start with a digit, #, or +/-digit
      ;; — common in keywordized JSON (e.g. HTTP status codes).
      (keyword "200")
      (keyword "#a")
      (keyword "-1")
      (keyword "nil")
      (keyword "/")
      (keyword "a" "/"))))

(deftest keyword-invalid-test
  (are [kw reason] (= reason (token/keyword-error kw))
    (keyword "a b")        "whitespace, delimiter, or reserved character"
    (keyword "a,b")        "whitespace, delimiter, or reserved character"
    (keyword "a\"b")       "whitespace, delimiter, or reserved character"
    (keyword "a]")         "whitespace, delimiter, or reserved character"
    (keyword "a\nb")       "whitespace, delimiter, or reserved character"
    (keyword "a/b" "c")    "whitespace, delimiter, or reserved character"
    (keyword "a" "b/c")    "whitespace, delimiter, or reserved character"
    (keyword "")           "empty component"
    (keyword "" "a")       "empty component"
    (keyword ":a")         "leading colon"
    (keyword "a:")         "trailing colon"
    (keyword "a::b")       "double colon"
    (keyword "a\uD800")    "unpaired surrogate"
    ;; Qualified names follow symbol rules (`:a/1` is not readable)
    (keyword "a" "1")      "leading digit"
    (keyword "1" "a")      "leading digit"))

(deftest symbol-valid-test
  (are [sym] (nil? (token/symbol-error sym))
    'a
    'ns/a
    '+
    '-
    '.
    '+a
    '.a
    '<=>
    'a#
    'a'b
    '/
    'clojure.core//
    'foo/nil
    (symbol "café")))

(deftest symbol-invalid-test
  (are [sym reason] (= reason (token/symbol-error sym))
    (symbol "nil")      "reserved literal name"
    (symbol "true")     "reserved literal name"
    (symbol "false")    "reserved literal name"
    (symbol "1")        "leading digit"
    (symbol "1a")       "leading digit"
    (symbol "a" "1")    "leading digit"
    (symbol "-1")       "leading +, -, or . followed by a digit"
    (symbol "+1")       "leading +, -, or . followed by a digit"
    (symbol ".5")       "leading +, -, or . followed by a digit"
    (symbol "#inst")    "leading #"
    (symbol "#{}")      "whitespace, delimiter, or reserved character"
    (symbol ":a")       "leading colon"
    (symbol "a b")      "whitespace, delimiter, or reserved character"
    (symbol "\"x\"")    "whitespace, delimiter, or reserved character"
    (symbol "a@b")      "whitespace, delimiter, or reserved character"
    (symbol "a/b" "c")  "whitespace, delimiter, or reserved character"
    (symbol "" "a")     "empty component"))
