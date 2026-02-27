```
Canonical EDN v1                                       
Internet-Draft                                         February 2026
Intended status: Informational
Expires: August 2026
```

# Canonical EDN (CEDN) v1: A Deterministic Serialization for EDN Values

## Abstract

This document specifies Canonical EDN (CEDN), a deterministic
serialization of Extensible Data Notation (EDN) values to UTF-8
byte sequences.  CEDN defines rules such that a given EDN value
always produces exactly one canonical byte sequence, enabling
cryptographic signing, verification, and content-addressing across
heterogeneous Clojure runtime environments.

Two conformance profiles are defined: CEDN-P (Portable), which
guarantees identical output across JVM, ClojureScript, Babashka,
and Node.js; and CEDN-R (Rich), which extends CEDN-P with
JVM-specific numeric types.

## Status of This Memo

This document is a draft specification for community review.  It is
not an IETF standard.  Distribution of this memo is unlimited.

## Copyright Notice

Copyright (c) 2026 the authors.  All rights reserved.  This document
is made available under the terms of the MIT License.

---

## Table of Contents

1.  [Introduction](#1-introduction)
2.  [Conventions and Definitions](#2-conventions-and-definitions)
3.  [CEDN-P: Portable Profile](#3-cedn-p-portable-profile)
4.  [CEDN-R: Rich Profile](#4-cedn-r-rich-profile)
5.  [Canonical Ordering](#5-canonical-ordering)
6.  [Byte Encoding](#6-byte-encoding)
7.  [Error Conditions](#7-error-conditions)
8.  [Security Considerations](#8-security-considerations)
9.  [References](#9-references)
10. [Appendix A: ABNF Grammar](#appendix-a-abnf-grammar)
11. [Appendix B: Number Serialization Samples](#appendix-b-number-serialization-samples)
12. [Appendix C: Conformance Test Vectors](#appendix-c-conformance-test-vectors)
13. [Authors' Addresses](#authors-addresses)

---

## 1. Introduction

### 1.1. Motivation

Cryptographic operations — signing, verification, hashing — require
deterministic serialization: the same logical value MUST always
produce the same byte sequence.  Clojure's built-in `pr-str` function
does not provide this guarantee.  Its output varies across platforms
(JVM vs. ClojureScript), is influenced by dynamic variables
(`*print-length*`, `*print-level*`, `*print-namespace-maps*`), and
differs between Clojure versions for some types.

CEDN addresses this by defining a single canonical text
representation for each supported EDN value, and a single encoding
of that text to bytes.

### 1.2. Design Principles

#### 1.2.1. Information, Not Mechanism

Clojure values carry two kinds of content: the INFORMATION they
represent, and the MECHANISM by which the runtime manages that
information.  A sorted map and a hash map containing the same keys
and values carry identical information; the ordering strategy is
mechanism.  A record and a plain map with the same fields carry
identical information; the type tag is a dispatch mechanism.
Metadata, lazy evaluation strategies, subvector backing arrays,
and the sign bit of negative zero are all mechanism — they serve
the runtime, not the data.

Canonicalization strips mechanism and preserves information.  The
canonical output represents each value in terms of EDN's primitive
data structures — nil, booleans, numbers, strings, keywords,
symbols, and the four structural containers (list, vector, set,
map) — which are the irreducible carriers of information in the
EDN model.  Nothing platform-specific, behavioral, or
implementation-contingent survives canonicalization.

This is precisely what cryptographic operations require.  A
signature attests to INFORMATION — "this authority grants read
access to file1" — not to the mechanism by which that information
was represented in a particular process on a particular runtime.
Two systems that hold the same information must produce the same
signature, regardless of whether one used a sorted map and the
other a hash map, or whether one ran on the JVM and the other in
a browser.

#### 1.2.2. One-Way Normalization

CEDN is a ONE-WAY NORMALIZATION FUNCTION.  Many input
representations (different map orderings, whitespace variants,
type representations) map to one canonical output.  The consumer
of canonical output is a cryptographic function (sign, verify,
hash), not a deserializer.

There is no `canonical-read` inverse, and none is needed.  Any
conforming EDN reader can read canonical output back into the
host language's native data structures.  What the reader produces
are values in EDN's primitive data model — exactly the information
content, with all mechanism already removed by canonicalization.

#### 1.2.3. Idempotent Projection

The composition `read ∘ canonicalize` is an idempotent projection.
Applying it once maps an arbitrary Clojure value to a fixed point
in the canonical value subset; applying it again produces the same
fixed point:

```
  v1    →  canonical(v1)  →  read(canonical(v1))  =  v2
  v2    →  canonical(v2)  →  read(canonical(v2))  =  v3
  where:  canonical(v1)  =  canonical(v2)     (byte-identical)
          v2  =  v3                           (value-equal)
```

The first pass burns off mechanism (sorted maps become hash maps,
records become plain maps, metadata disappears, negative zero
becomes positive zero).  What remains is a value composed entirely
of EDN primitives — the information in its most basic structural
form.  All subsequent passes are no-ops.

This property falls out naturally from the design and does not
require special implementation effort.  It is, however, a useful
correctness invariant for testing (see Appendix C.1).

#### 1.2.4. Analogies

The relationship between input and output is analogous to:

```
  Unicode NFC:         many byte sequences  →  one normalized form
  Database collation:  many strings         →  one sort key
  CEDN:                many EDN values      →  one byte sequence
```

### 1.3. Scope

This specification defines:

-  Two conformance profiles (CEDN-P and CEDN-R)
-  Canonical rendering rules for each supported type
-  A total ordering over all canonical values
-  Error conditions and their classification
-  The encoding of canonical text to bytes

This specification does NOT define:

-  Wire formats for tokens, messages, or signatures
-  Key management, algorithm selection, or signing protocols
-  Application-level interpretation of canonical values
-  A deserialization format

### 1.4. Relationship to EDN

Canonical EDN is a strict subset of EDN [EDN].  Every canonical EDN
text is valid EDN that can be read by any conforming EDN reader.
Not every EDN text is canonical.

### 1.5. Versioning

Every canonical output is associated with a profile and version
identifier.  This identifier is NOT embedded in the canonical text
of the value itself — canonicalization is a pure function from value
to bytes, independent of version metadata.

However, the version identifier MUST be cryptographically bound to
any signature that covers canonical bytes.  Concretely: signing
protocols that use CEDN MUST include the profile and version
identifier in the content that is signed, such that an attacker
cannot substitute a different profile or version without
invalidating the signature.

#### 1.5.1. Version Key and Values

This specification defines a REQUIRED key-value pair for version
binding:

```
  Key:     :cedn/version   (namespaced keyword)
  Values:  "cedn-p.v1"     (Portable profile, version 1)
           "cedn-r.v1"     (Rich profile, version 1)
```

The value is a string encoding both the profile (`p` or `r`) and
the version number, separated by a dot.  Future versions of this
specification will define additional values (e.g., `"cedn-p.v2"`).

Signing protocols MUST use exactly this key and one of the defined
values.  Implementations MUST reject version strings that are not
recognized.

#### 1.5.2. Binding Requirement

Signing protocols that use CEDN MUST satisfy the following
property:

```
  REQUIRED PROPERTY:  Given a signature S over canonical bytes B
  produced under profile P, it MUST be impossible to verify S
  under any profile P' ≠ P without access to the signer's
  private key.
```

Three example mechanisms that satisfy this property:

1.  **Payload field** (RECOMMENDED): Include the version key-value
    pair in the signed data structure, then canonicalize the entire
    structure:

    ```clojure
    {:cedn/version "cedn-p.v1"
     :authority     [[:right :resource "file1" :read]]
     :created       #inst "2026-02-26T12:00:00.000000000Z"}
    ```

2.  **Prefix concatenation**: Derive the signing input as the
    concatenation of the version string bytes and the canonical
    value bytes:
    `sign(UTF-8("cedn-p.v1") || canonical-bytes(value))`.

3.  **Protected header**: Include the version key-value pair in a
    signed header alongside the payload (as in JWS [RFC7515]
    protected headers).

The canonicalizer itself does NOT inject the version key-value
pair.  It is the responsibility of the signing protocol to include
`:cedn/version` in the data structure before canonicalization.

Signing protocols that fail to bind the version identifier to the
signature are vulnerable to profile confusion attacks (Section 8.6).

Verifiers MUST know which profile and version produced a given
signature in order to verify it.  A v2 implementation MAY support
verification of both v1 and v2 signatures.

---

## 2. Conventions and Definitions

### 2.1. Notation

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT",
"SHOULD", "SHOULD NOT", "RECOMMENDED", "NOT RECOMMENDED", "MAY",
and "OPTIONAL" in this document are to be interpreted as described
in BCP 14 [RFC2119] [RFC8174] when, and only when, they appear in
all capitals, as shown here.

### 2.2. ABNF

The formal grammar in this document uses the Augmented Backus-Naur
Form (ABNF) notation of [RFC5234], including the core rules defined
in Appendix B of that document.

### 2.3. Terminology

**Canonical text**: The deterministic string representation of an
EDN value as defined by this specification.

**Canonical bytes**: The UTF-8 encoding of the canonical text,
which is the input to cryptographic operations.

**Rank**: The total ordering defined in Section 5, used to sort
map keys and set elements.

**Profile**: A named set of type support rules.  CEDN-P (Portable)
or CEDN-R (Rich).

**Normalization**: The process of converting an EDN value to its
canonical text.  This is a many-to-one mapping.

---

## 3. CEDN-P: Portable Profile

CEDN-P defines the canonical subset guaranteed to produce identical
output on all target platforms: JVM Clojure, ClojureScript (browser
and Node.js), Babashka, and Scittle.

CEDN-P is the REQUIRED profile for cross-runtime signature
verification.

### 3.1. Nil

**Canonical form**: The three-character string `nil`.

```
  canonical-nil = %x6E.69.6C  ; "nil"
```

### 3.2. Booleans

**Canonical form**: The strings `true` or `false`.

```
  canonical-boolean = %x74.72.75.65       ; "true"
                    / %x66.61.6C.73.65    ; "false"
```

### 3.3. Integers

**Canonical form**: Decimal digits with an optional leading minus
sign.

Normative rules:

1.  The output MUST be the shortest decimal representation.
2.  Leading zeros MUST NOT appear, except for the value zero itself.
3.  A leading `+` sign MUST NOT appear.
4.  The `N` (BigInt) suffix MUST NOT appear in CEDN-P output.
5.  Negative zero does not exist for integers.

**Range**: CEDN-P integers MUST be within the 64-bit signed integer
range: -9,223,372,036,854,775,808 to 9,223,372,036,854,775,807
(inclusive).  Values outside this range MUST cause an `out-of-range`
error (Section 7).

On ClojureScript, where all numbers are IEEE 754 doubles, a value
is treated as an integer if and only if it satisfies
`(and (js/Number.isFinite x) (== x (Math/trunc x)))` and falls
within the above range.

Examples:

```
  Input       Canonical
  -----       ---------
  42          "42"
  -7          "-7"
  0           "0"
  007         "7"
  +5          "5"
  42N         ERROR (unsupported-type in CEDN-P)
```

### 3.4. Doubles (Floating-Point)

**Canonical form**: The output of the ECMAScript Number serialization
algorithm (ECMA-262 Section 7.1.12.1, "Number::toString"), with one
EDN-specific adaptation: if the output contains neither a decimal
point (`.`) nor an exponent indicator (`e`), the suffix `.0` MUST
be appended.

This produces the shortest decimal representation that round-trips
to the exact same IEEE 754 double-precision value.

Normative rules:

1.  The ECMAScript algorithm MUST be used.  Java's
    `Double.toString()` MUST NOT be used directly, as it produces
    different output (see Section 3.4.3).
2.  Exponent notation uses lowercase `e`.
3.  Positive exponents include an explicit `+` sign: `e+21`.
4.  If the shortest representation is an integer (no `.` or `e`),
    `.0` MUST be appended to preserve the EDN float/integer type
    distinction.
5.  Negative zero (`-0.0`) MUST be serialized as `0.0`.
6.  `NaN` MUST cause an `invalid-number` error (Section 7).
7.  `Infinity` and `-Infinity` MUST cause an `invalid-number` error.

Examples:

```
  IEEE 754 value           Canonical
  --------------           ---------
  0.0                      "0.0"
  -0.0                     "0.0"
  1.0                      "1.0"
  -3.14                    "-3.14"
  0.1                      "0.1"
  0.001                    "0.001"
  0.000001                 "0.000001"
  0.0000001                "1e-7"
  100000000000000000000.0  "100000000000000000000.0"
  1e+21                    "1e+21"
  4.5                      "4.5"
  9007199254740994.0       "9007199254740994.0"
  NaN                      ERROR (invalid-number)
  Infinity                 ERROR (invalid-number)
```

#### 3.4.1. Implementation on JavaScript Platforms

On JavaScript (ClojureScript, Scittle, Node.js), the ECMAScript
algorithm is the native behavior of `Number.prototype.toString()`.
No special implementation is required beyond the `.0` suffix rule
and the `-0.0` check.

```javascript
  function formatDouble(x) {
    if (x !== x || !isFinite(x)) throw new Error("invalid-number");
    if (Object.is(x, -0)) return "0.0";
    var s = String(x);
    if (s.indexOf('.') === -1 && s.indexOf('e') === -1) s += ".0";
    return s;
  }
```

#### 3.4.2. Implementation on JVM

Java's `Double.toString()` implements Java-specific formatting rules
that differ from ECMAScript.  JVM implementations MUST use an
ECMAScript-compatible formatter.  Two Apache 2.0 licensed Java
implementations are available:

-  `org.webpki.jcs.NumberToJSON.serializeNumber()` from
   [JCS-REF] (~530 LOC, Ryu-based)
-  `org.erdtman.jcs.NumberToJSON.serializeNumber()` from
   [JCS-JAVA], available on Maven Central as
   `[io.github.erdtman/java-json-canonicalization "1.1"]`
   (~6 files, Grisu3-based)

Both implement the ECMAScript Number serialization algorithm.

The JVM formatter MUST then apply the EDN-specific `.0` suffix
rule and the `-0.0` normalization.

#### 3.4.3. Differences Between Java and ECMAScript Formatting

For reference, here are values where Java `Double.toString()` and
ECMAScript `Number.prototype.toString()` produce different output:

```
  IEEE 754 value    Java                ECMAScript
  --------------    ----                ----------
  1.0               "1.0"               "1"
  10.0              "10.0"              "10"
  1.0e-4            "1.0E-4"            "0.0001"
  1.0e-7            "1.0E-7"            "1e-7"
  1.0e+20           "1.0E20"            "100000000000000000000"
  1.0e+21           "1.0E21"            "1e+21"
```

### 3.5. Strings

**Canonical form**: The string content enclosed in double quotes,
with the following escape rules applied.

#### 3.5.1. Mandatory Escapes

The following characters MUST be escaped:

```
  Character        Escape     Codepoint
  ---------        ------     ---------
  quotation mark   \"         U+0022
  reverse solidus  \\         U+005C
  newline          \n         U+000A
  carriage return  \r         U+000D
  tab              \t         U+0009
```

#### 3.5.2. Control Character Escapes

All other characters in the ranges U+0000–U+001F and U+007F that
are not covered by Section 3.5.1 MUST be escaped using the
`\uNNNN` form, where `NNNN` is exactly four lowercase hexadecimal
digits.

```
  Examples:
  U+0000  →  \u0000
  U+0008  →  \u0008   (backspace — EDN has no \b escape)
  U+000C  →  \u000c   (form feed — EDN has no \f escape)
  U+001F  →  \u001f
  U+007F  →  \u007f   (delete)
```

#### 3.5.3. All Other Characters

All Unicode characters outside the mandatory escape set (U+0020
through U+007E excluding `"` and `\`, plus U+0080 and above) MUST
be passed through as literal UTF-8.  They MUST NOT be escaped.

This means the escape `\u00e9` (for `é`) MUST NOT appear in
canonical output; the literal byte sequence `C3 A9` MUST appear
instead.

#### 3.5.4. Invalid Unicode

Strings containing unpaired surrogates (lone high surrogate U+D800–
U+DBFF or lone low surrogate U+DC00–U+DFFF) MUST cause an
`invalid-unicode` error (Section 7).

### 3.6. Keywords

**Canonical form**: A colon (`:`) followed by the keyword name, or
a colon followed by the namespace, a solidus (`/`), and the name.

Normative rules:

1.  Unqualified keywords: `:name`
2.  Qualified keywords: `:namespace/name`
3.  No whitespace between `:` and the name.
4.  The namespace and name components MUST appear exactly as stored
    in the runtime keyword object.  No normalization of casing or
    encoding is applied.

### 3.7. Symbols

**Canonical form**: The symbol name, or the namespace, a solidus
(`/`), and the name.

Normative rules:

1.  Unqualified symbols: `name`
2.  Qualified symbols: `namespace/name`
3.  The namespace and name components MUST appear exactly as stored
    in the runtime symbol object.

### 3.8. Lists

**Canonical form**: An opening parenthesis `(`, the canonical forms
of the elements separated by single spaces (U+0020), and a closing
parenthesis `)`.

Normative rules:

1.  Elements appear in their natural order (not sorted).
2.  Exactly one space (U+0020) separates adjacent elements.
3.  No space after `(` or before `)`.
4.  Empty list: `()`

All seq-like types (lazy sequences, cons cells, ranges) MUST be
rendered as lists.  The implementation MUST fully realize lazy
sequences before rendering.

Examples:

```
  (1 2 3)
  (:a "hello" nil)
  ()
```

### 3.9. Vectors

**Canonical form**: An opening square bracket `[`, the canonical
forms of the elements separated by single spaces, and a closing
square bracket `]`.

Normative rules:

1.  Elements appear in their natural order (not sorted).
2.  Exactly one space separates adjacent elements.
3.  No space after `[` or before `]`.
4.  Empty vector: `[]`

Subvector types (`clojure.lang.APersistentVector$SubVector`) MUST
be rendered identically to regular vectors.

Examples:

```
  [1 2 3]
  [:a "hello" nil]
  []
```

### 3.10. Sets

**Canonical form**: The two-character opening `#{`, the canonical
forms of the elements sorted by rank (Section 5) and separated by
single spaces, and a closing `}`.

Normative rules:

1.  Elements MUST be sorted by the rank ordering (Section 5).
2.  Exactly one space separates adjacent elements.
3.  No space after `#{` or before `}`.
4.  Empty set: `#{}`
5.  Elements that are value-equal (`=`) after normalization MUST
    cause a `duplicate-element` error (Section 7).

Examples:

```
  #{1 2 3}           ; integers sorted by value
  #{:a :b :c}        ; keywords sorted lexicographically
  #{}
```

### 3.11. Maps

**Canonical form**: An opening brace `{`, the canonical forms of
key-value pairs sorted by key rank (Section 5), with keys and
values separated by single spaces, pairs separated by single
spaces, and a closing brace `}`.

Normative rules:

1.  Entries MUST be sorted by key rank (Section 5).
2.  Within each entry: key, then one space, then value.
3.  Exactly one space separates adjacent entries (between the
    value of one entry and the key of the next).
4.  No space after `{` or before `}`.
5.  Empty map: `{}`
6.  Keys that are value-equal (`=`) after normalization MUST cause
    a `duplicate-key` error (Section 7).

All map types (hash-map, sorted-map, array-map, records) MUST
produce the same output for the same logical content.  Records are
treated as plain maps; the record type name is not preserved.

Namespace map syntax (`#:ns{:a 1}`) MUST be expanded to its full
form (`{:ns/a 1}`) before canonicalization.

Examples:

```
  {:a 1 :b 2}
  {1 "one" 2 "two"}
  {}
```

### 3.12. Tagged Literal: `#inst`

**Canonical form**: The five characters `#inst` followed by a single
space and an RFC 3339 [RFC3339] timestamp string enclosed in double
quotes.

Normative rules:

1.  Timezone MUST be UTC, indicated by the `Z` suffix.  Offsets
    such as `+00:00` or `-00:00` MUST NOT appear.
2.  Fractional seconds MUST always be exactly 9 digits
    (nanosecond precision), zero-padded as needed.
3.  The timestamp MUST be formatted from the underlying epoch
    value directly.  Implementations MUST NOT delegate to the
    platform's `toString()` or `pr-str` for the date object.

Platforms with lower precision (e.g., `java.util.Date` and
`js/Date` at millisecond resolution) MUST zero-pad the
sub-millisecond digits.  This is not information loss — the
value genuinely has zero nanoseconds.  Platforms with
nanosecond precision (e.g., `java.time.Instant`) emit all
9 significant digits.

The canonical output is write-only — it feeds into sign, verify,
and hash operations, not into a deserializer.  No platform needs
to reconstruct a date object from the canonical text.  If two
platforms hold the same logical timestamp, they produce the same
9 digits.  If they hold different-precision values (one at ms,
one at ns), those are different values and correctly produce
different bytes.

Examples:

```
  Canonical:
  #inst "2026-02-26T12:00:00.000000000Z"   ; ms-precision source
  #inst "2026-02-26T12:00:00.123000000Z"   ; ms-precision source
  #inst "2026-02-26T12:00:00.123456789Z"   ; ns-precision source
  #inst "1970-01-01T00:00:00.000000000Z"   ; epoch

  NOT canonical:
  #inst "2026-02-26T12:00:00Z"             ; missing fractional digits
  #inst "2026-02-26T12:00:00.123Z"         ; only 3 digits, need 9
  #inst "2026-02-26T12:00:00.000+00:00"    ; offset instead of Z
```

### 3.13. Tagged Literal: `#uuid`

**Canonical form**: The five characters `#uuid` followed by a single
space and a UUID string enclosed in double quotes, formatted as
lowercase hexadecimal in the standard 8-4-4-4-12 grouping.

Normative rules:

1.  All hexadecimal digits MUST be lowercase.
2.  The standard 8-4-4-4-12 grouping with hyphens MUST be used.
3.  The UUID MUST be formatted from the underlying 128-bit value
    directly.  Implementations MUST NOT delegate to the platform's
    `toString()`.

```
  Canonical:
  #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"

  NOT canonical:
  #uuid "F81D4FAE-7DEC-11D0-A765-00A0C91E6BF6"   ; uppercase
```

### 3.14. Tagged Literals: General Policy

CEDN-P v1 restricts tagged literals to `#inst` and `#uuid` only.
Custom tagged literals are not permitted in the portable profile.

> **Rationale:**  Limiting to the two built-in tags keeps the
> portable profile simple, self-contained, and interoperable
> across all Clojure runtimes without requiring tag registries.

If a future profile permits custom tags, the following rules apply:

A general tagged literal `#tag value` is canonical if and only if:

1.  `tag` is a valid EDN symbol (optionally namespace-qualified).
2.  `value` is itself canonical under the active profile.
3.  The rendering is: `#`, the canonical symbol form, one space,
    the canonical value form.

The tag's semantic meaning is OPAQUE to canonicalization.  CEDN
normalizes syntax and nested value structure only; it does not
interpret tag-specific semantics.

### 3.15. Metadata

Metadata MUST be silently stripped during canonicalization.  It MUST
NOT appear in canonical output.  This is consistent with metadata's
intended role as orthogonal to value equality in Clojure.

### 3.16. Unsupported Types

The following types MUST cause an `unsupported-type` error
(Section 7) under CEDN-P:

-  BigInt (`42N`)
-  BigDecimal (`3.14M`)
-  Ratios (`22/7`)
-  Character literals (`\a`, `\newline`)
-  Records and deftypes (rendered as maps, see Section 3.11)
-  Regex patterns (`#"..."`)
-  Functions, vars, atoms, refs, agents
-  Host objects (Java collections, JS arrays, etc.)
-  Any type not enumerated in Sections 3.1–3.14

> **Clarification:** Records are normalized to their map
> representation per Section 3.11, losing type identity.  This is
> intentional normalization, not an error.  The `unsupported-type`
> error applies to types that have NO map representation, such as
> functions or regex patterns.

---

## 4. CEDN-R: Rich Profile

CEDN-R extends CEDN-P with JVM-specific numeric types.  It is
intended for environments where ALL participants run on the JVM.

CEDN-R tokens are explicitly non-portable.  Implementations on
JavaScript platforms MUST reject CEDN-R input with an appropriate
error.

### 4.1. Relationship to CEDN-P

Every value that is valid CEDN-P is also valid CEDN-R, with
identical canonical output.  CEDN-R is a strict superset.

All rules from Section 3 apply to CEDN-R unless overridden in
this section.

### 4.2. BigInt

**Canonical form**: Decimal digits with optional leading minus sign,
followed by the `N` suffix.

Normative rules:

1.  The `N` suffix MUST appear.
2.  Leading zeros MUST NOT appear (except for `0N`).
3.  A value that fits within the 64-bit signed integer range MUST
    be rendered as a plain integer (Section 3.3), NOT as a BigInt.
    The `N` suffix is reserved for values that REQUIRE arbitrary
    precision.

```
  Canonical:
  9223372036854775808N    ; Long/MAX_VALUE + 1
  -9223372036854775809N   ; Long/MIN_VALUE - 1

  NOT canonical:
  42N                     ; fits in Long, MUST be "42"
  0N                      ; fits in Long, MUST be "0"
```

### 4.3. BigDecimal

**Canonical form**: Normalized decimal representation followed by
the `M` suffix.

Normative rules:

1.  The `M` suffix MUST appear.
2.  Trailing fractional zeros MUST be stripped.
3.  Leading zeros MUST NOT appear (except `0.xxxM`).
4.  If the value is a mathematical integer (e.g., `3.00M`), it MUST
    be rendered as `3M`, not `3.00M` or `3.0M`.
5.  Scientific notation MUST NOT be used.

```
  Canonical:
  3.14M
  0.001M
  3M            ; was 3.00M

  NOT canonical:
  3.140M        ; trailing zero
  03.14M        ; leading zero
  3.14E2M       ; scientific notation
```

### 4.4. Ratios

**Canonical form**: Numerator, solidus (`/`), denominator.

Normative rules:

1.  The ratio MUST be in lowest terms (GCD of numerator and
    denominator is 1).
2.  The denominator MUST be positive.
3.  The denominator MUST NOT be 1 (use integer form instead).
4.  Leading zeros MUST NOT appear in either component.

```
  Canonical:
  22/7
  -1/3
  1/1000000

  NOT canonical:
  44/14         ; not lowest terms
  22/-7         ; negative denominator
  3/1           ; MUST be integer "3"
```

### 4.5. Characters

Character literals are excluded from CEDN-R.

> **Rationale:**  ClojureScript has no character type, so characters
> are inherently non-portable.  Excluding them simplifies the
> specification and avoids cross-runtime incompatibility.

### 4.6. Ordering Extensions

Within CEDN-R, the rank ordering (Section 5) extends the number
type to include BigInt, BigDecimal, and ratio values.  All numeric
types share priority 2 and are ordered by mathematical value.

When multiple types represent the same mathematical value, the
ordering is: integer < BigInt < double < BigDecimal < ratio.

> **Note:** As with Section 5.3.3, this sub-ordering exists only
> to make the rank function total.  In practice, duplicate-equivalent
> values in sets or map keys produce errors, so this ordering
> should never appear in output.

---

## 5. Canonical Ordering

### 5.1. Purpose

Sets (Section 3.10) and maps (Section 3.11) require deterministic
element and key ordering.  CEDN defines a TOTAL ORDER (called
"rank") over all canonical values.  For any two distinct canonical
values `a` and `b`, exactly one of `rank(a, b) < 0`,
`rank(a, b) = 0`, or `rank(a, b) > 0` holds.

This ordering is based on the `arrangement` library [ARRANGEMENT],
adapted for cross-platform consistency.

### 5.2. Type Priority

Values of different types are ordered first by type priority:

```
  Priority   Type
  --------   ----
  0          nil
  1          boolean
  2          number (integer and double; BigInt, BigDecimal,
                     ratio in CEDN-R)
  3          string
  4          keyword
  5          symbol
  6          list
  7          vector
  8          set
  9          map
  10         tagged literal
```

A value of lower priority always ranks before a value of higher
priority, regardless of the values themselves.

### 5.3. Within-Type Ordering

#### 5.3.1. Nil

Only one value.  No ordering needed.

#### 5.3.2. Booleans

`false` < `true`.

#### 5.3.3. Numbers

All numeric types within a profile are ordered by mathematical
value.

When two values are mathematically equal but have different types
(e.g., integer `1` and double `1.0`), the integer ranks first.
In CEDN-R, the full sub-ordering for equal mathematical values is:
integer < BigInt < double < BigDecimal < ratio.

> **Note:** Maps and sets containing duplicate-equivalent keys or
> elements are rejected with an error (Section 7).  The sub-ordering
> for equal values exists only to make the rank function total; it
> should never produce observable output in practice.

#### 5.3.4. Strings

Strings are ordered lexicographically by **Unicode codepoint value**
(equivalently, by their UTF-8 encoded byte sequences).

> **Rationale:**  CEDN is a platform-neutral spec with a UTF-8 wire
> format.  Basing sort order on UTF-16 code units — an encoding the
> spec does not use — would leak a JVM/JS implementation detail into
> the canonical form.  Codepoint order is encoding-agnostic, matches
> the wire format, and any platform can implement it without knowledge
> of surrogate pairs.  The three candidate orderings (UTF-8 byte,
> codepoint, UTF-16 code unit) are identical for all BMP strings
> (U+0000–U+FFFF); they diverge only for astral-plane characters
> (U+10000+).

#### 5.3.5. Keywords

Keywords are ordered lexicographically by:

1.  Namespace (absent namespace sorts BEFORE any present namespace)
2.  Name (within the same namespace)

The string comparison rules from Section 5.3.4 apply to both
namespace and name components.

#### 5.3.6. Symbols

Same ordering rules as keywords (Section 5.3.5).

#### 5.3.7. Sequential Types (Lists and Vectors)

Lists and vectors are ordered element-by-element using rank
comparison.  If one sequence is a prefix of another, the shorter
sequence ranks first.

Note that lists (priority 6) and vectors (priority 7) are different
types.  All lists rank before all vectors due to type priority.
Within a type, element-by-element comparison applies.

#### 5.3.8. Sets

Sets are ordered by:

1.  Cardinality (fewer elements first).
2.  If equal cardinality: compare the sorted element sequences
    pairwise by rank.

#### 5.3.9. Maps

Maps are ordered by:

1.  Entry count (fewer entries first).
2.  If equal count: compare the sorted key sequences pairwise
    by rank.
3.  If keys are identical: compare corresponding value sequences
    pairwise by rank.

#### 5.3.10. Tagged Literals

Tagged literals are ordered by:

1.  Tag symbol (using symbol ordering, Section 5.3.6).
2.  If same tag: by the tagged value (using rank).

---

## 6. Byte Encoding

The canonical text (a Unicode string produced by the rules in
Sections 3–5) MUST be encoded as UTF-8 [RFC3629] with:

1.  NO byte order mark (BOM).
2.  NO trailing newline or whitespace.

The resulting byte sequence is the "canonical bytes" — the input
to cryptographic sign, verify, and hash operations.

Implementations MUST verify that the encoding produces valid UTF-8.
Overlong encodings, encodings of surrogates (U+D800–U+DFFF), and
encodings of code points above U+10FFFF MUST NOT appear in the
output.

---

## 7. Error Conditions

A conforming implementation MUST raise an error (not produce output)
when any of the following conditions is encountered.  Errors MUST
be reported with structured data including the error class, the
offending value, and where possible its position in the input
structure.

Silent fallbacks, best-effort rendering, and partial output are
FORBIDDEN.  CEDN is a cryptographic function; incorrect output is
worse than no output.

### 7.1. Error Classes

```
  Error class          Trigger
  -----------          -------
  unsupported-type     Value has no canonical form in the active
                       profile (Section 3.16).

  invalid-number       Double value is NaN, Infinity, or -Infinity
                       (Section 3.4).

  out-of-range         Integer value outside 64-bit signed range
                       in CEDN-P (Section 3.3).

  invalid-tag-form     Tagged literal whose nested value cannot be
                       canonicalized under the active profile
                       (Section 3.14).

  invalid-unicode      String contains unpaired UTF-16 surrogates
                       (Section 3.5.4).

  duplicate-key        Map contains keys that are value-equal (=)
                       after normalization (Section 3.11).

  duplicate-element    Set contains elements that are value-equal
                       (=) after normalization (Section 3.10).
```

### 7.2. Error Reporting

Errors SHOULD be reported using the Clojure `ex-info` mechanism
(or the ClojureScript `ExceptionInfo` equivalent) with at minimum:

```clojure
  (ex-info "CEDN canonicalization error"
           {:cedn/error   :cedn/unsupported-type   ; error class
            :cedn/value   <offending-value>         ; the value
            :cedn/path    [:key :nested :path]})    ; optional
```

---

## 8. Security Considerations

### 8.1. Determinism as a Security Property

The entire purpose of CEDN is to guarantee deterministic output for
cryptographic operations.  Any implementation bug that produces
different bytes for the same logical value is a SECURITY
VULNERABILITY — it enables signature bypass or forged content
addresses.

### 8.2. Silent Truncation

Implementations MUST NOT silently truncate precision.  For example,
when formatting `#inst` from a `java.time.Instant`, all 9
nanosecond digits MUST be emitted from the source value — an
implementation that formats only the millisecond portion and
zero-pads the rest would produce incorrect output for ns-precision
values, causing signature verification to fail or two different
values to produce the same canonical bytes (collision).

### 8.3. Lazy Sequence Realization

Canonicalizing a lazy sequence requires fully realizing it.
Implementations SHOULD be aware that this may trigger side effects
or consume unbounded memory if the sequence is infinite.
Applications SHOULD impose bounds before passing data to the
canonicalizer.

### 8.4. Denial of Service

Deeply nested structures or very large collections will consume
proportional time and memory during canonicalization.  Sorting is
O(n log n) per collection level.  Applications SHOULD impose size
and depth limits appropriate to their use case before passing data
to the canonicalizer.

### 8.5. Cross-Platform Verification

CEDN-P signatures MUST be verified using CEDN-P canonicalization.
A verifier that uses CEDN-R, non-canonical EDN, or a different CEDN
version will produce different bytes and verification will fail.
The profile and version identifier MUST be bound to the signature
as specified in Section 1.5.2.

### 8.6. Profile Confusion Attacks

If the profile and version identifier is not cryptographically
bound to the signature, an attacker can relabel a signed payload
as belonging to a different profile or version.  If the target
profile happens to produce the same bytes for that particular
value, the signature verifies under the wrong rules, and the
verifier applies incorrect semantics.

This is analogous to the `alg` substitution attack in JSON Web
Tokens, where unsigned algorithm headers allow an attacker to
select a weaker verification algorithm.

Signing protocols MUST include the `:cedn/version` key-value pair
(Section 1.5.1) in the signed content and MUST satisfy the binding
property defined in Section 1.5.2.  Implementations that carry the
version identifier as unsigned, out-of-band metadata are
NON-CONFORMING.

Verifiers MUST reject any signature where the `:cedn/version`
value cannot be authenticated as part of the signed content.

---

## 9. References

### 9.1. Normative References

```
  [EDN]       Hickey, R., "extensible data notation", 2012.
              https://github.com/edn-format/edn

  [ECMA-262]  Ecma International, "ECMAScript Language
              Specification", ECMA-262.  Section 7.1.12.1,
              "Number::toString".
              https://tc39.es/ecma262/

  [RFC2119]   Bradner, S., "Key words for use in RFCs to Indicate
              Requirement Levels", BCP 14, RFC 2119, March 1997.
              https://www.rfc-editor.org/rfc/rfc2119

  [RFC3339]   Klyne, G. and C. Newman, "Date and Time on the
              Internet: Timestamps", RFC 3339, July 2002.
              https://www.rfc-editor.org/rfc/rfc3339

  [RFC3629]   Yergeau, F., "UTF-8, a transformation format of ISO
              10646", RFC 3629, November 2003.
              https://www.rfc-editor.org/rfc/rfc3629

  [RFC5234]   Crocker, D., Ed., and P. Overell, "Augmented BNF for
              Syntax Specifications: ABNF", RFC 5234, January 2008.
              https://www.rfc-editor.org/rfc/rfc5234

  [RFC8174]   Leiba, B., "Ambiguity of Uppercase vs Lowercase in
              RFC 2119 Key Words", BCP 14, RFC 8174, May 2017.
              https://www.rfc-editor.org/rfc/rfc8174
```

### 9.2. Informative References

```
  [RFC8785]   Rundgren, A., Jordan, B., and S. Erdtman, "JSON
              Canonicalization Scheme (JCS)", RFC 8785, June 2020.
              https://www.rfc-editor.org/rfc/rfc8785

  [RYU]       Adams, U., "Ryū: fast float-to-string conversion",
              PLDI 2018.
              https://dl.acm.org/doi/10.1145/3192366.3192369

  [ARRANGEMENT]  Look, G., "mvxcvi/arrangement", 2023.
              https://github.com/greglook/arrangement

  [JCS-JAVA]  Erdtman, S., "java-json-canonicalization", 2020.
              https://github.com/erdtman/java-json-canonicalization

  [JCS-REF]   Rundgren, A., "json-canonicalization", 2020.
              https://github.com/cyberphone/json-canonicalization

  [BISCUIT]   Geoffroy, C., et al., "Biscuit authorization token
              specification".
              https://doc.biscuitsec.org/reference/specifications

  [KEX]       Ayar, S., "Kex: Biscuit-style tokens in Clojure".
              https://github.com/serefayar/kex

  [RFC7515]   Jones, M., Bradley, J., and N. Sakimura, "JSON Web
              Signature (JWS)", RFC 7515, May 2015.
              https://www.rfc-editor.org/rfc/rfc7515
```

---

## Appendix A: ABNF Grammar

The following ABNF grammar defines the canonical text syntax.  This
grammar defines only the CANONICAL form; it does not accept all
valid EDN.

```abnf
  ; --- Top-level ---

  canonical-value   = canonical-nil
                    / canonical-boolean
                    / canonical-integer
                    / canonical-double
                    / canonical-string
                    / canonical-keyword
                    / canonical-symbol
                    / canonical-list
                    / canonical-vector
                    / canonical-set
                    / canonical-map
                    / canonical-tagged

  ; --- Scalars ---

  canonical-nil     = %x6E.69.6C                        ; "nil"

  canonical-boolean = %x74.72.75.65                      ; "true"
                    / %x66.61.6C.73.65                   ; "false"

  canonical-integer = "0"
                    / ["-"] nonzero-digit *DIGIT

  nonzero-digit     = %x31-39                            ; "1"-"9"

  canonical-double  = ecmascript-number [dot-zero]
                    ; dot-zero appended iff ecmascript-number
                    ; contains neither "." nor "e"

  dot-zero          = %x2E.30                            ; ".0"

  ecmascript-number = <per ECMA-262 7.1.12.1>
                    ; Not expressible in pure ABNF; defined by
                    ; reference to the ECMAScript specification.

  ; --- Strings ---

  canonical-string  = DQUOTE *canonical-char DQUOTE

  canonical-char    = unescaped
                    / %x5C %x22                          ; \"
                    / %x5C %x5C                          ; \\
                    / %x5C %x6E                          ; \n
                    / %x5C %x72                          ; \r
                    / %x5C %x74                          ; \t
                    / %x5C %x75 4lowercase-hex           ; \uNNNN

  unescaped         = %x20-21                            ; space, !
                    / %x23-5B                            ; # through [
                    / %x5D-7E                            ; ] through ~
                    / %x80-10FFFF                        ; non-ASCII

  lowercase-hex     = DIGIT / %x61-66                    ; 0-9, a-f

  ; --- Names ---

  canonical-keyword = ":" sym-name
                    / ":" sym-namespace "/" sym-name

  canonical-symbol  = sym-name
                    / sym-namespace "/" sym-name

  sym-name          = <per EDN symbol rules>
  sym-namespace     = <per EDN symbol rules>

  ; --- Collections ---

  canonical-list    = "()"
                    / "(" elements ")"

  canonical-vector  = "[]"
                    / "[" elements "]"

  canonical-set     = "#{}"
                    / "#{" elements "}"
                    ; Elements in rank order (Section 5).

  canonical-map     = "{}"
                    / "{" map-entries "}"
                    ; Entries sorted by key rank (Section 5).

  elements          = canonical-value *( " " canonical-value )

  map-entries       = map-entry *( " " map-entry )

  map-entry         = canonical-value " " canonical-value

  ; --- Tagged Literals ---

  canonical-tagged  = "#" canonical-symbol " " canonical-value

  ; For #inst: value is canonical-string per Section 3.12.
  ; For #uuid: value is canonical-string per Section 3.13.

  ; --- CEDN-R Extensions (Section 4) ---

  canonical-bigint  = "0N"
                    / ["-"] nonzero-digit *DIGIT "N"

  canonical-bigdec  = ["-"] dec-integer ["." dec-fraction] "M"

  dec-integer       = "0" / nonzero-digit *DIGIT

  dec-fraction      = *DIGIT nonzero-digit               ; no trailing zeros

  canonical-ratio   = ["-"] dec-integer "/" pos-integer
                    ; lowest terms, denominator > 1

  pos-integer       = nonzero-digit *DIGIT                ; always > 0
```

---

## Appendix B: Number Serialization Samples

The following table shows IEEE 754 double-precision values (in
hexadecimal bit representation), their ECMAScript serialization,
and their Canonical EDN serialization (with `.0` suffix where
needed).

```
  IEEE 754 hex         ECMAScript       CEDN
  ------------         ----------       ----
  0000000000000000     "0"              "0.0"
  8000000000000000     "0"              "0.0"
  3FF0000000000000     "1"              "1.0"
  BFF0000000000000     "-1"             "-1.0"
  4024000000000000     "10"             "10.0"
  4059000000000000     "100"            "100.0"
  3FB999999999999A     "0.1"            "0.1"
  3F847AE147AE147B     "0.01"           "0.01"
  3F1A36E2EB1C432D     "0.0001"         "0.0001"
  3EB0C6F7A0B5ED8D     "0.000001"       "0.000001"
  3E7AD7F29ABCAF48     "1e-7"           "1e-7"
  4340000000000000     "9007199254740992"    "9007199254740992.0"
  4340000000000001     "9007199254740994"    "9007199254740994.0"
  444B1AE4D6E2EF50     "1e+21"          "1e+21"
  40C3880000000000     "10000"          "10000.0"
  4014000000000000     "5"              "5.0"
  4008000000000000     "3"              "3.0"
  400921FB54442D18     "3.141592653589793"   "3.141592653589793"
```

A comprehensive test vector file containing 100 million entries is
available from the JCS project [JCS-REF], at:

```
  https://github.com/cyberphone/json-canonicalization/releases/
    download/es6testfile/es6testfile100m.txt.gz
```

Format: `hex-ieee,expected-ecmascript-string\n`.  The EDN-specific
`.0` suffix must be applied to derive CEDN expected values from JCS
expected values.  Progressive SHA-256 checksums at 1K, 10K, 100K,
1M, 10M, and 100M lines are published alongside the file.

---

## Appendix C: Conformance Test Vectors

### C.1. Round-Trip Identity

The following EDN values, when canonicalized under CEDN-P, MUST
produce the exact byte sequences shown.

```
  Input value          Canonical text          UTF-8 hex bytes
  -----------          --------------          ---------------
  nil                  nil                     6E 69 6C
  true                 true                    74 72 75 65
  false                false                   66 61 6C 73 65
  42                   42                      34 32
  -7                   -7                      2D 37
  0                    0                       30
  3.14                 3.14                    33 2E 31 34
  1.0                  1.0                     31 2E 30
  ""                   ""                      22 22
  "hello"              "hello"                 22 68 65 6C 6C 6F 22
  "a\tb"               "a\tb"                  22 61 5C 74 62 22
  :foo                 :foo                    3A 66 6F 6F
  :ns/bar              :ns/bar                 3A 6E 73 2F 62 61 72
  foo                  foo                     66 6F 6F
  ()                   ()                      28 29
  [1 2 3]              [1 2 3]                 5B 31 20 32 20 33 5D
  #{3 1 2}             #{1 2 3}                23 7B 31 20 32 20 33 7D
  {:b 2 :a 1}          {:a 1 :b 2}            7B 3A 61 20 31 20 3A 62
                                               20 32 7D
```

### C.2. Normalization

The following non-canonical inputs MUST produce the shown canonical
output:

```
  Input (non-canonical)              Canonical output
  ---------------------              ----------------
  {  :b  2  ,  :a  1  }             {:a 1 :b 2}
  #{ 3  1  2 }                      #{1 2 3}
  {:z 1, :a 2, :m 3}                {:a 2 :m 3 :z 1}
  [  1 ,  2 ,  3  ]                 [1 2 3]
```

### C.3. Cross-Type Ordering

The following set, containing mixed types, MUST produce the shown
canonical output (demonstrating type priority ordering):

```
  Input:
  #{:kw "str" true 42 nil [1] (2) #{} {} 3.14}

  Canonical output:
  #{nil true 3.14 42 "str" :kw (2) [1] #{} {}}
```

> **Note:** 3.14 (double) and 42 (integer) share numeric priority 2.
> 3.14 < 42 by mathematical value, so 3.14 appears first.

### C.4. Error Cases

The following inputs MUST produce the indicated error under CEDN-P:

```
  Input                    Error class
  -----                    -----------
  ##NaN                    invalid-number
  ##Inf                    invalid-number
  ##-Inf                   invalid-number
  42N                      unsupported-type
  3.14M                    unsupported-type
  22/7                     unsupported-type
  \a                       unsupported-type
  #"regex"                 unsupported-type
```

### C.5. String Escaping

```
  Input string (Clojure)       Canonical text
  ----------------------       --------------
  "hello"                      "hello"
  "say \"hi\""                 "say \"hi\""
  "line1\nline2"               "line1\nline2"
  "tab\there"                  "tab\there"
  "back\\slash"                "back\\slash"
  "\u0000"                     "\u0000"
  "café"                       "café"
  "\u00e9"                     "é"
```

> **Note:** The last two examples demonstrate that non-ASCII
> characters MUST appear as literal UTF-8, never as `\uNNNN`
> escapes.

---

## Authors' Addresses

```
  [TBD]
```
