# CEDN Implementation Guide

## What This Is

CEDN (Canonical EDN) is a deterministic serialization of EDN values
to UTF-8 byte sequences.  Same logical value → same bytes, always,
on every Clojure runtime.  This enables cryptographic signing and
verification of EDN data structures across JVM, ClojureScript,
Babashka, and Node.js.

This library is a standalone dependency — it does NOT depend on Kex
or any authorization framework.  Kex will depend on it.

## Reference Documents

Read these before writing code.  They are the source of truth.

| File | What it is |
|------|-----------|
| `cedn-spec.md` | The formal specification.  Every rendering rule, error condition, and edge case is defined here.  §3 (CEDN-P types) and §5 (ordering) are the critical sections for implementation. |
| `cedn-api-design.cljc` | The public API surface and module structure.  Function signatures, option maps, return values, and module dependency graph are all defined here.  Follow this design. |
| `cedn-p-schema.cljc` | Malli and clojure.spec type contracts for CEDN-P.  The Malli version is the primary one.  This file is ready to use with minor adjustments. |
| `kex-sources.md` | Links to reference implementations, specs, and libraries.  Especially useful: JCS Java implementations for ECMAScript number formatting, and the arrangement library for sort ordering. |

## Decisions for Open Issues

The spec has four items marked `OPEN ISSUE (v1-draft)`.  For this
implementation, use these defaults:

### 1. `#inst` fractional digits → **9 (nanoseconds)**

Always emit exactly 9 fractional digits, zero-padded.  The
canonical output is write-only — it goes into sign/verify/hash,
not back into a deserializer.  No platform needs to reconstruct a
date from the canonical text.

Platforms with millisecond precision (`java.util.Date`, `js/Date`)
emit 3 significant digits + 6 zeros.  Platforms with nanosecond
precision (`java.time.Instant`) emit all 9 significant digits.
If both sides hold the same value, they produce the same bytes.

Accept `java.time.Instant` as input on JVM (in addition to
`java.util.Date`).  On ClojureScript, accept `js/Date`.

```clojure
;; From java.util.Date (ms precision):
#inst "2026-02-26T12:00:00.123000000Z"

;; From java.time.Instant (ns precision):
#inst "2026-02-26T12:00:00.123456789Z"
```

### 2. Custom tagged literals → **#inst and #uuid only**

CEDN-P v1 does NOT support arbitrary tagged literals.  Any tagged
value that is not `#inst` or `#uuid` is an `:cedn/unsupported-type`
error.  This keeps the type universe closed and the implementation
simple.

### 3. CEDN-R characters → **excluded**

Do not implement character literals even in CEDN-R.  They add
complexity for no practical authorization use case.

### 4. String comparison ordering → **UTF-16 code unit order**

Use JVM `String.compareTo()` / JS `<` operator semantics.  This is
the native comparison on both platforms.  The only divergence from
codepoint order is for characters above U+FFFF (emoji, etc.), which
are extremely rare in authorization data.

Document this choice in the code but do not overthink it — for BMP
strings (the vast majority of real-world use), all three candidate
orderings produce identical results.

## Project Structure

```
cedn/
├── deps.edn
├── context.md                  ← this file
├── cedn-spec.md                ← specification (reference)
├── cedn-api-design.cljc        ← API design (reference)
├── cedn-p-schema.cljc          ← schema design (reference)
├── kex-sources.md              ← links (reference)
├── src/
│   └── cedn/
│       ├── core.cljc           ← public API (5 functions)
│       ├── emit.cljc           ← per-type canonical emission
│       ├── order.cljc          ← rank comparator
│       ├── number.cljc         ← ECMAScript double formatting
│       ├── error.cljc          ← structured error constructors
│       ├── schema.cljc         ← Malli type contracts
│       └── gen.cljc            ← test.check generators
└── test/
    └── cedn/
        ├── core_test.cljc      ← public API round-trip tests
        ├── emit_test.cljc      ← per-type emission tests
        ├── order_test.cljc     ← rank comparator tests
        ├── number_test.cljc    ← double formatting tests
        ├── vectors_test.cljc   ← spec Appendix C test vectors
        └── property_test.cljc  ← generative property tests
```

