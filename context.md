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

**v1 implementation complete — JVM + Babashka + nbb + shadow-cljs + Scittle (5 platforms), CEDN-P profile.**

All modules done and tested on five platforms.  Zero production dependencies beyond Clojure.

| Module | Status | Description |
|--------|--------|-------------|
| `cedn.error` | Done | 7 error constructors (`unsupported-type!`, `invalid-number!`, `out-of-range!`, `duplicate-key!`, `duplicate-element!`, `invalid-unicode!`, `invalid-tag-form!`) |
| `cedn.number` | Done | Pure Clojure `ecma-reformat` post-processes `Double/toString` into ECMAScript format. Single `:clj` branch for JVM+bb. JCS is test-only cross-validation oracle. |
| `cedn.order` | Done | `type-priority` + `rank` comparator implementing §5 total ordering. `compare-strings` uses `.codePointAt` loop on bb (`:bb` reader conditional). |
| `cedn.emit` | Done | Core `emit`/`emit-str` with type dispatch, string escaping (§3.5), `#inst` (9 fractional digits), `#uuid` (lowercase hex via `uuid?`), set/map sorting + duplicate detection. CLJS: `.charCodeAt` for string chars; negative zero emits as `"0"` (JS -0.0 === 0). |
| `cedn.schema` | Done | Hand-written predicates for CEDN-P type contracts, `schema-for`/`valid?`/`explain` |
| `cedn.core` | Done | Public API: `canonical-bytes`, `canonical-str`, `valid?`, `explain`, `assert!`, `inspect` (SHA-256), `canonical?`, `rank`, `readers` |
| `cedn.gen` | Done | test.check generators for CEDN-P values |
| Property tests | Done | 4 properties × 200 iterations: idempotency, valid EDN, determinism, str/bytes agreement |
| Cross-platform bytes | Done | 40 values × 2 checks (canonical-str + bytes hex): proves all 5 platforms produce identical output for the same inputs. Compliance test vectors stored in `cedn-p-compliance-vectors.edn` (IETF RFC-style). |

**Test results: JVM 79 / 21,499, bb 74 / 1,458, nbb 70 / 389, shadow-cljs 74 / 393, Scittle 69 / 69 — 0 failures on all platforms.**
**Lint: 0 clj-kondo errors/warnings, cljfmt clean.**

**Persistent project memory is stored in MCP memory (tag: `cedn`).**
Use `memory_search` with query "CEDN" or filter by tag "cedn" to retrieve
design decisions, project state, and workflow notes across sessions.

## Reference Documents

| File | What it is |
|------|-----------|
| `docs/cedn-spec.md` | The formal specification.  §3 (CEDN-P types) and §5 (ordering) are the critical sections. |
| `docs/cedn-api-design.cljc` | The public API surface and module structure.  Function signatures and module dependency graph. |
| `docs/cedn-p-schema.cljc` | Original Malli schema design (historical reference). |
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
   JVM: `cedn/readers` maps `#inst` → `Instant/parse` and `#uuid` → `UUID/fromString`.
   The default EDN reader produces `java.util.Date` (ms precision), losing sub-ms digits
   from the 9-fractional-digit canonical `#inst` form. `Instant/parse` preserves nanosecond
   precision. CLJS: only overrides `#inst` → `js/Date.`; built-in EDN reader handles `#uuid`.
   `canonical?` uses `readers` internally on all platforms.

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
├── bb.edn                     ← Babashka project config
├── shadow-cljs.edn            ← shadow-cljs build config (CLJS :node-test)
├── package.json               ← npm deps (shadow-cljs)
├── scittle-tests.html         ← Scittle browser test page (69 tests)
├── dist/
│   └── cedn.cljc              ← Concatenated CEDN source for Scittle/browser (auto-generated)
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
│       ├── schema.cljc         ← hand-written type predicates
│       └── gen.cljc            ← test.check generators
└── test/
    └── cedn/
        ├── core_test.cljc      ← public API + round-trip tests
        ├── emit_test.cljc      ← per-type emission + Appendix C vectors
        ├── order_test.cljc     ← rank comparator tests
        ├── number_test.cljc    ← double formatting + Appendix B vectors
        ├── number-reference.edn            ← 1,051 JVM-generated reference vectors
        ├── cedn-p-compliance-vectors.edn  ← CEDN-P compliance test vectors (RFC-style)
        ├── error_test.cljc     ← error constructor tests
        ├── schema_test.cljc    ← schema validation tests
        ├── property_test.cljc  ← generative property tests
        └── xplatform_test.cljc ← cross-platform byte comparison tests
