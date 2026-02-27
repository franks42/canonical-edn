# CEDN — Canonical EDN Library

## What This Is

CEDN (Canonical EDN) is a deterministic serialization of EDN values
to UTF-8 byte sequences.  Same logical value → same bytes, always,
on every Clojure runtime.  This enables cryptographic signing and
verification of EDN data structures across JVM, ClojureScript,
Babashka, and Node.js.

This library is a standalone dependency — it does NOT depend on Kex
or any authorization framework.  Kex will depend on it.

## Current Status

**v1 implementation complete — JVM, CEDN-P profile.**

All 8 implementation steps are done and tested:

| Module | Status | Description |
|--------|--------|-------------|
| `cedn.error` | Done | 7 error constructors (`unsupported-type!`, `invalid-number!`, `out-of-range!`, `duplicate-key!`, `duplicate-element!`, `invalid-unicode!`, `invalid-tag-form!`) |
| `cedn.number` | Done | `format-double` using JCS `NumberToJSON` on JVM, all Appendix B test vectors passing |
| `cedn.order` | Done | `type-priority` + `rank` comparator implementing §5 total ordering |
| `cedn.emit` | Done | Core `emit`/`emit-str` with type dispatch, string escaping (§3.5), `#inst` (9 fractional digits), `#uuid` (lowercase hex), set/map sorting + duplicate detection |
| `cedn.schema` | Done | Malli schemas for CEDN-P with recursive registry, `schema-for`/`valid?`/`explain` |
| `cedn.core` | Done | Public API: `canonical-bytes`, `canonical-str`, `valid?`, `explain`, `assert!`, `inspect` (SHA-256), `canonical?`, `rank`, `readers` |
| `cedn.gen` | Done | test.check generators for CEDN-P values |
| Property tests | Done | 4 properties × 200 iterations: idempotency, valid EDN, determinism, str/bytes agreement |

**Test results: 73 tests, 246 assertions, 0 failures.**
**Lint: 0 clj-kondo errors/warnings, cljfmt clean.**

**Persistent project memory is stored in MCP memory (tag: `cedn`).**
Use `memory_search` with query "CEDN" or filter by tag "cedn" to retrieve
design decisions, project state, and workflow notes across sessions.

## Reference Documents

| File | What it is |
|------|-----------|
| `docs/cedn-spec.md` | The formal specification.  §3 (CEDN-P types) and §5 (ordering) are the critical sections. |
| `docs/cedn-api-design.cljc` | The public API surface and module structure.  Function signatures and module dependency graph. |
| `docs/cedn-p-schema.cljc` | Malli and clojure.spec type contracts for CEDN-P. |
| `docs/kex-sources.md` | Links to reference implementations, specs, and libraries. |

## Design Decisions (Open Issues Resolved)

1. **`#inst` fractional digits → 9 (nanoseconds).**
   Always emit exactly 9 fractional digits, zero-padded.
   `java.util.Date` (ms) → 3 digits + 6 zeros.
   `java.time.Instant` (ns) → all 9 digits.

2. **Custom tagged literals → `#inst` and `#uuid` only.**
   CEDN-P v1 does NOT support arbitrary tagged literals.

3. **CEDN-R characters → excluded.**

4. **String comparison ordering → Unicode codepoint order.**
   Equivalent to UTF-8 byte order.  Platform-neutral: does not depend on
   JVM/JS UTF-16 internals.  Matches the CEDN wire format (UTF-8).
   Identical to UTF-16 code unit order for BMP-only strings; diverges
   only for astral-plane characters (U+10000+).

5. **`readers` map for canonical round-trips.**
   `cedn/readers` maps `#inst` → `Instant/parse` (JVM) and `#uuid` → `UUID/fromString` (JVM).
   The default EDN reader produces `java.util.Date` (ms precision), losing sub-ms digits
   from the 9-fractional-digit canonical `#inst` form. `Instant/parse` preserves nanosecond
   precision. `canonical?` uses `readers` internally so it correctly recognizes canonical
   `#inst`/`#uuid` strings.

6. **KEX/Biscuit policies → CEDN-P only (no CEDN-R).**
   All policy statements must use only CEDN-P data types.  CEDN-R
   (BigInt, BigDecimal, ratios) is not needed for authorization use
   cases and is deprioritized indefinitely.  Rationale:
   - Cross-platform portability (JVM, CLJS, Babashka, Node.js) is
     paramount for policy interchange.
   - CEDN-P is already a strict superset of Biscuit's data types
     (i64, string, date, bytes, boolean, set).
   - Financial amounts use integer smallest-units (cents, satoshis),
     which fit in 64-bit Long.
   - No realistic authorization scenario requires >64-bit integers,
     exact decimals, or ratios.

## Project Structure

