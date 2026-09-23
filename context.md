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

**v1.5.2 — runtimes and published-artifact tests (no library code change). v1.5.1 was a documentation release. v1.5.0 brought profile enforcement, strict `#uuid` and CLI I/O correctness, on top of v1.4.0 (determinism, injectivity, strict readers). Requires JDK 19+ on the JVM.**

5 library platforms (JVM + Babashka + nbb + shadow-cljs + Scittle) plus a sixth artifact: `bin/cedn`, the CLI filter. Zero production dependencies beyond Clojure.

## Session Handoff (2026-09-22)

Everything from the 2026-09 review is closed and released (v1.4.0,
v1.5.0, v1.5.1, v1.5.2 — see decisions 6–13 and the CHANGELOG).  `main` is
clean, CI is green, all five platforms plus the CLI pass locally.

### Open items, roughly in priority order

1. **`inspect` returns `:sha-256 nil` on CLJS.**  `SubtleCrypto.digest`
   is async; would need an async variant or a bundled sync SHA-256.
2. **CEDN-R** stays unimplemented and actively rejected (decision 6).
3. **Low-priority suggestions from `docs/review-kimi-20260605.md`** not
   acted on: #7 `format-bytes` into the StringBuilder, #9 CLJS
   `compare-strings` array copies, #12 stripping `ns` forms from the
   Scittle bundle, #13 a clean error when `bin/cedn`'s `add-deps` fails,
   #14 `bb.edn` test-task repetition.  Its correctness items are done.

Closed in 1.5.2 (see CHANGELOG): README pins the CDN bundle to the
release tag instead of `@main`; runtimes updated (Clojure 1.12.6,
ClojureScript 1.12.145, shadow-cljs 3.5.3, Scittle 0.8.33, CI Node 26); spec header dates refreshed;
`bb test:scittle-cdn [ref]`, `bb test:nbb-git` and `bb test:published`
test what the README tells users to load, weekly via
`.github/workflows/published.yml`.  `bb test:scittle-cdn` with no
argument still tests `@main`.  1.5.2 was verified from outside: full
JVM and bb suites against the Clojars JAR, the release asset, and
`bb test:published`; jsdelivr and JAR sources are byte-identical to the
tag.

Since 1.5.2 (unreleased, tooling only): `bb lint` and `bb fmt` cover
every Clojure file — `src`, `test`, `bin/cedn`, `build.clj`, `bb.edn`,
`deps.edn`, `shadow-cljs.edn` — not just `src` and `test`.  `bb.edn` had
drifted (whitespace only, since v1.3.0) and was reformatted.  Excluded:
generated `dist/` and the historical sketches in `docs/`.

### Environment notes (macOS, this laptop — new machine, Sept 2026)

- **`clojure` vs `clj`**: Homebrew's `clojure` wrapper pins its own
  `JAVA_HOME`, so `JAVA_HOME=... clojure ...` does NOT switch JDKs — it
  silently runs the brew JDK.  To test on another JDK, invoke that JDK's
  `java` directly with `-cp "$(clojure -Spath -M:test)" clojure.main`.
  Scripts must call `clojure`, not `clj` (`clj` needs rlwrap, absent on
  CI runners).
- **Playwright**: `npx playwright install` downloaded Chromium fine but
  then hung indefinitely unpacking it (0% CPU).  Workaround: kill it and
  extract the zip from the temp dir with `ditto -x -k`, then `touch
  INSTALLATION_COMPLETE`.  Headless runs also need the separate
  `chromium_headless_shell-<rev>` build.
- **Scittle/sci gaps**: no `cljs.core/uuid`, no `UUID.` constructor (it
  does have `random-uuid`, `uuid?`).  `cedn.reader` builds UUIDs through
  the constructor of a `random-uuid` instance because of this.  Probe
  Scittle behaviour with a throwaway page + Playwright rather than
  assuming parity with shadow-cljs/nbb.
- **CLJS `array-map`** can produce a map with duplicate `=` keys, which
  cedn correctly rejects; do not build test maps that rely on it
  deduplicating.
- Tooling present: `bb`, `clojure`, JDK 25 (sdkman), `nbb`, `node`,
  `gh` (authenticated, git credential helper), Playwright browsers.

### Release procedure (as executed for 1.4.0 – 1.5.2)

1. `bb test:all`, plus `bb test:jar` and `bb test:cli-release`.
2. Bump `version` in `src/cedn/core.cljc`, `bin/cedn`, `build.clj`
   (`version-test` checks they agree; `release.yml` checks them against
   the tag).  `bb build:scittle` to refresh `dist/`.  Point the README's
   Maven coords, Scittle URL (`@vX.Y.Z`), CLI download URL and
   `cedn/version` example at the new version in the release commit.
   Leave the nbb snippet on the previous release's matching tag+sha pair.
