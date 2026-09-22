# Review: canonical-edn (CEDN)

**Date:** 2026-06-05
**Reviewer:** Cascade / Kimi
**Project version at review:** 1.3.1
**Scope:** Architecture, cross-platform design, source quality, test coverage

---

## Architecture & How It Works

### Module Dependency Graph

```
┌─────────────────────────────────────────────────────────────┐
│  PUBLIC API  (cedn.core)                                     │
│  canonical-bytes / canonical-str / valid? / explain /        │
│  assert! / inspect / canonical? / rank / readers             │
└─────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌──────────────┐    ┌─────────────────┐    ┌──────────────┐
│ cedn.emit    │    │ cedn.schema     │    │ cedn.order   │
│ emit-str     │    │ valid? / explain│    │ rank         │
│ (StringBuilder│    │ (type contract) │    │ (comparator) │
│  type dispatch)│   │                 │    │              │
└──────────────┘    └─────────────────┘    └──────────────┘
        │                     │                     │
        ▼                     │                     ▼
┌──────────────┐              │            ┌──────────────┐
│ cedn.number  │              │            │ cedn.error   │
│ format-double│              │            │ 7 error fns  │
│ ECMA-262 fmt │              │            │ (ex-info)    │
└──────────────┘              │            └──────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ cedn.gen        │
                    │ test.check gens │
                    │ (property tests)│
                    └─────────────────┘
```

### Data Flow: `canonical-bytes`

```
Value ──► [schema validation?] ──► emit-str ──► StringBuilder
                                    (sort maps/sets via rank)
                                    (format doubles via number)
                                    (escape strings)
                                    ──► Canonical String ──► UTF-8 bytes
```

### Module Responsibilities

| Module | Role |
|---|---|
| **`cedn.core`** | Public API facade. Delegates to emit, order, schema. Provides `inspect` (diagnostics + SHA-256), `canonical?` (round-trip checker), `readers` (EDN tag readers for `#inst`/`#uuid`/`#bytes`). |
| **`cedn.emit`** | Inner loop: type-dispatched text emission into a `StringBuilder`/`StringBuffer`. Handles nil, bool, int, double, string (escaping), keyword, symbol, list, vector, set (sort + duplicate detection), map (sort-by key + duplicate detection), `#inst` (9 fractional digits, UTC), `#uuid` (lowercase), `#bytes` (lowercase hex). |
| **`cedn.order`** | Total ordering comparator (`rank`) used for sorting map keys and set elements. Implements §5 of the spec: type priority → type-specific comparison (numbers by mathematical value, strings by Unicode codepoint, keywords/symbols by ns+name, collections element-wise, tagged literals by tag-kind then value). |
| **`cedn.number`** | Cross-platform double formatting. On JVM: pure Clojure `ecma-reformat` post-processes `Double/toString` (Schubfach) into ECMA-262 §7.1.12.1 format. On CLJS: `Number.prototype.toString()` is already spec-compliant. Rejects `NaN`/`Infinity`. Emits `-0.0` as `"0.0"`. |
| **`cedn.schema`** | Hand-written recursive predicates for the CEDN-P type contract (`cedn-p-valid?` / `cedn-p-explain`). Only `:cedn-p` profile is actually implemented. |
| **`cedn.error`** | Standardized `ex-info` constructors: `unsupported-type!`, `invalid-number!`, `out-of-range!`, `duplicate-key!`, `duplicate-element!`, `invalid-unicode!`, `invalid-tag-form!`. |
| **`cedn.gen`** | `test.check` generators for property-based testing of CEDN-P values. |

### Cross-Platform Matrix

| Platform | Runtime | Test Command | Distribution |
|---|---|---|---|
| JVM | `clj` | `bb test:jvm` | Maven JAR (Clojars) |
| Babashka | `bb` | `bb test:bb` | Same Maven JAR |
| nbb (Node.js) | `nbb` | `bb test:nbb` | Git dependency |
| shadow-cljs | `shadow-cljs` | `bb test:cljs` | Source on classpath |
| Scittle (browser) | Browser | `bb test:scittle` | CDN (`dist/cedn.cljc`) |

---

## Suggested Improvements

### 1. Version String Drift in `test:jar` (Minor — Correctness)

`bb.edn:69-71` hardcodes `"1.2.0"` for the smoke-test Maven coordinate:

```clojure
{:deps {com.github.franks42/cedn {:mvn/version "1.2.0"}}}
```