## Dependencies

```clojure
;; deps.edn
{:paths ["src"]
 :deps {org.clojure/clojure              {:mvn/version "1.12.0"}
        metosin/malli                     {:mvn/version "0.17.0"}
        io.github.erdtman/java-json-canonicalization {:mvn/version "1.1"}}
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {io.github.cognitect-labs/test-runner
                       {:git/tag "v0.5.1"
                        :git/url "https://github.com/cognitect-labs/test-runner"}
                       org.clojure/test.check {:mvn/version "1.1.1"}}
         :main-opts   ["-m" "cognitect.test-runner"]
         :exec-fn     cognitect.test-runner.api/test}
  :cljs {:extra-deps {org.clojure/clojurescript {:mvn/version "1.11.132"}}
         ;; shadow-cljs or similar for ClojureScript testing
         }}}
```

The `java-json-canonicalization` dependency provides
`org.erdtman.jcs.NumberToJSON.serializeNumber()` which implements
the ECMAScript Number serialization algorithm on the JVM.  This is
the ONLY correct way to format doubles on the JVM — do NOT use
`Double.toString()`.

## Implementation Order

The module dependency graph dictates the build order.  Each step
should be fully tested before moving to the next.

### Step 1: `cedn.error`

**Zero dependencies.  Start here.**

Structured error constructors.  Each function throws `ex-info` with
a map containing `:cedn/error`, `:cedn/value`, and optional
`:cedn/path`.  Seven error classes — see `cedn-api-design.cljc`
and spec §7.

This is ~40 lines of straightforward code.  Every other module
depends on it.

```clojure
;; Test: each constructor throws ExceptionInfo with correct :cedn/error key
(is (= :cedn/unsupported-type
       (:cedn/error (ex-data (try (err/unsupported-type! 22/7)
                                  (catch ExceptionInfo e e))))))
```

### Step 2: `cedn.number`

**Zero internal dependencies (uses JCS Java lib on JVM).**

One function: `format-double`.  This is the trickiest cross-platform
piece because JVM and JS format doubles differently.

On JS: `(.toString x)` IS the ECMAScript algorithm.  Just add the
`.0` suffix rule and `-0.0` check.

On JVM: call `org.erdtman.jcs.NumberToJSON.serializeNumber(x)`,
then apply the `.0` suffix rule and `-0.0` normalization.

**Test with the spec's Appendix B table** — every row is a test case.
Also test:
- NaN → throws `:cedn/invalid-number`
- Infinity → throws `:cedn/invalid-number`
- -Infinity → throws `:cedn/invalid-number`
- -0.0 → `"0.0"`
- 1.0 → `"1.0"` (not `"1"`)
- 1e+21 → `"1e+21"`

```clojure
;; Appendix B test vectors (partial)
(are [input expected]
  (= expected (number/format-double input))
  0.0                    "0.0"
  -0.0                   "0.0"
  1.0                    "1.0"
  -3.14                  "-3.14"
  0.1                    "0.1"
  1e-7                   "1e-7"
  1e21                   "1e+21"
  3.141592653589793      "3.141592653589793")
```

### Step 3: `cedn.order`

**Zero internal dependencies.**

Two functions: `type-priority` and `rank`.

`type-priority` maps a value to its integer priority (nil=0,
boolean=1, number=2, string=3, keyword=4, symbol=5, list=6,
vector=7, set=8, map=9, tagged=10).

`rank` is the comparator.  Compare by type-priority first, then
within-type rules per spec §5.3.

**Critical: get the within-type rules right.**  Numbers by
mathematical value (int before double if equal).  Keywords by
namespace-then-name (absent namespace before any namespace).
Sequences element-by-element, shorter first.

**Test with spec Appendix C.3** — the cross-type ordering vector:

```clojure
;; #{:kw "str" true 42 nil [1] (2) #{} {} 3.14}
;; must sort to:
;; (nil true 3.14 42 "str" :kw (2) [1] #{} {})
(is (= [nil true 3.14 42 "str" :kw '(2) [1] #{} {}]
       (sort order/rank
             [:kw "str" true 42 nil [1] '(2) #{} {} 3.14])))
```