```
cedn/
├── deps.edn
├── context.md                  ← this file
├── .clj-kondo/config.edn      ← kondo config (defspec lint-as)
├── docs/
│   ├── cedn-spec.md            ← formal specification
│   ├── cedn-api-design.cljc    ← API design document
│   ├── cedn-p-schema.cljc      ← Malli schema design
│   └── kex-sources.md          ← reference links
├── src/
│   └── cedn/
│       ├── core.cljc           ← public API (7 functions + rank + readers)
│       ├── emit.cljc           ← per-type canonical emission
│       ├── order.cljc          ← rank comparator
│       ├── number.cljc         ← ECMAScript double formatting
│       ├── error.cljc          ← structured error constructors
│       ├── schema.cljc         ← Malli type contracts
│       └── gen.cljc            ← test.check generators
└── test/
    └── cedn/
        ├── core_test.cljc      ← public API + round-trip tests
        ├── emit_test.cljc      ← per-type emission + Appendix C vectors
        ├── order_test.cljc     ← rank comparator tests
        ├── number_test.cljc    ← double formatting + Appendix B vectors
        ├── error_test.cljc     ← error constructor tests
        ├── schema_test.cljc    ← schema validation tests
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
                        :git/sha "dfb30dd6605cb6c0efc275e1df1736f6e90d4d73"
                        :git/url "https://github.com/cognitect-labs/test-runner"}
                       org.clojure/test.check {:mvn/version "1.1.1"}}
         :main-opts   ["-m" "cognitect.test-runner"]
         :exec-fn     cognitect.test-runner.api/test}
  :cljs {:extra-deps {org.clojure/clojurescript {:mvn/version "1.11.132"}}}}}
```

The `java-json-canonicalization` dependency provides
`org.erdtman.jcs.NumberToJSON.serializeNumber()` which implements
the ECMAScript Number serialization algorithm on the JVM.  This is
the ONLY correct way to format doubles on the JVM — do NOT use
`Double.toString()`.

## Usage

```clojure
(require '[cedn.core :as cedn])

;; Canonicalize to bytes (for signing/hashing)
(cedn/canonical-bytes {:a 1 :b 2})
;=> #bytes[7B 3A 61 20 31 20 3A 62 20 32 7D]

;; Canonicalize to string (for debugging)
(cedn/canonical-str {:b 2 :a 1})
;=> "{:a 1 :b 2}"

;; Validate before canonicalization
(cedn/valid? {:a 1 :b "hello"})  ;=> true
(cedn/valid? 22/7)               ;=> false

;; Full diagnostics
(cedn/inspect {:a 1 :b 2})
;=> {:status :ok, :canonical "{:a 1 :b 2}", :sha-256 "...", ...}

;; Check if a string is already canonical
(cedn/canonical? "{:a 1 :b 2}")  ;=> true
(cedn/canonical? "{:b 2 :a 1}")  ;=> false

;; Round-trip with precision-preserving readers
(require '[clojure.edn :as edn])
(let [s (cedn/canonical-str (java.time.Instant/parse "2025-02-26T12:00:00.123456789Z"))]
  (edn/read-string {:readers cedn/readers} s))
;=> #object[java.time.Instant "2025-02-26T12:00:00.123456789Z"]

;; Total ordering comparator
(sort cedn/rank [3 :a nil true "b"])
;=> (nil true 3 "b" :a)
```

## Development: Test, Lint, Format

All three checks must pass with zero errors and zero warnings before
any commit.  Run on ALL source and test files, not just modified ones.

```bash
# 1. Tests — all must pass
clj -X:test

# 2. Linting — must report 0 errors, 0 warnings
clj-kondo --lint src test

# 3. Formatting — must report all files correct
cljfmt check src test

# Auto-fix formatting issues:
cljfmt fix src test
```

## Architecture Notes

### Module dependency graph

```
cedn.core
  ├── cedn.emit
  │     ├── cedn.number
  │     ├── cedn.order
  │     └── cedn.error
  ├── cedn.schema
  └── cedn.order (re-exported as cedn.core/rank)

cedn.gen
  └── clojure.test.check.generators
```

### Key implementation patterns

- **StringBuilder threading**: `emit` takes a `StringBuilder` (JVM) or
  `goog.string.StringBuffer` (CLJS) as first arg, all emit calls append to it.
- **Closed type dispatch**: `cond` chain in `emit`, NOT protocols/multimethods.
  Order matters: nil → boolean → int → double → string → keyword → symbol →
  seq → vector → set → map → #inst → #uuid → error.
- **Duplicate detection**: After sorting sets/maps, check adjacent elements/keys
  for equality. O(n) after O(n log n) sort.
- **Cross-platform .cljc**: All files are `.cljc` with reader conditionals for
  JVM/CLJS differences (StringBuilder vs StringBuffer, format-double, #inst/#uuid).

## What's NOT Built Yet

- **CEDN-R profile**: BigInt, BigDecimal, ratios.  Deprioritized indefinitely —
  KEX/Biscuit policies require only CEDN-P types.  The spec defines CEDN-R
  for completeness, but no implementation work is planned.
- **ClojureScript testing**: All code is `.cljc` ready, needs shadow-cljs setup.
- **Babashka support**: JCS Java dependency unavailable in bb — needs pure-Clojure
  fallback or bb pod for `format-double`.
- **CLI tool**: Trivial Babashka wrapper once bb support exists.
- **Kex integration**: Separate concern. Kex depends on CEDN, not vice versa.