Current version is `1.3.1`. The `release-check` task validates `bin/cedn`, `build.clj`, and `core.cljc`, but **not** this inline string. The task should either read the version from `build.clj` or `bin/cedn`, or `release-check` should include this file in its validation.

**Impact:** `bb test:jar` silently tests against an old version instead of the freshly built one.

---

### 2. `:cedn-r` Profile Exists in API but Is Not Implemented (Medium — API Clarity)

`core.cljc` accepts `:profile :cedn-r` in `canonical-bytes`, `canonical-str`, `valid?`, etc. But `schema.cljc:116-119` throws for any profile other than `:cedn-p`:

```clojure
(defn schema-for [profile]
  (case profile
    :cedn-p :cedn-p
    (throw (ex-info ...))))
```

**Options:**
- Implement `:cedn-r` (JVM-specific numeric types like `BigDecimal`, `BigInt` beyond 64-bit, perhaps `java.math.BigInteger`).
- Remove `:cedn-r` from the public API surface and docs to avoid user confusion.

Given the project's "one-way normalization for crypto" design goal, option 2 may be preferable until `:cedn-r` is fully specified.

---

### 3. Schema `valid?` Does Not Catch Out-of-Range Integers (Medium — Contract Consistency)

`schema.cljc:39` accepts all `int?` values:

```clojure
(int? v) true
```

But `emit.cljc:187-190` throws `out-of-range!` for integers outside signed 64-bit:

```clojure
(when-not (and (>= (long value) -9223372036854775808)
               (<= (long value) 9223372036854775807))
  (err/out-of-range! value))
```

So `(cedn/valid? 9223372036854775808N)` returns `true`, but `(cedn/canonical-bytes ...)` throws. The schema should enforce the same bounds as the emitter to keep the contract consistent. Consider a helper like `cedn-p-integer?` that checks both `int?` and 64-bit range.

---

### 4. `canonical?` Is Expensive for Large Values (Low — Documentation)

`core.cljc:163-173` fully parses then re-emits the entire value:

```clojure
(let [value (edn/read-string {:readers readers} edn-str)
      result (canonical-str value {:profile profile})]
  (= edn-str result))
```

For large structures this is O(n) parse + O(n) emit. This is fine for typical use, but the docstring should note the cost. A streaming or direct-validation approach (e.g., checking whitespace, map/set ordering, string escaping without full re-emit) is possible but likely overkill.

---

### 5. `invalid-unicode!` Is Dead Code (Low — Cleanup)

`error.cljc:50-56` defines `invalid-unicode!` but it is never called by `emit.cljc`. The string emitter handles control-char escaping but does not validate unpaired surrogates.

**Options:**
- Wire the check into `emit-string` if CEDN-P intends to reject malformed UTF-16/invalid Unicode.
- Remove the dead constructor to reduce API surface.

If CEDN-P's stance is "we escape whatever the runtime gives us," then removal is cleanest.

---

### 6. CLJS `inspect` SHA-256 Always Returns `nil` (Low — UX)

`core.cljc:89-94`:

```clojure
#?(:cljs
   (defn- sha-256-hex [_bs] nil))
```

This should be documented in `inspect`'s docstring. Users on CLJS may expect `:sha-256` to work and be surprised by silent `nil`. Alternatively, gate SHA-256 behind an optional async promise-based API (e.g. `inspect-async`) or omit the key entirely in CLJS.

---

### 7. `format-bytes` Could Use the StringBuilder Directly (Low — Performance)

`emit.cljc:109-118`:

```clojure
(defn- format-bytes [value]
  ...
  #?(:clj  (apply str (map hex-char (seq value)))
     :cljs (apply str (map ...))))
```

The caller (`emit`) already has a `StringBuilder`/`StringBuffer` in hand. `format-bytes` builds an intermediate string, then `.append sb` appends it. Pass the builder in and append hex chars directly to eliminate the intermediate string allocation. For large byte arrays (e.g. cryptographic hashes, signatures), this is a measurable win.

---

### 8. `bb gen:compliance` Does Not Write the File (Medium — Functional Bug)

`bb.edn:115-139`:

```clojure
:gen:compliance
  {:doc  "Verify all platforms agree, then write golden test vectors file"
   ...
   (println "Golden file: test/cedn/cedn-p-compliance-vectors.edn")
   (println "All platforms produced identical output."))}
```

