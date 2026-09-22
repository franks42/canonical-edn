# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(Active dev cycle. Drop the `-SNAPSHOT` suffix on `bin/cedn`'s `version`
constant — and bump the matching constants in `src/cedn/core.cljc` and
`build.clj` — before tagging the next release.)

Canonicalization fixes for determinism and injectivity. **Output
changes** for inputs that previously hit these bugs (sets/maps of
`#inst`, integers above 2^53, integers vs. doubles near 2^53); inputs
that are now rejected previously produced colliding or invalid output.
See `docs/cedn-spec.md` Appendix D and `context.md` decisions 7–11.

### Fixed

- `#inst` values are ordered chronologically. Previously they were
  ordered by `Date.toString()` — weekday names in the machine's default
  timezone — so the same set of dates canonicalized differently on
  different machines. `#uuid` ordering now uses the canonical lowercase
  string on CLJS too.
- Numbers are compared exactly. Distinct longs above 2^53 no longer rank
  equal (which made their order follow input iteration order), and
  integer-vs-double comparison is exact on the JVM.
- Duplicate set elements / map keys are detected by identical canonical
  text instead of `=`: a `Date` and an `Instant` for the same moment,
  byte arrays with the same content, or a record and an equal map now
  throw `:cedn/duplicate-element` / `:cedn/duplicate-key` instead of
  emitting an unreadable set or map.
- Strings with unpaired UTF-16 surrogates now throw
  `:cedn/invalid-unicode` (spec §3.5.4). Previously the JVM encoded them
  as `?`, colliding with `"?"`.

### Fixed (readers and `#inst` range)

- `#inst` values with a year outside 0000–9999 now throw
  `:cedn/out-of-range` instead of emitting invalid RFC 3339 such as
  `#inst "10000-01-01…"` or `#inst "-001-01-01…"` (spec §3.12 rule 4).
- The `#bytes` reader no longer truncates: `#bytes "abc"` threw away the
  trailing digit and non-hex input threw a raw `NumberFormatException`.
  It now requires an even-length hex string and throws
  `:cedn/invalid-tag-form` with a `:cedn/reason`.
- The `#inst` reader accepts the full EDN timestamp grammar (`#inst
  "2020"`, `"2020-01-01"`, `"…T10:20"`, `±HH:MM` offsets), which the
  JVM's `Instant/parse` rejected, and parses it identically on every
  platform — `js/Date.` read an offset-less timestamp as local time
  where EDN means UTC. Leap seconds are rejected rather than silently
  rolled into the next minute. This also applies to the `cedn` CLI.

### Added

- `:cedn/invalid-name` error: keywords and symbols whose names would
  serialize as a different value are rejected — e.g. `(symbol "nil")`,
  `(symbol "1")`, `(keyword "a b")`, `(keyword "a/b" "c")`. Rules are in
  new spec §3.6.1; `:200`, `clojure.core//` and `foo/nil` remain valid.
  `valid?` / `explain` apply the same checks.
- `cedn.token` and `cedn.reader` namespaces (internal), holding the
  lexical checks and the strict tagged-literal readers; `dist/cedn.cljc`
  includes both.
- Tests for each fix, plus adversarial property tests (name round-trip,
  arbitrary UTF-16 strings, map key-order independence).
- CI workflow (`.github/workflows/ci.yml`): JVM, bb and CLI tests, lint
  and format check on pushes to `main` and on pull requests. The release
  workflow now also runs the JVM suite before deploying to Clojars.

### Changed

- **cedn now refuses to load on a JVM older than JDK 19.** `cedn.number`
  probes `Double/toString` at load time and throws
  `:cedn/unsupported-runtime` if the JVM does not produce shortest
  round-trip doubles. Silently emitting bytes that no other platform
  reproduces is worse than failing to start. The check tests the actual
  behaviour rather than the reported version string.
- Documented that the JVM build requires **JDK 19 or newer**
  (`Double/toString` is only guaranteed shortest-round-trip from JDK 19,
  JDK-4511638). On JDK 17 cedn emits extra digits for some doubles and
  diverges from the JS platforms; the reference vectors in
  `test/cedn/number-reference.edn` catch it. CI pins Temurin 21 and 25.

### Fixed (test infrastructure)

- `bb test:jvm` / `clj -X:test` silently ran only `jar-smoke-test`:
  that namespace ran its tests and called `System/exit` at load time,
  ending the JVM (exit 0) before the real suite ran. It now exits only
  from `-main`, and the runner is restricted to `cedn.*` namespaces.

## [1.3.1] — 2026-05-04 — Release-workflow fix

No library code changes. v1.3.0 tag's CI run failed at the Clojars deploy step because `clojure -T:build deploy` couldn't resolve `org.clojure/clojure 1.12.0` — `tools.build`'s `create-basis` runs in a separate resolver context from the surrounding tool-mode classpath, and on a fresh CI runner ~/.m2 the artifact wasn't pre-populated.

### Fixed

- `.github/workflows/release.yml` now runs `clojure -P` (prefetch project deps) before tests and deploy. This populates `~/.m2` with the project's main `:deps` (Clojure itself plus transitive deps) so the subsequent `clojure -T:build deploy` finds them when `tools.build/create-basis` reads `deps.edn`.