```

## Dependencies

```clojure
;; deps.edn
{:paths ["src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}}
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {io.github.cognitect-labs/test-runner
                       {:git/tag "v0.5.1"
                        :git/sha "dfb30dd6605cb6c0efc275e1df1736f6e90d4d73"
                        :git/url "https://github.com/cognitect-labs/test-runner"}
                       org.clojure/test.check {:mvn/version "1.1.1"}
                       io.github.erdtman/java-json-canonicalization {:mvn/version "1.1"}}
         :main-opts   ["-m" "cognitect.test-runner"]
         :exec-fn     cognitect.test-runner.api/test}
  :cljs {:extra-deps {org.clojure/clojurescript {:mvn/version "1.11.132"}
                      thheller/shadow-cljs {:mvn/version "2.28.23"}}}
  :cljs-test {:extra-paths ["test"]
              :extra-deps {org.clojure/test.check {:mvn/version "1.1.1"}}}}}
```

The only production dependency is Clojure itself.  Double formatting
uses a pure Clojure `ecma-reformat` that post-processes `Double/toString`
(JDK 17+ Schubfach algorithm) into ECMAScript Number::toString format.
The `java-json-canonicalization` library (JCS) is a **test-only**
dependency used to cross-validate `ecma-reformat` against the reference
ECMAScript implementation for 20,000+ doubles.

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

All checks must pass with zero errors and zero warnings before
any commit.  Run on ALL source and test files, not just modified ones.

### bb tasks (preferred)

```bash
# Run individual platform tests
bb test:jvm     # JVM (cognitect test-runner)
bb test:bb      # Babashka
bb test:nbb     # nbb (Node.js)
bb test:cljs    # shadow-cljs

# Lint & format
bb lint         # clj-kondo
bb fmt          # cljfmt check
bb fmt:fix      # cljfmt fix
bb gen:xref       # print cross-platform hex reference data
bb gen:compliance # verify all platforms agree, confirm golden file

# Run everything (JVM + bb + nbb + cljs + scittle + lint + fmt)
bb test:all

# Build concatenated Scittle source
bb build:scittle   # → dist/cedn.cljc

# Scittle (browser, automated via Playwright — auto-rebuilds dist/cedn.cljc)
bb test:scittle

# Prerequisite (one-time): npm install && npx playwright install chromium

# List all available tasks
bb tasks
```

### Long-form commands (reference)

```bash
# 1. Tests (JVM) — all must pass
clj -X:test

# 2. Tests (Babashka) — all must pass
bb -cp src:test -e '
(require (quote clojure.test)
         (quote cedn.number-test) (quote cedn.order-test)
         (quote cedn.core-test) (quote cedn.emit-test)
         (quote cedn.error-test) (quote cedn.schema-test))
(let [r (clojure.test/run-tests
          (quote cedn.number-test) (quote cedn.order-test)
          (quote cedn.core-test) (quote cedn.emit-test)
          (quote cedn.error-test) (quote cedn.schema-test))]
  (System/exit (if (pos? (+ (:fail r) (:error r))) 1 0)))'

# 3. Tests (nbb / Node.js) — all must pass
nbb -cp src:test -e '
(require (quote cljs.test)
         (quote cedn.error-test) (quote cedn.number-test)
         (quote cedn.order-test) (quote cedn.schema-test)
         (quote cedn.emit-test) (quote cedn.core-test))