The task description says "write golden test vectors file" but only prints to stdout. The actual `spit` call to `test/cedn/cedn-p-compliance-vectors.edn` is missing. The inline vectors in `xplatform_test.cljc` exist, but the file-based vectors (`cedn-p-compliance-vectors.edn`) are presumably maintained by hand.

**Fix:** Add the `spit` call, or update the task docstring if file writing is intentionally manual.

---

### 9. `compare-strings` CLJS Copies Strings to Arrays (Low — Memory)

`order.cljc:67-81`:

```clojure
:cljs (let [aa (js/Array.from a) ...])
```

`js/Array.from` materializes the full string as an array. For very long strings in sorted collections, this doubles memory. Consider iterating by index with `.codePointAt` directly on the string (with manual surrogate-pair advancement). This is a niche concern — only relevant if users canonicalize data with multi-kilobyte string keys.

---

### 10. Property Test Map Keys Are Inconsistent with Leaf Generator (Low — Test Coverage)

`gen.cljc:87-96`:

```clojure
(gen/map
  (gen/one-of
    [(gen/return nil)
     gen/boolean
     (gen/large-integer* {:min -1000 :max 1000})  ;; narrow range
     gen/string-alphanumeric
     (gen/fmap keyword gen-edn-name)
     (gen/fmap symbol gen-edn-name)])
  inner
  {:max-elements max-size})
```

Map key generator omits `gen-inst`, `gen-uuid`, `gen-finite-double`, and uses a narrow integer range vs. the full-range `gen-cedn-p-leaf`. If the restriction is deliberate (hashability or ordering stability concerns in test.check), document why in a comment. If not, widen it to match the leaf generator so property tests exercise the full key space — e.g. maps with `#{:foo #uuid "..." 3.14}` as keys.

---

### 11. `context.md` Has Stale Version References (Low — Maintenance)

`context.md:60` lists Maven coordinate as `1.2.0` despite project being at `1.3.1`. This is a living project file — keep it in sync or auto-generate it. Consider a `bb` task that regenerates the version line, or add a check to `release-check`.

---

### 12. Scittle Build Could Strip Intermediate `ns` Forms (Low — Compatibility)

`bb.edn:37-52` (`build:scittle`) concatenates source files in dependency order. Each file retains its `(ns ...)` form. In Scittle, multiple `ns` declarations in a single script may cause unexpected namespace-switching side effects. Consider stripping all but the first `ns` declaration, or verify Scittle handles this gracefully. The current setup works (tests pass), so this is more of a defensive suggestion.

---

### 13. `bin/cedn` Error Handling for `add-deps` Failures (Low — Robustness)

`bin/cedn:38-45`:

```clojure
(let [script-file ...
      cedn-core (java.io.File. src-dir "cedn/core.cljc")]
  (if (.exists cedn-core)
    (cp/add-classpath ...)
    (deps/add-deps {:deps {'com.github.franks42/cedn {:mvn/version version}}})))
```

If `deps/add-deps` fails (network error, Clojars down, version not yet indexed), the script fails with a stack trace rather than a clean error message. Consider wrapping in `try/catch` with a user-friendly exit.

---

### 14. `bb.edn` Test Task Repetition (Low — DRY)

`bb.edn:8-18` (bb tests) and `bb.edn:21-32` (nbb tests) both enumerate the same 6 test namespaces by hand. A single `def` of the namespace list at the top of the tasks map would make adding a new test namespace a one-line change instead of four.

---

## Overall Assessment

The codebase is **well-architected, thoroughly tested across 5 platforms, and carefully designed for cryptographic determinism**. The suggestions above are all minor — no structural changes needed.

**Strongest recommendations (highest impact / easiest fix):**

| Priority | Item | Location | Why |
|---|---|---|---|
| High | #1 Version drift in `test:jar` | `bb.edn:69-71` | Silent testing of stale version |
| High | #3 Schema/emitter contract mismatch | `schema.cljc:39`, `emit.cljc:187-190` | `valid?` lies about large ints |
| Medium | #8 Missing file write in compliance generator | `bb.edn:115-139` | Docstring says it writes; it doesn't |
| Medium | #2 `:cedn-r` placeholder | `schema.cljc:116-119` | Unclear API contract |
| Low | #5 Dead code `invalid-unicode!` | `error.cljc:50-56` | Cleanup |
| Low | #6 CLJS `inspect` SHA-256 `nil` | `core.cljc:89-94` | UX / documentation |
| Low | #7 `format-bytes` intermediate string | `emit.cljc:109-118` | Minor allocation win |