### Step 4: `cedn.emit`

**Depends on: error, number, order.**

This is the workhorse.  One function `emit` that dispatches on type
and writes canonical text to a StringBuilder (JVM) or string buffer
(JS).

Implement in this order within the module, testing each type
before moving on:

1. **nil, boolean** — trivial, 2 lines each
2. **integer** — range check, `str`
3. **double** — delegate to `cedn.number/format-double`
4. **string** — escape rules per §3.5 (mandatory escapes, control
   chars as `\uNNNN`, literal UTF-8 for everything else)
5. **keyword, symbol** — namespace/name rendering
6. **vector, list** — elements in order, separated by spaces
7. **set** — sort elements by `rank`, then emit like a collection
8. **map** — sort entries by key `rank`, emit key-space-value
9. **#inst** — format from epoch value, fixed 9 fractional digits,
   UTC `Z` suffix.  Accept both `java.util.Date` (ms → pad 6 zeros)
   and `java.time.Instant` (ns → all 9 digits) on JVM.
10. **#uuid** — lowercase hex, 8-4-4-4-12

**String emission is the most subtle part.**  The rules:
- `"`, `\` → escaped
- `\n`, `\r`, `\t` → escaped with named escapes
- U+0000–U+001F, U+007F (minus the named ones) → `\uNNNN`
- Everything else (U+0020–U+007E minus `"` and `\`, plus U+0080+) → literal UTF-8

**Test with every row of spec Appendix C.1 and C.5.**

```clojure
;; C.1 round-trip vectors
(are [input expected]
  (= expected (emit/emit-str :cedn-p input))
  nil          "nil"
  true         "true"
  42           "42"
  -7           "-7"
  3.14         "3.14"
  1.0          "1.0"
  ""           "\"\""
  "hello"      "\"hello\""
  :foo         ":foo"
  :ns/bar      ":ns/bar"
  '()          "()"
  [1 2 3]      "[1 2 3]"
  #{3 1 2}     "#{1 2 3}"
  {:b 2 :a 1}  "{:a 1 :b 2}")
```

### Step 5: `cedn.schema`

**Depends on: malli (external).**

Adapt `cedn-p-schema.cljc` into the library's `cedn.schema`
namespace.  Three functions: `schema-for`, `valid?`, `explain`.

The Malli schemas are already designed — this is mostly wiring.

### Step 6: `cedn.core`

**Depends on: emit, schema, order.**

Thin public API.  Five functions plus `rank` re-export.

`canonical-bytes`: call `emit`, encode to UTF-8 bytes.
`canonical-str`: call `emit`, return string.
`valid?`: delegate to schema.
`explain`: delegate to schema.
`inspect`: try/catch around `canonical-bytes`, return diagnostic map
including SHA-256 hex digest.
`canonical?`: read-string, re-canonicalize, compare.

### Step 7: `cedn.gen`

**Depends on: schema, malli.generator.**

Generators for property-based testing.  The Malli schema gives us
generators almost for free via `malli.generator/generator`.

Custom generator overrides needed for:
- Doubles: generate only finite, non-NaN values
- Integers: generate within 64-bit signed range
- #inst: generate java.util.Date / js/Date values
- #uuid: generate java.util.UUID / cljs.core/UUID values
- Recursion: control depth to avoid stack overflow

### Step 8: Property Tests

With generators in hand, write property tests:

```clojure
;; Idempotency: canonical text is stable across roundtrips
(defspec canonical-idempotent 1000
  (prop/for-all [v (cedn.gen/gen-cedn-p)]
    (let [s1 (cedn/canonical-str v)
          v2 (edn/read-string s1)
          s2 (cedn/canonical-str v2)]
      (= s1 s2))))

;; Valid EDN: canonical output can always be read back
(defspec canonical-is-valid-edn 1000
  (prop/for-all [v (cedn.gen/gen-cedn-p)]
    (let [s (cedn/canonical-str v)]
      (some? (edn/read-string s)))))

;; Determinism: same value always produces same bytes
(defspec canonical-deterministic 1000
  (prop/for-all [v (cedn.gen/gen-cedn-p)]
    (let [b1 (cedn/canonical-bytes v)
          b2 (cedn/canonical-bytes v)]
      (java.util.Arrays/equals b1 b2))))
```