(cljs.test/run-tests
  (quote cedn.error-test) (quote cedn.number-test)
  (quote cedn.order-test) (quote cedn.schema-test)
  (quote cedn.emit-test) (quote cedn.core-test))'

# 4. Tests (shadow-cljs / full CLJS) — all must pass
npx shadow-cljs compile test

# 5. Tests (Scittle / browser) — automated via Playwright
node test/run-scittle.mjs
# Prerequisite (one-time): npm install && npx playwright install chromium

# 6. Linting — must report 0 errors, 0 warnings
clj-kondo --lint src test

# 7. Formatting — must report all files correct
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

## Cross-Platform Notes

### Babashka (bb)
Done. Pure Clojure `ecma-reformat` serves both JVM and bb.
`compare-strings` uses `.codePointAt` loop on bb (`:bb` reader conditional).
Full test suite passes (70 tests, 1,294 assertions).
Cross-platform reference test verifies bb output matches JVM for 1,051 doubles.

### nbb (Node.js Babashka)
Done. Exercises `:cljs` reader conditional branches on the JS runtime via SCI.
Full test suite passes (66 tests, 225 assertions).

Key CLJS fixes:
- `emit-string-char`: `(int ch)` → `.charCodeAt` (JS `(int "h")` returns 0)
- Negative zero: JS `-0.0 === 0` (same value); both emit as `"0"` via integer
  path (`"0.0"` can't round-trip through `edn/read-string` on CLJS)
- `format-inst`: uses `js/Date` (ms precision only, last 6 of 9 digits zero)
- nbb uses `cljs.test/run-tests` (not `clojure.test/run-tests`)

Platform-legitimate test differences (guarded with reader conditionals):
- int/double distinction (JS has no separate types)
- Ratios (`22/7` evaluates to a double in JS)
- SHA-256 (nil on CLJS, no built-in crypto)
- Nanosecond `#inst` (JS Date has ms precision only)

### shadow-cljs (full ClojureScript)
Done. Uses `:node-test` target with shadow-cljs. Includes property tests
(4 × 200 iterations) which caught the negative-zero round-trip issue.
Full test suite passes (70 tests, 229 assertions).

Additional CLJS fixes for shadow-cljs:
- `gen/large-integer*` range limited to `Number.MAX_SAFE_INTEGER` (2^53-1)
- `22/7` ratio guarded with `#?(:clj ...)` (not a valid CLJS constant)
- Property tests 3 & 4 made cross-platform (vec comparison, TextDecoder)

### Scittle (browser)
Done. Loads source files via `<script type="application/x-scittle">` tags
with Scittle v0.8.31 CDN. 59 tests covering all modules pass in the browser.

Scittle-specific fixes (SCI symbols not available):
- `cljs.core/UUID` → `uuid?` (cross-platform predicate, works everywhere)
- `ExceptionInfo` → `(or (ex-data e) ...)` idiom (avoids bare symbol)
- `uuid` macro → removed from CLJS readers (built-in EDN reader handles it)

Build single-file bundle: `bb build:scittle` → `dist/cedn.cljc`.
Automated test: `bb test:scittle` (rebuilds + headless Chromium via Playwright).
CDN: `https://cdn.jsdelivr.net/gh/franks42/canonical-edn@main/dist/cedn.cljc`

## What's NOT Built Yet

- **CEDN-R profile**: BigInt, BigDecimal, ratios.  Deprioritized indefinitely —
  KEX/Biscuit policies require only CEDN-P types.
- **CLI tool**: Trivial Babashka wrapper, now unblocked.
- **Kex integration**: Separate concern. Kex depends on CEDN, not vice versa.
- **Web Crypto SHA-256**: Browser `SubtleCrypto.digest` is async; `inspect`
  returns nil for SHA-256 on CLJS. Could wrap with async support later.
