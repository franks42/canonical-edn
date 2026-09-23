# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(Active dev cycle. Bump the `version` constants in `bin/cedn`,
`src/cedn/core.cljc` and `build.clj` — they must agree with the tag, and
`release.yml` refuses to release otherwise — before tagging the next
release.)

### Changed

- README pins the Scittle CDN bundle to the release tag (`@v1.5.1`)
  instead of `@main`, which followed unreleased development.
- Spec header dates refreshed (September 2026, expires March 2027).

### Added

- `bb test:scittle-cdn [ref]` takes a git ref (default `main`); for a
  release tag it also checks that the bundle reports that version.
- `bb test:nbb-git` resolves the README's nbb.edn git coordinates with an
  empty gitlibs cache. `bb test:nbb-dep` uses `:local/root`, so a wrong
  tag or sha in the README was never tested.
- `bb test:published` runs both against what the README tells users to
  load, and `.github/workflows/published.yml` runs it weekly.

### Fixed

- The Playwright runners passed `{timeout}` as `waitForFunction`'s page
  argument, so the 60 s timeout was ignored and Playwright's 30 s default
  applied.

## [1.5.1] — 2026-09-22 — Documentation

No library code changes: `src/` is identical to 1.5.0 apart from the
version constant. Documentation, tests and test data only.

### Changed

- The compliance vector now contains **every** CEDN-P type: `#bytes` and
  the empty collections were missing, though its docstring claimed
  otherwise (`#bytes` shipped in 1.2.0). The golden file
  `test/cedn/cedn-p-compliance-vectors.edn` is regenerated accordingly,
  `gen:compliance` writes byte arrays as `#bytes "hex"`, and the file is
  read with `cedn/readers`. README shows this value and its canonical
  form as a worked example.
- README leads with idempotence: many equivalent EDN spellings collapse
  to one canonical form, and since that form is itself ordinary EDN,
  canonicalizing it again returns it unchanged (`cedn ∘ cedn = cedn`).
- Tests for the fixed-point property (spec §1.2.3): canonicalizing
  canonical output is a no-op, checked over the all-types vector and
  through the CLI (`cedn | cedn | cedn`).

## [1.5.0] — 2026-09-22 — Profile enforcement, strict `#uuid`, CLI I/O

**Breaking:** `:profile :cedn-r` and unknown profiles now throw instead
of silently producing CEDN-P bytes; the CLI's `-o` is no longer an alias
for `--objects`; and the `#uuid` reader requires canonical 8-4-4-4-12
form.

### Fixed

- **Profiles are no longer accepted and ignored.** `canonical-str`,
  `canonical-bytes`, `valid?`, `explain`, `assert!` and `canonical?`
  took any `:profile` value and produced CEDN-P output, so a caller who
  asked for `:cedn-r` silently got portable-profile bytes — the profile
  confusion the spec warns about (§8.6). `:cedn-r` now throws
  `:cedn/unsupported-profile` ("not implemented — use :cedn-p") and
  anything else throws `:cedn/unknown-profile`. `inspect` keeps its
  never-throws contract and reports the error in `:errors`.

### Fixed (continued)

- The `#uuid` reader accepted sloppy input: `java.util.UUID/fromString`
  reads `#uuid "1-2-3-4-5"` as a padded UUID and `cljs.core/uuid` accepts
  any string at all, so the value read differed from the text. Both
  platforms now require canonical 8-4-4-4-12 hex (uppercase accepted,
  lowercased on read) and throw `:cedn/invalid-tag-form` otherwise.
- `bin/cedn` treated every `IOException` as a closed downstream pipe and
  exited 0, so a failed write to `--output` looked like success. Only a
  broken pipe is silent now; other I/O errors print and exit 1, as
  `--help` always claimed.
- `bb gen:compliance` printed "Writing golden file…" and wrote nothing.
  It now writes `test/cedn/cedn-p-compliance-vectors.edn`, ordering map
  and set contents by rank and escaping control characters so the file is
  stable and contains no invisible bytes, then re-reads and re-verifies
  it. Byte-identical output on repeated runs.

### Changed

- **CLI:** `-o` is no longer an alias for `--objects`; write `--objects`
  in full. It is not reassigned to `--output` — that would silently write
  to a file named like the next argument — so `-o` now fails with a
  usage error (exit 2) explaining the change.
- `cedn.gen` generates values that actually exercise the rules: strings
  with escapes, control characters, non-ASCII and astral-plane pairs;
  namespaced keywords and symbols; and nanosecond-precision `Instant`s
  on the JVM. New `cedn.gen/gen-bytes`; byte arrays stay out of
  `gen-cedn-p` because two arrays with the same content are not `=` and
  would land in one set as a canonical duplicate.
- `cedn.gen/gen-uuid` draws from a new fixed `cedn.gen/uuid-pool` instead
  of calling `randomUUID`. A generator that is not a pure function of the
  seed cannot be replayed from a reported seed and cannot shrink, so a
  failing case was unreproducible. The pool leads with the nil, max and
  signed-64-bit-boundary UUIDs, so shrinking lands on an edge case. Tests
  elsewhere use fixed UUID literals.
- `docs/cedn-spec.md` specifies `#bytes` (new §3.14), which had shipped
  in 1.2.0 without ever being written down: lowercase hex, two digits
  per octet, no separators, `#bytes ""` for empty, and readers must
  reject odd-length or non-hex payloads. Later subsections renumbered
  (general policy 3.14→3.15, metadata 3.15→3.16, unsupported types
  3.16→3.17).
- CI now also runs ClojureScript (shadow-cljs), nbb, the nbb
  git-dependency path and the Scittle browser tests, and both CI and the
  release workflow fail if `dist/cedn.cljc` is stale relative to `src/`.

(Active dev cycle. Bump the `version` constants in `bin/cedn`,
`src/cedn/core.cljc` and `build.clj` — they must agree with the tag, and
`release.yml` refuses to release otherwise — before tagging the next
release.)

## [1.4.0] — 2026-09-22 — Determinism, injectivity and strict readers

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
- `bb test:jar` smoke-tested a hard-coded `1.2.0` from `~/.m2` instead of
  the JAR it had just installed; it now reads the version from
  `build.clj`.

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