3. CHANGELOG: turn `[Unreleased]` into the new version section.
4. Commit, push, wait for CI green, then `git tag -a vX.Y.Z` and push
   the tag — that triggers the Clojars deploy and GitHub Release.
5. Afterwards, move the README's nbb `:git/tag` and `:git/sha` to the new
   release together, in a follow-up commit (the tagged commit cannot
   contain its own sha, and a new tag with the old sha does not resolve).
6. Verify from outside: `bb test:published` (CDN bundle at the pinned tag
   reports the new version; nbb git dep resolves from an empty gitlibs
   cache), Clojars JAR 200, release asset downloads and runs with an
   empty `~/.m2`.

### v1.5.2 — runtimes and published-artifact tests

No library code change.  Clojure 1.12.6 (the JAR's pom now names it),
ClojureScript 1.12.145, shadow-cljs 3.5.3, Scittle 0.8.33, CI on Node 26.
README pins the CDN bundle to the release tag; `bb test:scittle-cdn
[ref]`, `bb test:nbb-git` and `bb test:published` (weekly in
`published.yml`) test what the README tells users to load.

### v1.5.1 — documentation

README rewritten around the two properties that define the library:
idempotence (many equivalent EDN spellings collapse to one canonical
form, and that form is itself EDN, so re-encoding is a no-op) and the
single all-types example with its canonical output.  Also added: the API
listing, the `cedn` CLI (undocumented since it shipped in 1.3.0), the
error table, and pointers to the spec and golden vectors.  The
compliance vector gained `#bytes` and the empty collections — it claimed
to cover every CEDN-P type but did not.  `docs/cedn-api-design.cljc` is
marked historical.  Spec §3.3 gained a note on BigInt vs `out-of-range`.

### v1.5.0 — profile enforcement and remaining review items

`:cedn-r` and unknown profiles are rejected instead of silently emitting
CEDN-P (decision 6).  The `#uuid` reader requires canonical 8-4-4-4-12
form, completing the strict-reader work.  `bin/cedn` no longer reports
success after a failed write, and `-o` is no longer an alias for
`--objects`.  `bb gen:compliance` actually writes the golden vectors
file, rank-ordered and free of raw control bytes, and re-verifies it.
`cedn.gen` now generates strings with escapes/control characters/astral
pairs, namespaced names, and nanosecond insts, plus `gen-bytes`.

### v1.4.0 — canonicalization fixes (decisions 7–13)

A review found cases where the same value produced different bytes, or
different values produced the same bytes.  `#inst` ordering no longer
goes through `Date.toString` (timezone-dependent), numeric ordering is
exact above 2^53, duplicates are detected by canonical text, unpaired
surrogates and unusable keyword/symbol names are rejected, `#inst` years
are limited to 0000–9999, and the `#inst`/`#bytes` readers are strict and
parse identically on every platform.  cedn now refuses to load on a JVM
older than JDK 19, where `Double/toString` is not shortest-round-trip.
Two new internal namespaces: `cedn.token`, `cedn.reader`.  CI
(`.github/workflows/ci.yml`) runs on every push and PR.

### v1.3.1 — release-workflow Maven-resolution fix

Patch over v1.3.0. CI workflow now runs `clojure -P` (prefetch project deps) before `clojure -T:build deploy`, so `tools.build/create-basis` can resolve `org.clojure/clojure 1.12.0` on a fresh CI runner where `~/.m2` is empty. v1.3.0's tag is a dud (workflow failed before reaching deploy); v1.3.1 supersedes it.

### v1.3.0 — `cedn` CLI shipped

Single executable bb script (`bin/cedn`) wrapping the library. Distributed as a versioned GitHub Release asset (`cedn-vX.Y.Z`); user installs via curl + `chmod +x`.

- **Subcommand-less**: positional input via `--edn <string>` / `--input <file>` / stdin; output via stdout / `--output <file>`.
- **Streaming**: reads top-level EDN forms one at a time, emits canonical bytes immediately, EPIPE-clean.
- **Two output modes**: default newline-separated with trailing newline, `--objects` for single-space-separated compact concatenation. Both produce raw UTF-8 bytes via `canonical-bytes` (no PrintWriter, no locale-encoding paths).
- **Source loading**: dev mode (in-repo) uses local `src/` via `babashka.classpath/add-classpath`; release mode (downloaded artifact) uses `babashka.deps/add-deps` to resolve cedn from Clojars on first run, cached in `~/.m2`.

Composes via Unix pipes:
```
data | to edn | ^cedn | sha256sum            # canonical-byte content hash
data | to edn | ^cedn | from edn             # canonical round-trip via Nushell
cat config.edn | cedn                         # normalize whitespace
^uuidv7 gen --format edn | cedn | sha256sum  # cross-tool composition
```

The CLI versions 1-for-1 with the library: `cedn` v1.3.1 ↔ library `com.github.franks42/cedn 1.3.1`.

### v1.2.0 Changes

- **`#bytes "hex"` tagged literal**: Native byte array support (`byte[]` on JVM, `js/Uint8Array` on CLJS). Emits as `#bytes "deadbeef"` (lowercase hex). Reader `hex->bytes` in `cedn/readers` for round-tripping.
- **`cedn.core/version`**: Version string var (`"1.2.0"`).
- **Type ordering extended**: `nil < bool < num < str < kw < sym < list < vec < set < map < #bytes < #inst < #uuid`. Tagged types sub-ordered by tag-kind (alphabetical: `:bytes` < `:inst` < `:uuid`), then by value within same kind.
- **Schema validation**: Byte arrays accepted as valid CEDN-P values.

### Distribution

| Platform | Mechanism | Test |
|---|---|---|
| Clojure (JVM) | Maven JAR via `deps.edn` | `bb test:jar` |
| Babashka | Maven JAR via `bb.edn` | `bb test:jar` |
| nbb | Git dep via `nbb.edn` | `bb test:nbb-dep` |
| shadow-cljs | Source (classpath) | `bb test:cljs` |
| Scittle (browser) | CDN script tag via jsdelivr | `bb test:scittle-cdn` |

Maven coordinates: `com.github.franks42/cedn {:mvn/version "1.5.2"}`
Build: `build.clj` (tools.build + deps-deploy) — `bb jar`, `bb install`, `clojure -T:build deploy`

| Module | Status | Description |
|--------|--------|-------------|
| `cedn.error` | Done | 7 error constructors (`unsupported-type!`, `invalid-number!`, `out-of-range!`, `duplicate-key!`, `duplicate-element!`, `invalid-unicode!`, `invalid-tag-form!`) |
| `cedn.number` | Done | Pure Clojure `ecma-reformat` post-processes `Double/toString` into ECMAScript format. Single `:clj` branch for JVM+bb. JCS is test-only cross-validation oracle. |
| `cedn.order` | Done | `type-priority` + `rank` comparator implementing §5 total ordering. `compare-strings` uses `.codePointAt` loop on bb (`:bb` reader conditional). `compare-bytes` (lexicographic unsigned), `compare-tagged` (tag-kind dispatch: bytes < inst < uuid). |
| `cedn.emit` | Done | Core `emit`/`emit-str` with type dispatch, string escaping (§3.5), `#inst` (9 fractional digits), `#uuid` (lowercase hex via `uuid?`), `#bytes` (lowercase hex via `format-bytes`), set/map sorting + duplicate detection. CLJS: `.charCodeAt` for string chars; negative zero emits as `"0"` (JS -0.0 === 0). |
| `cedn.schema` | Done | Hand-written predicates for CEDN-P type contracts including `bytes-value?`, `schema-for`/`valid?`/`explain` |
| `cedn.core` | Done | Public API: `version`, `canonical-bytes`, `canonical-str`, `valid?`, `explain`, `assert!`, `inspect` (SHA-256), `canonical?`, `rank`, `readers` (incl. `#bytes` via `hex->bytes`) |
| `cedn.gen` | Done | test.check generators for CEDN-P values |
| Property tests | Done | 4 properties × 200 iterations: idempotency, valid EDN, determinism, str/bytes agreement |
| Cross-platform bytes | Done | 40 values × 2 checks (canonical-str + bytes hex): proves all 5 platforms produce identical output for the same inputs. Compliance test vectors stored in `cedn-p-compliance-vectors.edn` (IETF RFC-style). |

**Test results (tests / assertions, 2026-09-22): JVM 145 / 21,825, bb 112 / 1,726, nbb 106 / 633, shadow-cljs 122 / 661, Scittle 69 / 69, Scittle-CDN 28 / 28, CLI 22 / 49 — 0 failures on all platforms.**
**Lint: 0 clj-kondo errors/warnings, cljfmt clean — on every Clojure file (see `bb lint` / `bb fmt`).**

Design decisions and project state live in this file and the CHANGELOG.

## Reference Documents

| File | What it is |
|------|-----------|
| `docs/cedn-spec.md` | The formal specification.  §3 (CEDN-P types) and §5 (ordering) are the critical sections. |
| `docs/cedn-api-design.cljc` | Original API design sketch (historical).  Where it differs from the shipped API — e.g. it still describes `:profile :cedn-r` as accepted — `src/cedn/` and this file are authoritative. |
| `docs/cedn-p-schema.cljc` | Original Malli schema design (historical reference). |
| `docs/kex-sources.md` | Links to reference implementations, specs, and libraries. |

## Design Decisions (Open Issues Resolved)

1. **`#inst` fractional digits → 9 (nanoseconds).**
   Always emit exactly 9 fractional digits, zero-padded.
   `java.util.Date` (ms) → 3 digits + 6 zeros.
   `java.time.Instant` (ns) → all 9 digits.

2. **Tagged literals → `#inst`, `#uuid`, and `#bytes` only.**
   CEDN-P does NOT support arbitrary tagged literals.  `#bytes` added in v1.2.0
   for byte array support (SHA-256 hashes, Ed25519 signatures, etc.).

3. **CEDN-R characters → excluded.**

4. **String comparison ordering → Unicode codepoint order.**
   Equivalent to UTF-8 byte order.  Platform-neutral: does not depend on
   JVM/JS UTF-16 internals.  Matches the CEDN wire format (UTF-8).
   Identical to UTF-16 code unit order for BMP-only strings; diverges
   only for astral-plane characters (U+10000+).

5. **`readers` map for canonical round-trips.**
   `cedn/readers` maps `#inst` → `cedn.reader/parse-inst` and `#bytes` →
   `cedn.reader/hex->bytes`; on the JVM also `#uuid` → `UUID/fromString`
   (CLJS's built-in `#uuid` reader already produces `cljs.core/UUID`).
   The default EDN reader produces `java.util.Date` (ms precision), losing sub-ms digits
   from the 9-fractional-digit canonical `#inst` form; `parse-inst` returns a
   `java.time.Instant` and preserves nanoseconds (`js/Date`, ms, on CLJS).
   Both readers are strict and share one grammar across platforms — see
   decision 12.  `canonical?` uses `readers` internally on all platforms.

6. **CEDN-R is not implemented, and is now rejected rather than ignored.**
   `:cedn-r` (spec §4) is a real extension — BigInt, BigDecimal, ratios,
   characters, plus their ordering — not a flag flip, and CEDN-P covers
   the use cases.  Until v1.4.0 the emit path accepted any `:profile`
   and ignored it, so asking for `:cedn-r` silently produced CEDN-P
   bytes (spec §8.6, profile confusion).  Every entry point now calls
   `schema/schema-for`: `:cedn-r` throws `:cedn/unsupported-profile`,
   anything else `:cedn/unknown-profile`.  `inspect` reports it in
   `:errors` instead of throwing, per its contract.

   **KEX/Biscuit policies → CEDN-P only (no CEDN-R).**
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

### Injectivity & determinism hardening (released in v1.4.0)

A review found cases where the same logical value produced different
bytes, or different values produced the same bytes — both are
signature-bypass class bugs (spec §8.1, §8.7).  Decisions 7–11 close
them; spec text updated accordingly (see spec Appendix D).

7. **`#inst` ordering is chronological, never `toString`.**
   `compare-tagged` used `(compare (str a) (str b))`; for `Date`/`js/Date`
   that is `"Thu Jan 01 00:00:00 EST 1970"` — ordered by weekday name, in
   the machine's default timezone.  The same set of dates canonicalized
   differently under `TZ=UTC` and `TZ=America/New_York`.  Now: JVM
   normalizes `Date`/`Instant` to `Instant` and compares (seconds, nanos);
   CLJS compares `.getTime`.  `#uuid` compares the lowercase string (CLJS
   `UUID.` keeps input case; `java.util.UUID.compareTo` is signed — both
   wrong).  Spec §5.3.10.

8. **Numeric ordering is exact.**
   `compare-numbers` widened to `double`, so distinct longs above 2^53
   ranked equal and their order followed input iteration order
   (`{9007199254740993 1 9007199254740992 2}` emitted in insertion order).
   Now: long–long via `compare` on longs; int–double via exact
   `BigDecimal` (`BigDecimal/valueOf long` / `(BigDecimal. double)` — not
   `bigdec`, whose shortest-repr conversion is inexact for 2^62-sized
   doubles).  CLJS unchanged (all numbers are doubles).  Spec §5.3.3.

9. **Duplicates = identical canonical text, not `=`.**
   `emit-set`/`emit-map` checked adjacent `=`.  A `Date` and an `Instant`
   for the same moment, two byte arrays with the same content, a record
   and a map with the same entries, or CLJS UUIDs differing in case are
   not `=` but serialize identically — output contained a duplicate,
   which no EDN reader accepts.  Now `sort-canonical` emits each
   element/key once to its own string, sorts by rank, rejects adjacent
   identical strings, and reuses those strings for output.  Rank ties
   are broken by canonical text, so output can never depend on input
   order even if a future rank bug made two distinct forms tie.
   Spec §3.10 rule 5, §3.11 rule 6.

10. **Unpaired surrogates rejected (§3.5.4 was specified, not
    implemented).**  `err/invalid-unicode!` existed but was never
    called.  On the JVM `"\uD800"` encoded to `?` — the same bytes as
    `"?"`; `TextEncoder` gives U+FFFD instead (cross-platform divergence
    too).  Now checked in `emit-string` and `schema`.

11. **Keyword/symbol name validation — new `:cedn/invalid-name` error.**
    Components are emitted verbatim (spec §3.6 rule 4), so
    `[(symbol "nil")]` ≡ `[nil]`, `[(keyword "a b")]` ≡ `[:a 'b]`,
    `(keyword "a/b" "c")` ≡ `(keyword "a" "b/c")`, `(symbol "#inst")` +
    string ≡ an `#inst`.  New spec §3.6.1 rules, implemented in
    `cedn.token`.  Rules were checked against what `clojure.edn` actually
    reads: `:200`, `:#a`, `clojure.core//`, `foo/nil` stay valid; `:a/1`
    and `a/1` are rejected (the reader rejects them too).  Unqualified
    keyword names are exempt from the leading-digit rules because
    keywordized numeric keys (HTTP status codes) are common.
    `valid?`/`explain` apply the same rules, so they agree with emit.

    Tests: per-rule unit tests plus three adversarial properties in
    `property_test.cljc` — hostile names must read back as themselves
    (proves no name collisions), arbitrary UTF-16 strings must round-trip
    or be rejected (oracle: UTF-8 encode/decode, independent of
    `cedn.token`), and a map built from rank-tie-prone keys (2^53
    neighbours, Date/Instant, byte arrays) must give the same result in
    either insertion order.  All of these fail against v1.3.1.

    The same review also found `#inst` years outside 0000–9999 and a
    lenient `#bytes` reader; both are closed by decision 12.

12. **Readers are strict and platform-uniform; `#inst` years 0000–9999.**
    Three reader/emit edge cases from the review:
    - `#inst` outside 0000–9999 emitted invalid RFC 3339 (`10000-01-01`,
      `-001-01-01` on JVM vs. different padding on CLJS).  Now an
      `out-of-range` error on both platforms (spec §3.12 rule 4).
    - The `#bytes` reader silently truncated: `#bytes "abc"` read as one
      byte, and non-hex threw a raw `NumberFormatException`.  Now requires
      an even-length hex string (upper or lower case) and throws
      `:cedn/invalid-tag-form` with a reason.
    - The JVM `#inst` reader (`Instant/parse`) rejected `#inst
      "2020-01-01"`, which is valid EDN, so the CLI refused input other
      tools produce.  CLJS was worse: `js/Date.` reads
      `"2020-01-01T10:20"` as *local* time where EDN means UTC, so the
      two platforms read the same document differently.

    New `cedn.reader` ns parses the whole EDN timestamp grammar with
    shared `.cljc` code (regex + range validation, leap years, offsets),
    then constructs `Instant` (ns precision) or `js/Date` (ms).  Leap
    seconds are rejected: java.time refuses them and JS rolls silently
    into the next minute, so neither can represent one.

13. **JVM test suite actually runs; CI on push/PR.**
    `test/jar_smoke_test.clj` called `(run-tests)` + `System/exit` at
    load time.  The test runner requires every discovered namespace
    before running any, so loading it exited the JVM — with status 0 —
    before the suite ran: `bb test:jvm` only ever ran the 7 smoke tests
    and could never fail on library bugs.  Fixed by moving that into
    `-main` (how `bb test:jar` invokes it via `-m`) and restricting the
    runner to `cedn.*` namespaces (`deps.edn` `:test` alias).  New
    `.github/workflows/ci.yml` runs JVM + bb + CLI tests, lint and fmt
    on pushes to main and PRs; `release.yml` now also gates the Clojars
    deploy on `bb test:jvm`.  CI's `js` job has since added shadow-cljs,
    nbb, the nbb dep smoke test, Scittle (headless Chromium) and a
    `dist/cedn.cljc` staleness check.  Network-dependent checks run
    weekly in `published.yml`.

## Project Structure

```
cedn/
├── deps.edn
├── bb.edn                     ← Babashka project config (test:bb, test:cli, install, release-check, etc.)
├── build.clj                  ← tools.build script (jar, install, deploy)
├── README.md                  ← Installation, usage, distribution docs
├── CHANGELOG.md               ← Keep-a-Changelog format
├── shadow-cljs.edn            ← shadow-cljs build config (CLJS :node-test)
├── package.json               ← npm deps (shadow-cljs)
├── scittle-tests.html         ← Scittle browser test page (69 tests, loads dist/cedn.cljc)
├── bin/
│   └── cedn                   ← CLI filter (single bb script wrapping cedn.core)
├── dist/
│   └── cedn.cljc              ← Concatenated CEDN source for Scittle/browser (auto-generated)
├── .github/
│   └── workflows/
│       ├── ci.yml             ← push/PR: JVM matrix (21, 25) + CLJS/nbb/Scittle job
│       ├── published.yml      ← weekly: CDN bundle (main + pinned tag), README nbb git dep
│       └── release.yml        ← v*.*.* tag → Clojars deploy + GH Release with bin/cedn asset
├── context.md                  ← this file
├── test/
│   ├── jar_smoke_test.clj     ← JVM JAR dependency smoke test (bb test:jar)
│   ├── nbb_smoke_test.cljs    ← nbb git dependency smoke test (bb test:nbb-dep)
│   ├── run-scittle.mjs        ← Playwright test runner (local, 69 tests)
│   ├── run-scittle-cdn.mjs    ← Playwright test runner (jsdelivr CDN at a git ref, 28 smoke tests)
│   └── scittle-cdn-test.html  ← CDN smoke test page
├── .clj-kondo/config.edn      ← kondo config (defspec lint-as)
├── docs/
│   ├── cedn-spec.md            ← formal specification
│   ├── cedn-api-design.cljc    ← API design document
│   ├── cedn-p-schema.cljc      ← Malli schema design
│   └── kex-sources.md          ← reference links
├── src/
│   └── cedn/
│       ├── core.cljc           ← public API (version + 7 functions + rank + readers)
│       ├── emit.cljc           ← per-type canonical emission
│       ├── order.cljc          ← rank comparator
│       ├── number.cljc         ← ECMAScript double formatting
│       ├── error.cljc          ← structured error constructors
│       ├── token.cljc          ← surrogate + keyword/symbol name validity
│       ├── reader.cljc         ← #inst / #bytes readers (strict, shared grammar)
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
        ├── token_test.cljc     ← string/keyword/symbol lexical rules
        ├── reader_test.cljc    ← #inst grammar + #bytes reader strictness
        ├── property_test.cljc  ← generative property tests
        ├── cli_test.clj        ← bin/cedn tests (bb test:cli)
        └── xplatform_test.cljc ← cross-platform byte comparison tests
```

## Dependencies

```clojure
;; deps.edn
{:paths ["src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.6"}}
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {io.github.cognitect-labs/test-runner
                       {:git/tag "v0.5.1"
                        :git/sha "dfb30dd6605cb6c0efc275e1df1736f6e90d4d73"
                        :git/url "https://github.com/cognitect-labs/test-runner"}
                       org.clojure/test.check {:mvn/version "1.1.1"}
                       io.github.erdtman/java-json-canonicalization {:mvn/version "1.1"}}
         ;; Only cedn.* — jar-smoke-test runs separately against the
         ;; installed JAR (bb test:jar).
         :main-opts   ["-m" "cognitect.test-runner" "-r" "cedn\\..*-test$"]
         :exec-fn     cognitect.test-runner.api/test
         :exec-args   {:patterns ["cedn\\..*-test$"]}}
  :cljs {:extra-deps {org.clojure/clojurescript {:mvn/version "1.12.145"}
                      thheller/shadow-cljs {:mvn/version "3.5.3"}}}
  :cljs-test {:extra-paths ["test"]
              :extra-deps {org.clojure/test.check {:mvn/version "1.1.1"}}}
  :pprint {:extra-deps {zprint/zprint {:mvn/version "1.3.0"}}}
  :build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.6"}
                 slipset/deps-deploy {:mvn/version "0.2.2"}}
          :ns-default build}}}
```

The only production dependency is Clojure itself.  Double formatting
uses a pure Clojure `ecma-reformat` that post-processes `Double/toString`
(JDK 19+ Schubfach algorithm) into ECMAScript Number::toString format.
**JDK 19+ is required**: `Double/toString` only became guaranteed
shortest-round-trip in JDK 19 (JDK-4511638).  On JDK 17 or older, cedn
emits extra digits for some doubles and diverges from every other
platform — CI pins Temurin 21/25 and `number-reference.edn` catches it.
`cedn.number` therefore probes `Double/toString` at load time and throws
`:cedn/unsupported-runtime` on an older JVM: a hard failure at load is
better than canonical bytes that silently do not match other platforms.
The probe tests behaviour, not `java.version`, so a runtime whose
reported version does not match its formatter is judged correctly.
The `java-json-canonicalization` library (JCS) is a **test-only**
dependency used to cross-validate `ecma-reformat` against the reference
ECMAScript implementation for 20,000+ doubles.

## Usage

```clojure
(require '[cedn.core :as cedn])

cedn/version  ;=> "1.5.2"

;; Canonicalize to bytes (for signing/hashing)
(cedn/canonical-bytes {:a 1 :b 2})
;=> #bytes[7B 3A 61 20 31 20 3A 62 20 32 7D]

;; Canonicalize to string (for debugging)
(cedn/canonical-str {:b 2 :a 1})
;=> "{:a 1 :b 2}"

;; Byte arrays emit as #bytes "hex"
(cedn/canonical-str (byte-array [0xde 0xad 0xbe 0xef]))
;=> "#bytes \"deadbeef\""

;; Validate before canonicalization
(cedn/valid? {:a 1 :b "hello"})  ;=> true
(cedn/valid? 22/7)               ;=> false
(cedn/valid? (symbol "nil"))     ;=> false  (would serialize as nil)

;; :cedn-p is the only implemented profile; anything else throws
;; rather than quietly producing CEDN-P bytes
(cedn/canonical-str {:a 1} {:profile :cedn-r})
;=> throws :cedn/unsupported-profile

;; Full diagnostics
(cedn/inspect {:a 1 :b 2})
;=> {:status :ok, :canonical "{:a 1 :b 2}", :sha-256 "...", ...}

;; Check if a string is already canonical
(cedn/canonical? "{:a 1 :b 2}")  ;=> true
(cedn/canonical? "{:b 2 :a 1}")  ;=> false

;; Round-trip with precision-preserving readers (incl. #bytes)
(require '[clojure.edn :as edn])
(let [s (cedn/canonical-str (byte-array [1 2 3]))]
  (edn/read-string {:readers cedn/readers} s))
;=> #bytes[01 02 03]

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
bb test:jvm          # JVM (cognitect test-runner)
bb test:bb           # Babashka
bb test:nbb          # nbb (Node.js)
bb test:cljs         # shadow-cljs
bb test:scittle      # Scittle (headless Chromium via Playwright)
bb test:scittle-cdn  # Scittle loading from jsdelivr CDN (optional ref, default main)
bb test:jar          # JVM smoke test against installed Maven JAR
bb test:nbb-dep      # nbb smoke test via :local/root dep (nbb.edn pattern)
bb test:nbb-git      # nbb smoke test via the README's git coordinates (network)
bb test:published    # README's pinned CDN tag + test:nbb-git (network)

# Pretty-print EDN without decoding (preserves canonical token forms)
bb pprint file.edn          # from file
bb pprint '{:b 2 :a 1}'     # from string argument
echo '{:a 1}' | bb pprint   # from stdin

# Lint & format
bb lint         # clj-kondo
bb fmt          # cljfmt check
bb fmt:fix      # cljfmt fix
bb gen:xref       # print cross-platform hex reference data
bb gen:compliance # verify all platforms agree, confirm golden file

# Run everything (JVM + bb + nbb + cljs + scittle + cli + lint + fmt)
bb test:all

# Build & distribute
bb build:scittle   # → dist/cedn.cljc (Scittle browser bundle)
bb jar             # → target/cedn.jar
bb install         # → ~/.m2/repository/com/github/franks42/cedn/<version>/

# Scittle (browser, automated via Playwright — auto-rebuilds dist/cedn.cljc)
bb test:scittle

# Prerequisite (one-time): npm install && npx playwright install chromium

# List all available tasks
bb tasks
```

### Build & distribute (long-form)

```bash
clojure -T:build jar       # → target/cedn.jar
clojure -T:build install   # → ~/.m2/repository/com/github/franks42/cedn/<version>/
clojure -T:build deploy    # → Clojars (needs CLOJARS_USERNAME/PASSWORD)
clojure -T:build clean     # remove target/
```

### Long-form commands (reference)

```bash
# 1. Tests (JVM) — all must pass
clojure -X:test

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
clj-kondo --lint src test build.clj
clj-kondo --lang clj --lint bin/cedn   # no extension: name the language

# 7. Formatting — must report all files correct
cljfmt check src test bin/cedn build.clj bb.edn deps.edn shadow-cljs.edn

# Auto-fix formatting issues:
cljfmt fix src test bin/cedn build.clj bb.edn deps.edn shadow-cljs.edn
```

## Architecture Notes

### Module dependency graph

```
cedn.core
  ├── cedn.emit
  │     ├── cedn.number
  │     ├── cedn.order
  │     ├── cedn.token
  │     └── cedn.error
  ├── cedn.reader (#inst / #bytes readers)
  │     └── cedn.error
  ├── cedn.schema
  │     └── cedn.token
  └── cedn.order (re-exported as cedn.core/rank)

cedn.gen
  └── clojure.test.check.generators
```

### Key implementation patterns

- **StringBuilder threading**: `emit` takes a `StringBuilder` (JVM) or
  `goog.string.StringBuffer` (CLJS) as first arg, all emit calls append to it.
- **Closed type dispatch**: `cond` chain in `emit`, NOT protocols/multimethods.
  Order matters: nil → boolean → int → double → string → keyword → symbol →
  seq → vector → set → map → #inst → #uuid → #bytes → error.
- **Duplicate detection**: Each set element / map key is emitted to its own
  string once; after sorting, adjacent identical strings are duplicates
  (decision 9). The strings are reused for output. O(n) after O(n log n) sort.
- **Cross-platform .cljc**: All files are `.cljc` with reader conditionals for
  JVM/CLJS differences (StringBuilder vs StringBuffer, format-double, #inst/#uuid).

## Cross-Platform Notes

### Babashka (bb)
Done. Pure Clojure `ecma-reformat` serves both JVM and bb.
`compare-strings` uses `.codePointAt` loop on bb (`:bb` reader conditional).
Full test suite passes (counts under Current Status).
Cross-platform reference test verifies bb output matches JVM for 1,051 doubles.

### nbb (Node.js Babashka)
Done. Exercises `:cljs` reader conditional branches on the JS runtime via SCI.
Full test suite passes (counts under Current Status).

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
Full test suite passes (counts under Current Status).

Additional CLJS fixes for shadow-cljs:
- `gen/large-integer*` range limited to `Number.MAX_SAFE_INTEGER` (2^53-1)
- `22/7` ratio guarded with `#?(:clj ...)` (not a valid CLJS constant)
- Property tests 3 & 4 made cross-platform (vec comparison, TextDecoder)

### Scittle (browser)
Done. Single concatenated bundle `dist/cedn.cljc` loaded via
`<script type="application/x-scittle">` with Scittle v0.8.33 CDN.
69 tests covering all modules pass in headless Chromium (Playwright).

Scittle-specific fixes (SCI symbols not available):
- `cljs.core/UUID` → `uuid?` (cross-platform predicate, works everywhere)
- `ExceptionInfo` → `(or (ex-data e) ...)` idiom (avoids bare symbol)
- `uuid` macro → removed from CLJS readers (built-in EDN reader handles it)

Build single-file bundle: `bb build:scittle` → `dist/cedn.cljc`.
Automated test: `bb test:scittle` (rebuilds + headless Chromium via Playwright).
CDN smoke test: `bb test:scittle-cdn` (loads from jsdelivr, 28 tests).

Browser usage (CDN):
```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.8.33/dist/scittle.js"></script>
<script type="application/x-scittle"
        src="https://cdn.jsdelivr.net/gh/franks42/canonical-edn@v1.5.2/dist/cedn.cljc"></script>
```

## What's NOT Built Yet

- **CEDN-R profile**: BigInt, BigDecimal, ratios, characters.  Deprioritized
  indefinitely — KEX/Biscuit policies require only CEDN-P types.  Since v1.5.0
  `:cedn-r` is actively rejected (`:cedn/unsupported-profile`) rather than
  silently treated as CEDN-P; see decision 6.
- **Kex integration**: Separate concern. Kex depends on CEDN, not vice versa.
- **Web Crypto SHA-256**: Browser `SubtleCrypto.digest` is async; `inspect`
  returns nil for SHA-256 on CLJS. Could wrap with async support later.