### Operational note for v1.3.0

The v1.3.0 git tag exists on origin but no GitHub Release was created and no Clojars artifact was published. Effectively a dud — harmless but cosmetic. v1.3.1 supersedes it; v1.3.0 can be left in place as a historical marker or deleted from origin (`git push origin :v1.3.0`) at maintainer discretion.

## [1.3.0] — 2026-05-03 — `cedn` CLI shipped

Library API unchanged from 1.2.0. This release ships the **command-line filter** for canonical EDN, plus the surrounding test/release plumbing.

### Added

- **`bin/cedn`** — single executable bb script that reads zero or more top-level EDN forms from stdin (or `--input <file>` / `--edn <string>`) and emits each as canonical EDN to stdout (or `--output <file>`) as raw UTF-8 bytes. Streaming: parses, canonicalizes, and emits one form at a time. Default output is newline-separated with a trailing newline; `--objects` switches to single-space separator with no trailing newline. Same script for dev and release: when run from inside the repo (with `../src/cedn/core.cljc` adjacent), uses local source via `babashka.classpath/add-classpath`; when run as the GitHub Release artifact (no adjacent `src/`), uses `babashka.deps/add-deps` to resolve the pinned cedn version from Clojars on first run.
- **`bb test:cli`** task — 18 integration tests shelling out to `bin/cedn` and asserting on stdout, stderr, and exit codes.
- **`bb test:cli-release`** task — installs the library JAR to local `~/.m2`, then runs `bin/cedn` from a clean temp dir to verify the release-mode source resolution path works against an actual Maven artifact. Catches "I broke add-deps without noticing" issues that local-only testing would miss.
- **`bb release-check`** task — refuses to ship a non-stable version (`-SNAPSHOT`, `-alpha`, `-beta`, `-rc`) and verifies the version is a clean `X.Y.Z` triple. Run before `git tag`.
- **`.github/workflows/release.yml`** — fires on `v*.*.*` tag push. Verifies tag matches the version constants in `bin/cedn`, `build.clj`, and `src/cedn/core.cljc`; runs library tests, CLI tests, lint, format check, `release-check`; deploys library to Clojars; waits for Clojars indexing; smoke-tests the CLI in a clean directory (forcing `add-deps` resolution from Clojars); builds a version-suffixed asset (`cedn-vX.Y.Z`); creates the GitHub Release.

### Fixed

- Three pre-existing `bb lint` warnings cleared: missing `clojure.edn` require in `test/jar_smoke_test.clj`, missing `cljs.reader` require in `test/nbb_smoke_test.cljs`, and an unused dead-code binding in the same nbb smoke test.
- Five test files reformatted to satisfy `bb fmt` (cljfmt) — pure whitespace adjustments, no semantic changes.

### Distribution

| Artifact | Where | Coord / asset |
| --- | --- | --- |
| Library JAR | Clojars | `com.github.franks42/cedn {:mvn/version "1.3.0"}` |
| CLI script | GitHub Release on this repo | `cedn-v1.3.0` |

### Install (CLI)

```bash
curl -L https://github.com/franks42/canonical-edn/releases/download/v1.3.0/cedn-v1.3.0 -o cedn
chmod +x cedn
./cedn --version
# cedn 1.3.0
```

Requires [babashka](https://babashka.org/) (`bb` on PATH). On first run from a clean install, the script resolves cedn from Clojars (~500 ms one-time cost; cached in `~/.m2` thereafter).

## [1.2.0] — `#bytes` tagged literal

Added `#bytes "hex"` tagged literal: native byte array support (`byte[]` on JVM, `js/Uint8Array` on CLJS). Emits as `#bytes "deadbeef"` (lowercase hex). Reader `hex->bytes` in `cedn/readers` for round-tripping. Type ordering extended to include `#bytes` between `:map` and `#inst` (alphabetical sub-ordering by tag-kind). Schema validation accepts byte arrays as valid CEDN-P values. Added `cedn.core/version` var.

## [1.1.0] — Scittle browser support

Automated Scittle (browser) testing via Playwright/headless Chromium. CDN distribution via jsdelivr. `dist/cedn.cljc` published as the single-file Scittle build.

## [1.0.0] — Five-platform canonical EDN

CEDN complete on JVM, Babashka, nbb (Node.js), shadow-cljs (CLJS), and Scittle (browser). CEDN-P profile fully implemented across all platforms with byte-equivalent output. CEDN-P compliance test vectors and cross-platform byte comparison tests.

## [0.5.0] — Scittle support

Browser support via Scittle. Five-platform parity.

## [0.4.0] — shadow-cljs (full CLJS) support

Full CLJS via shadow-cljs. Four platforms parity (JVM + bb + nbb + cljs).

## [0.3.0] — nbb (Node.js) support

Node.js via nbb. CLJS emit fixes and cross-platform tests.

## [0.2.0] — JCS as test-only

Promoted ecma-reformat to JVM production path; JCS used only in tests. Cross-platform reference tests for `format-double` (1,051 vectors).

## [0.1.0] and earlier

Babashka support; pure-Clojure `format-double`; bb test harness. See git history for details.
