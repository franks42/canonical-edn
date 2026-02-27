# Review of `canonical-edn-analysis.md` (GPT)

## Executive Summary

The analysis is strong, technically serious, and very close to an implementable v1 spec. The most valuable decisions are:

- one-way normalization (not type-preserving codec)
- strict unknown-type rejection
- explicit total ordering for map/set canonicalization
- dedicated writer (`canonical-pr-str`) instead of delegating to `pr-str`

The primary remaining risks are **spec ambiguity around numeric portability**, **ordering semantics at Unicode boundaries**, and **profile scoping** (portable subset vs rich JVM subset). These are solvable with a small number of explicit rules.

---

## What Is Already Excellent

- Clear problem framing: deterministic bytes for signatures/content hashes.
- Correct rejection of ambient print state (`*print-length*`, `*print-level*`, etc.).
- Strong comparison of prior art (Puget, arrangement, hasch, JCS).
- Good “many-to-one normalization” argument for records/maps.
- Practical implementation shape (`emit-*` functions + comparator + number module).
- Explicit focus on conformance vectors and byte-level test outputs.

---

## High-Impact Gaps / Ambiguities

### 1) Define Profiles Explicitly (normative)
The document currently mixes two goals:

- **Portable cross-runtime canonicalization** (JVM/CLJS/Babashka parity)
- **Full EDN richness** (BigInt/BigDecimal/ratios/chars/tags)

Recommendation: define profiles now, as normative language:

- **CEDN-P (Portable)**: strict subset guaranteed identical on JVM+CLJS+bb
- **CEDN-R (Rich)**: allows broader EDN types, but must be marked non-portable

This removes recurring ambiguity in sections 4, 5, and 8.

### 2) Numeric Rules Need One Final Canonical Contract
Current direction is good, but two points need explicit locking:

- For doubles, choose one exponent style (`e` vs `E`, `e+23` vs `E23`) and mandate it.
- Clarify whether whole-valued doubles always serialize with `.0` (including negative zero normalization to `0.0`).

Recommendation:

- Canonical float output uses lowercase `e` when exponent form is required.
- If shortest-roundtrip result is integer-like, append `.0`.
- `-0.0` canonicalizes to `0.0`.
- `NaN`, `Infinity`, `-Infinity` are errors in all profiles.

### 3) String Ordering Must Be Precisely Stated
There is tension between “codepoint order” wording and practical platform comparison (`String.compareTo` JVM, JS semantics). These diverge for surrogate behavior and can create non-interoperable sorts.

Recommendation: specify one exact comparator for strings used inside ranking:

- compare by Unicode scalar values (if you want language-level semantic correctness), **or**
- compare by UTF-16 code units (if you want easiest cross-runtime implementation parity).

Either is valid; ambiguity is not.

### 4) Tagged Literal Policy Needs a Hard Boundary
The two-tier strategy is sensible, but must be normative:

- Tagged literal is accepted only if tag is symbol and form is canonicalizable EDN.
- Opaque host objects are always rejected.
- Built-ins (`#inst`, `#uuid`) get special canonical rendering rules.

Also add explicit statement: custom tag semantics are outside canonicalization; canonicalization only normalizes syntax and nested canonical value.

### 5) Duplicate-Equivalent Key/Element Rule Is Correct But Hard to Enforce Uniformly
The “must not contain keys/elements equal on any target runtime” rule is desirable, but not directly computable without emulating all runtimes.

Recommendation: in CEDN-P, avoid semantic cross-runtime equality checks and instead require canonicalized ordering + deterministic rendering, then reject only concrete local duplicates during map/set construction/canonicalization. Document that user data models must avoid ambiguous numeric key domains.

### 6) `#inst` Precision Policy Should Prefer Rejection Over Silent Truncation
Truncation can hide data loss in security-critical contexts.

Recommendation:

- default mode: reject sub-millisecond instants
- optional compatibility mode: truncate to milliseconds (must be opt-in and documented)

---

## Alternative Design You Should Consider

## Option A (Recommended): “Portable-First v1”
Ship a narrow, strict first version:

- types: nil, booleans, strings, keywords, symbols, vectors, lists, sets, maps, integers in signed 64-bit range, doubles, `#uuid`, `#inst`(ms UTC)
- reject BigInt/BigDecimal/ratios/chars/regex/functions/host objects
- no custom tags in v1 (or allow only if nested form is portable)

Benefits:

- fast delivery
- lower interop risk
- easiest conformance story

## Option B: “Dual Profile from Day 1”
Define CEDN-P and CEDN-R together.

- CEDN-P used for signatures across heterogeneous runtimes
- CEDN-R for JVM-heavy ecosystems

Benefits:

- fewer future breaking discussions
- clearer migration path for advanced users

Cost: more spec text and testing burden now.

---

## Suggested Spec-Level Edits (Concrete)

1. Add a dedicated section: **“Conformance Profiles (CEDN-P, CEDN-R)”**.
2. Add a normative grammar snippet for canonical numeric lexical forms.
3. Lock one string-comparison algorithm for ordering.
4. Move all “open questions” that affect determinism into v1 decisions (especially numbers, chars, BigInt/BigDecimal).
5. Add a strict/compat mode table for `#inst` precision behavior.
6. Add a canonicalization failure taxonomy (invalid-number, unsupported-type, invalid-tag-form, invalid-unicode, etc.).

---

## Test Strategy Improvements

- Add **cross-runtime differential tests**: same value fixtures canonicalized on JVM, Node CLJS, Babashka; byte outputs must match.
- Add **metamorphic tests**: reorder map/set input permutations and assert identical canonical bytes.
- Add **negative corpus**: unsupported types and malformed tagged values must fail with stable error classes.
- Keep JCS-inspired number vectors, but include a smaller fixed “critical edge” corpus checked in uncompressed for quick CI.

---

## Final Recommendation

Proceed with the architecture as proposed, but lock down profile scope and numeric/string comparator rules before implementation. If you do only one thing before coding, make the v1 profile and numeric lexical contract fully normative; that decision will remove most future interop and signature-verification failure risk.