## Key Implementation Notes

### StringBuilder pattern (emit)

On JVM, use a single `StringBuilder` threaded through all emit
calls.  On ClojureScript, use string concatenation or a mutable
array that gets joined.  The `emit` function signature is:

```clojure
(defn emit [sb profile value] ...)
```

Where `sb` is the accumulator.  This avoids intermediate string
allocations for large nested structures.

### Type dispatch in emit

Use a `cond` chain, NOT protocols or multimethods.  The type
universe is deliberately closed.  The dispatch order matters
for correctness:

```clojure
(nil? value)      ;; must be first (nil is falsy)
(boolean? value)  ;; before any numeric check
(int? value)      ;; before double? (on JVM, Long is not Double)
(double? value)   ;; after int?
(string? value)
(keyword? value)
(symbol? value)
(seq? value)      ;; lists, lazy seqs, cons cells
(vector? value)
(set? value)
(map? value)      ;; includes records (intentional)
;; #inst and #uuid checks (platform-specific)
```

On ClojureScript, `int?` check needs care: all numbers are doubles,
so the check is `(and (number? x) (js/Number.isFinite x) (== x (Math/trunc x)))`.

### #inst formatting

Do NOT use `pr-str` or `.toString` on date objects.  Format from
the epoch value directly.  Always emit exactly 9 fractional digits.

```clojure
;; JVM — accept both java.util.Date and java.time.Instant
(defn format-inst [v]
  (let [inst (cond
               (instance? java.time.Instant v) v
               (instance? java.util.Date v)    (.toInstant v)
               :else (err/unsupported-type! v))
        zdt  (.atZone inst (java.time.ZoneOffset/UTC))
        nano (.getNano inst)]
    (format "%04d-%02d-%02dT%02d:%02d:%02d.%09dZ"
            (.getYear zdt) (.getMonthValue zdt) (.getDayOfMonth zdt)
            (.getHour zdt) (.getMinute zdt) (.getSecond zdt) nano)))
;; java.util.Date → nano will be ms * 1000000, last 6 digits are zeros
;; java.time.Instant → full nanosecond precision
```

On ClojureScript, use `js/Date` methods (`.getUTCFullYear`, etc.)
and zero-pad the last 6 digits since `js/Date` is ms-precision.

### Duplicate detection in sets and maps

After sorting, check adjacent elements/keys for equality.  If any
two adjacent sorted elements are `=`, throw `:cedn/duplicate-element`
or `:cedn/duplicate-key`.  This is O(n) after the O(n log n) sort.

In practice this rarely fires because Clojure's own sets and maps
already enforce uniqueness — but it can fire when integer `1` and
double `1.0` both appear as keys (Clojure allows this, CEDN
rejects it because they normalize to different canonical forms
but compare as equal numerically... actually, verify this edge case
carefully against the spec §5.3.3 and §3.10 duplicate rules).

### Cross-platform testing strategy

Phase 1: Get everything working and tested on JVM with `clj -X:test`.

Phase 2: Add ClojureScript tests.  The canonical bytes for every
test vector must be identical.  A shared `.cljc` test file with
`(are ...)` tables is the simplest approach.  Run with shadow-cljs
or similar.

Phase 3: Babashka smoke test.  Since bb runs `.cljc` files natively
and has its own number handling, run the test vectors under bb to
verify parity.  The JCS Java dependency won't be available in bb —
this will need a pure-Clojure fallback or bb pod.  Flag this as
a known gap for now.

## What NOT to Build Yet

- **CEDN-R**: Skip for now.  Get CEDN-P solid first.  CEDN-R adds
  BigInt, BigDecimal, and ratios — straightforward extensions once
  the core works.

- **CLI tool**: Trivial Babashka wrapper once `cedn.core` exists.
  Not worth building until the library is tested.

- **Kex integration**: Separate concern.  CEDN is a standalone
  library.  Kex will depend on it, not the other way around.

- **clj-kondo hooks**: Nice-to-have, low priority.  The runtime
  schema validation catches everything kondo could catch and more.
