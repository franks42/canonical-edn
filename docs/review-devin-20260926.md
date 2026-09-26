# Review: canonical-edn (CEDN)

**Date:** 2026-09-26
**Reviewer:** Devin (Cognition)
**Project version at review:** 1.6.0 (main, commit 2b40fc7)
**Scope:** Correctness against `docs/cedn-spec.md`, cross-platform byte
identity, API consistency, tests, packaging. All findings verified by
running the code (JVM 25 + nbb 1.x).

**Baseline:** `clojure -X:test` — 147 tests, 21,848 assertions, 0 failures.

---

## Verdict

This is a well-engineered library. The spec is unusually rigorous (an
RFC-style draft with ABNF, ordering rules, security considerations and
conformance vectors), the implementation is small (~1,600 LOC across 9
namespaces) and reads cleanly, and the test/distribution infrastructure
is the strongest part of the project: golden compliance vectors,
property tests for idempotence/injectivity/order-independence, a five-
platform CI matrix, a stale-`dist/` check, published-artifact smoke
tests on a weekly schedule, and release-time version agreement checks.
The fail-closed posture (throw rather than emit ambiguous bytes, refuse
unsupported profiles, refuse to load on a JVM whose `Double/toString`
isn't shortest-round-trip) is exactly right for a signing-oriented
serializer.

Two confirmed bugs break the core guarantee — both reachable only in
specific environments, both worth fixing before 1.7.0.

---

## Findings

### 1. CRITICAL — `#inst` emission is locale-dependent on JVM/bb

`cedn.emit/format-inst` formats the timestamp with

```clojure
(format "%04d-%02d-%02dT%02d:%02d:%02d.%09dZ" ...)
```

`clojure.core/format` (i.e. `String/format`) uses the *default locale*,
and `%d` localizes digits. Under a non-Latin-digit locale the canonical
bytes change:

```clojure
(java.util.Locale/setDefault (java.util.Locale/forLanguageTag "ar-EG"))
(cedn/canonical-str (Instant/parse "2020-01-02T03:04:05.123456789Z"))
;; => #inst "٢٠٢٠-٠١-٠٢T٠٣:٠٤:٠٥.١٢٣٤٥٦٧٨٩Z"
```

The same instant produces different bytes on an identically-versioned
JVM running with `LANG=ar_EG.UTF-8` or `-Duser.language=ar` — a direct
violation of §8.1 ("any implementation bug that produces different
bytes for the same logical value is a security vulnerability"). A
signature produced on such a machine is unverifiable everywhere else.
`bin/cedn` on Babashka has the same exposure.

Verified unaffected: `%x`/`%02x`/`%04x` do **not** localize digits, so
`format-bytes`, `escape-control-char` and `sha-256-hex` are safe —
`format-inst` is the only broken site.

**Fix:** `(format java.util.Locale/ROOT "%04d-%02d-..." ...)` (or build
the string without `format`, matching the CLJS `pad` approach).
**Test:** none — CI runners are `en_US`. A JVM test that sets the
default locale to `ar-EG` inside the test would catch a regression.

### 2. HIGH — CLJS emits out-of-range whole numbers as integers

In `cedn.emit/emit`, the 64-bit range check in the `int?` branch is
`#?(:clj)`-only. On JS the check is absent entirely:

```clojure
;; nbb / shadow-cljs
(cedn/canonical-str 1e20)                   ;; => "100000000000000000000"
(cedn/canonical-str -1e20)                  ;; => "-100000000000000000000"
(cedn/canonical-str 9223372036854775808)    ;; => "9223372036854776000"  (!)
(cedn/canonical-str {1e20 :a})              ;; => "{100000000000000000000 :a}"

;; JVM, same IEEE-754 doubles
(cedn/canonical-str 1e20)                   ;; => "100000000000000000000.0"
```

Spec §3.3 is explicit: on ClojureScript a number is an integer **iff**
`x == Math.trunc(x)` *and* it is within the signed 64-bit range. `1e20`
fails the range clause, so it is a double and must serialize as
`"100000000000000000000.0"` — which is also what the JVM produces, so
the spec-conforming behaviour is simultaneously the cross-platform-
consistent one. As implemented, JS emits byte-divergent output for
every whole-valued double in `[2^63, 1e21)` (and `(-1e21, -2^63]`).

Why `1e21` escapes: `cljs.core/integer?` is
`(== (js/parseFloat n) (js/parseInt n 10))`; `parseInt` breaks on the
exponential `toString` at ≥1e21, so those fall into the double branch
by accident rather than by design. The `2^63` example additionally
mangles the digits (`9223372036854775808` → `9223372036854776000`)
while the JVM rejects the same literal as a BigInt — three different
behaviours (emit-int on JS, `.0`-double per spec, error on JVM literal)
for one logical value.

`number_test.cljc` tests `format-double 1e20` directly, so the
formatter is covered — but emit's dispatch never reaches it on JS,
which is why the suite doesn't catch this. `xplatform-test`'s ns
docstring already concedes avoiding "the JS int/double boundary"; this
case is past that boundary (range, not int/double ambiguity).

**Fix:** make the dispatch range-aware on CLJS, e.g. treat
`(and (number? v) (== v (Math/trunc v)) (<= -9223372036854775808 v 9223372036854775807))`
as the integer test and let larger whole numbers take the
`format-double` path. Consider adding `1e20`/`2^63` to the compliance
vectors — they currently can't appear there because vectors must agree
on all platforms and this doesn't.

### 3. MEDIUM — `java.sql.Date`/`Time` throw raw `UnsupportedOperationException`

`emit` accepts `instance? java.util.Date`; `java.sql.Date` and
`java.sql.Time` are `Date` subclasses whose `.toInstant` always throws
`UnsupportedOperationException`:

```clojure
(cedn/canonical-str (java.sql.Date. 0))
;; java.lang.UnsupportedOperationException — no ex-data, no :cedn/error
```

README promises "Every error is `ex-info` carrying `:cedn/error`".
`inspect` catches it (returns `{:status :error}`) but the error map
lacks `:cedn/error`, so programmatic classification fails. Fix by
checking `(.getClass v)` is exactly `java.util.Date`, or catching and
re-throwing `:cedn/unsupported-type`.

### 4. MEDIUM-LOW — `valid?`/`check` accept values that can't canonicalize

`cedn.schema` checks *types*, not value ranges:

```clojure
(cedn/valid? (Instant/parse "+10000-01-01T00:00:00Z"))   ;; => true
(cedn/canonical-str (Instant/parse "+10000-01-01T00:00:00Z"))
;; throws :cedn/out-of-range
;; likewise an invalid js/Date (NaN time) passes valid? on CLJS
```

`check`'s docstring says "value, if it is valid CEDN" — a year-10000
inst isn't. Either tighten `schema` (year check is cheap and mirrors
`emit`) or narrow the docstrings to "valid *types*". Same class:
`int?`-typed values are always in range on JVM so the emit range check
there is dead-but-harmless.

### 5. LOW — unbounded recursion on nested input

`emit`, `rank`, `sort-canonical`, `cedn-p-explain` all recurse; ~20k
nested vectors → `StackOverflowError` on the JVM default stack. Two
footnotes:

- `canonical?` and `inspect` catch `Exception` only, so the SOE
  propagates out of functions documented as "returns true/false" /
  "never throws" (CLJS catches `:default`, which does catch the
  `RangeError`).
- `bin/cedn` catches `Throwable` and exits 1 — fine.

Spec §8.4 makes depth bounding the application's job, so this is
conformant; worth one docstring sentence on `canonical?`/`inspect`.

### 6. LOW — `rank` returns 0 for any two unsupported values

`(order/rank (atom 1) (atom 2))` → `0`; likewise any two values whose
`tag-kind` is `:unknown`. `cedn/rank` is public; sorting unsupported
values with it silently treats them as equal (and `sorted-set-by` would
collapse them). The docstring could state the domain is CEDN-P values,
or `compare-tagged` could throw on `:unknown`.

### 7. LOW — `canonical?` semantics worth a docstring sentence

`canonical?` verifies the whole string is *exactly one* canonical form:
`"{:a 1}\n"` → `false`, `"{:a 1} {:b 2}"` → `false`. Correct and
intentional, but the CLI emits a trailing newline per form, so
`cedn | cedn/canonical?`-style usage surprises. One sentence would help.

### 8. TRIVIAL — `emit-forms!` dies inside `try`/`finally`

`die` calls `System/exit` from inside the `try` in `emit-forms!`; the
`finally` in `main` that closes `--output`/`--input` streams is skipped.
Harmless in practice (each form is flushed as written, and exit closes
fds) but the asymmetry means a "cleaner" error path silently isn't.

### 9. TRIVIAL — `parse-inst` truncates >9 fractional digits silently

`nanos-of` zero-pads or *truncates* the fraction to 9 digits. Reading
`#inst "...1234567895"` yields an instant 0.5ns different from the text
— a quiet precision change in a reader whose ns-docstring advertises
"rejected rather than silently truncated". Canonical output can never
produce this, so it only affects lenient reads of foreign input;
consider rejecting >9 digits or documenting it.

---

## Confirmation of things that are right

Probed and behaved correctly:

- `int?` returns false for `BigInt`/`BigInteger` on JVM — out-of-range
  literals correctly become `:cedn/unsupported-type`, matching §3.3's
  note and the README table. The `(long value)` range check can't be
  bypassed by truncation.
- `3/4`, `\a`, `1.5M`, `1.5f` (Float), records-as-maps, sorted-maps,
  atoms — all rejected or normalized as specified.
- `explain` reports real paths (`{:a [1 {:b ##NaN}]}` →
  `:cedn/path [:a 1 :b]`).
- `canonical?` handles `nil`, empty string, multi-form strings,
  `#inst` non-canonical spellings, `#:a{:b 1}` ns-map syntax correctly.
- Token validation: `:'a`, `:#a`, `:a#_b`, `:a'b`, `a:b`, `'/`,
  `clojure.core//`, `foo/nil` round-trip and conform; `a;b`, `a,b`,
  `(symbol "nil")`, `(keyword "a/b" "c")`, leading-digit
  symbols, `#_x` correctly rejected. The unqualified-keyword exemption
  (`:200`, `:#a` valid, `:ns/1a` invalid) matches §3.6.1 exactly.
- `#inst` year 0000 and 9999 boundaries, leap-second rejection,
  `Date.UTC` 0–99 year quirk — handled.
- `-0.0` → `"0.0"`, `0.0` → `"0.0"` via the `ecma-reformat` path —
  traced and correct.
- `sort-canonical` reuses the canonical text computed for sorting as
  the emitted output — keys/elements are emitted once, not twice.
- `version-test` pins `cedn.core/version` to `build.clj` and `bin/cedn`;
  `release.yml` pins both to the tag. No drift possible.
- `dist/cedn.cljc` staleness is a CI gate; `uuid-ctor` workaround for
  Scittle is documented in context.md.
- JDK-19 check probes behaviour (`Double/toString` output) rather than
  version strings — the right call.

## Test-coverage notes

- Property tests run on JVM and shadow-cljs (`ns-regexp "-test$"` +
  `:cljs-test` alias) but are excluded from `test:bb`/`test:nbb`'s
  explicit namespace lists — bb bundles test.check, so they could run
  there for extra platform coverage.
- No test sets `Locale/setDefault`, exercises `|x| ≥ 2^63` on JS, or
  passes a `java.sql.Date` — the three gaps behind findings 1–3.
- `gen.cedn-p` never generates doubles/insts/uuids as map keys
  (deliberate, to avoid canonical-duplicate flakiness), so key-ordering
  for those types rests only on the `order_test` unit vectors — thin
  but probably adequate.

## Housekeeping

- `docs/` carries ~120 KB of May-era AI analyses
  (`canonical-edn-analysis*.md`, `kex-sources.md`) plus the historical
  `cedn-api-design.cljc`/`cedn-p-schema.cljc` sketches. Fine to keep,
  but a `docs/archive/` or a README note that they're historical would
  help a new reader rank them against the normative spec.
- `scittle-tests.html` sits at repo root while `test/` holds the other
  Scittle harness files — either it's orphaned or worth moving for
  tidiness (check before moving; it may be referenced).

## Suggested priority

1. `Locale/ROOT` in `format-inst` + a `setDefault`-mutating test.
2. CLJS 64-bit range gate on the integer path + `1e20`/`2^63` cases in
   the emit tests and (once fixed) the compliance vectors.
3. `java.sql.Date`/`Time` rejection with `:cedn/error`.
4. Docstring/doc alignment: `check` vs inst-range, `canonical?` single-
   form semantics, `rank` domain.
5. Optional: property tests on bb/nbb; `>9` digit `#inst` fraction
   rejection.

---

## Resolution (1.6.1, 2026-09-26)

Reviewed and verified by reproduction (JVM 25, nbb). Fixed, each with a
test shown to fail without the fix (see CHANGELOG):

| # | Result |
|---|---|
| 1 | Fixed: `Locale/ROOT`; every compliance vector runs under ar-EG, fa-IR, hi-IN (Devanagari) and th-TH (Thai digits). |
| 2 | Fixed, together with a case the review missed: on JS, whole numbers between 2^53 and 2^63 printed as a *different* integer (2^60 → `1152921504606847000`); the review's 2^63 example is partly this. One predicate, `cedn.number/cedn-int?`, for emit, ordering and schema; exact digits via `BigInt`; four new compliance vectors. |
| 3 | Fixed: `:cedn/unsupported-type`; `valid?` false; `Timestamp` still accepted. |
| 4 | Fixed: the schema checks the `#inst` year range (and NaN dates); the error message no longer says "integer". |
| 5 | Fixed: `canonical?` and `inspect` catch `StackOverflowError` (JVM). |
| 6 | Documented: `rank`'s domain is CEDN-P values. |
| 7 | Documented in `canonical?`. |
| 8 | Fixed: CLI errors close the streams before exiting. |
| 9 | Fixed: non-zero sub-nanosecond digits are refused; zeros accepted. |

Not done: property tests on bb/nbb, archiving the May-era docs, moving
`scittle-tests.html` (housekeeping, optional).

---

## Verification of the 1.6.1 fixes (Devin, 2026-09-26, re-run)

Each claim above was re-verified by reproduction on JVM 25 and nbb;
JVM suite: 153 tests / 22,076 assertions, 0 failures; nbb: 110 / 692, 0.

| # | Verified |
|---|---|
| 1 | `Locale/setDefault` to `ar-EG` now yields `#inst "2020-01-02T03:04:05.123456789Z"`. The test sweeps all compliance vectors under ar-EG, fa-IR, hi-IN (Devanagari) and th-TH — stronger than the suggested fix. |
| 2 | nbb: `1e20` → `"100000000000000000000.0"` (matches JVM's double), `2^63` → `"9223372036854776000.0"`, `-2^63` → `"-9223372036854775808"` (in-range integer, exact), `2^60` → `"1152921504606846976"` — the exact digits, not JS's `"1152921504606847000"`. That 2^53–2^63 misprint was indeed missed here; good catch. `cedn-int?` is used consistently by emit, `compare-numbers`/`exact-decimal`, and `valid?`/`explain`; four new compliance vectors cover the boundaries. |
| 3 | `java.sql.Date`/`Time` → `ex-info` `:cedn/unsupported-type`, `valid?` false; `Timestamp` works including `.setNanos`. |
| 4 | `valid?`/`explain` on a year-10000 `Instant` → false / `:cedn/out-of-range`, with correct `:cedn/path` in nested structures; invalid `js/Date` rejected on CLJS. |
| 5 | 50k-deep nesting: `canonical?` → false, `inspect` → `{:status :error}` (regression test in `core_test`). |
| 6/7 | `rank` and `canonical?` docstrings state their domain/semantics as suggested. |
| 8 | `fail` throws through `emit-forms!` so `finally` closes streams; main prints and exits. Parse-error exit code 1 and streaming partial output preserved (verified via CLI). |
| 9 | `parse-inst` accepts `"…​.1230000000000Z"` (trailing zeros) and refuses `"…​.1234567895Z"` and `"…​.0000000000001Z"` with `:cedn/invalid-tag-form`. |

Also correct: spec §3.3 now states the exact-digits requirement for JS
integers; `dist/cedn.cljc` regenerated; version 1.6.1 consistent across
`core.cljc`, `build.clj`, `bin/cedn`, README.

### Residual nits (very low severity, new or remaining)

- `schema/inst-in-range?` calls `.toInstant` on any `java.util.Date`
  whose class is not literally named `java.sql.Date`/`Time`. A *subclass*
  of those (or any exotic `Date` impl whose `.toInstant` throws) would
  make `valid?`/`explain`/`check` throw a raw `UnsupportedOperationException`
  rather than return false/an error map — `emit` catches it, schema does
  not. Only reachable via custom subclasses; a `try` around the call
  would close it.
- `cedn-int?` on CLJS requires `number?`, so `goog.math.Long`/`Integer`
  instances — previously emitted as integers via `int?` — now get
  `unsupported-type`. Spec-conformant (JS numbers only), but it is a
  behaviour change for a hypothetical caller.
- `bin/cedn`: `die` is still used for `--input`/`--output` open failures;
  on the output-open path the already-opened input stream is never
  closed. The process is exiting anyway — cosmetic.
- The `sql-time` eval-guard helper is copy-pasted into three test
  namespaces; a shared `cedn.test-util` ns would dedupe it.

### Response to the residual nits (2026-09-26, unreleased on main)

- **Date subclass**: reproduced with a proxied `java.util.Date`. Fixed: a
  `Date` is an inst only if `.toInstant` works, replacing the class-name
  check, so `valid?`/`explain` agree with `emit` for every subclass
  (`:cedn/unsupported-type`). Test shown to fail before the fix.
- **`goog.math.Long`/`Integer` on CLJS**: kept rejected (spec §3.3 covers JS
  numbers only; ordering never treated them as numbers). Recorded in the
  1.6.1 CHANGELOG as a behaviour change not noted at release.
- **`bin/cedn` output-open path**: fixed; both streams are opened inside
  the error handling, so a failure opening one closes the other.
- **`sql-time` duplication**: moved to `cedn.test-util` (with an `on-jvm`
  helper for values bb cannot build).
