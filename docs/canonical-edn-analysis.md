# Canonical EDN: Specification Analysis & Design Proposal

## 1. The Problem

EDN (Extensible Data Notation) is a data format whose spec explicitly leaves certain aspects
non-deterministic:

- **Map key ordering**: "No semantics should be associated with the order in which the pairs appear"
- **Set element ordering**: same rule applies
- **Whitespace**: "not otherwise significant, nor need redundant whitespace be preserved"
- **Commas**: treated as whitespace, optional

This means that the same logical value `{:a 1 :b 2}` can be serialized in many textually
different ways, all of which are valid EDN. For cryptographic operations (signing, hashing,
content-addressing), this is fatal — you need **one and only one** byte-level representation
for a given logical value.

Additionally, Clojure lacks a `clojure.edn/generate-string` counterpart to `clojure.edn/read-string`.
The de facto writer, `pr-str`, is a **developer convenience function** subject to:

- `*print-length*` — truncates collections silently
- `*print-level*` — truncates nesting silently
- `*print-namespace-maps*` — changes map syntax (`#:ns{...}`)
- Platform-dependent number formatting (JVM vs JS)
- No character type in ClojureScript (chars become single-char strings)
- No BigDecimal in JavaScript

The consequence: you cannot safely `pr-str` data on one platform and verify a signature on another.

## 2. Requirements for Canonical EDN

A Canonical EDN specification must deliver:

1. **Deterministic output**: identical logical values → identical byte sequences, always
2. **Cross-platform consistency**: JVM Clojure, ClojureScript (browser), Node.js, Babashka must
   produce identical output for identical logical values
3. **Value-level roundtrip fidelity**: `(= x (edn/read-string (canonical-pr-str x)))` must hold
   for all supported EDN types. See §2.1 for what "fidelity" means and doesn't mean.
4. **Valid EDN**: canonical output must be a strict subset of valid EDN — any EDN reader can
   parse it
5. **Explicit type handling**: unknown types must cause an error, never silently produce
   non-deterministic output
6. **No ambient state dependency**: output must not depend on dynamic vars, thread-local state,
   or runtime configuration
7. **Minimal output**: compact representation (no pretty-printing, minimal whitespace) to
   minimize the signed payload size
8. **Human readability**: unlike Protobuf/CBOR, the canonical form should be human-inspectable
   (this is the core advantage of the EDN-over-binary approach)

### Non-requirements (deliberate exclusions)

- **Pretty-printing**: canonical form is compact, not pretty
- **Streaming/lazy evaluation**: canonical serialization requires fully-realized data
- **Metadata preservation**: metadata is not part of the value semantics

### 2.1 One-Way Normalization, Not Bidirectional Codec

This section exists because it's natural — and healthy — to feel uneasy about
throwing away type information. The engineering instinct says: if I put a record in,
I should get a record back. If I put an ordered map in, the order should survive.
That instinct is good practice for serialization formats. But `canonical-pr-str`
is not a serialization format.

**What `canonical-pr-str` is:** a normalization function that produces deterministic
bytes for signing and verification. It's analogous to Unicode NFC normalization or
database collation — many representations collapse to one, and that's the whole point.

**What `canonical-pr-str` is not:** a codec. It doesn't have a decode side. There is
no `canonical-pr-read` that reconstructs the original type. The consumer of canonical
bytes is a cryptographic function (`sign`, `verify`, `hash`), not a deserializer.

**The Kex data flow makes this concrete:**

```
CREATING A TOKEN (signer side):
  1. Build token as Clojure data (maps, vectors, keywords, strings)
  2. canonical-pr-str → deterministic bytes
  3. sign(bytes, private-key) → signature
  4. Transmit token + signature (as EDN, JSON, protobuf, whatever)

VERIFYING A TOKEN (verifier side):
  1. Receive token + signature (deserialize however you like)
  2. canonical-pr-str → deterministic bytes  (must match signer's bytes)
  3. verify(bytes, signature, public-key) → true/false
```

Notice: `canonical-pr-str` appears on both sides, but `canonical-pr-read` appears
on *neither* side. The signer and verifier need to agree on the canonical bytes,
not reconstruct the original runtime types. The token was probably transmitted as
plain EDN or JSON between signer and verifier anyway — the record types are already
gone by the time the verifier sees the data.

**Why NOT to add bidirectional encoding as a requirement:**

Adding round-trip type preservation would mean:
- Records must emit as tagged literals (`#myapp/Token {...}`)
- Tag symbols must be platform-stable (JVM class names ≠ CLJS names ≠ Babashka names)
- A registry must exist on both sides, mapping tags ↔ types
- Registry mismatch between signer and verifier = verification failure
- Babashka's `record?` detection differs from JVM's (metadata vs class)
- The entire design becomes coupled to application-specific type definitions

All of this complexity serves a requirement we don't have. The signing use case
needs: same value → same bytes. Period.

**What round-trip DOES hold — value equality:**

```clojure
;; This is guaranteed:
(let [original {:b 2 :a 1}]
  (= original (edn/read-string (canonical-pr-str original))))
;;=> true

;; This is ALSO guaranteed (record → map, but = still holds):
(defrecord Token [a b])
(let [original (->Token 1 2)]
  (= original (edn/read-string (canonical-pr-str original))))
;;=> true  (because records are = to maps with same keys+values)
```

The canonical form reads back as a plain map, not a record. But `=` still holds
because Clojure records are equal to maps with the same entries. The *value* round-trips.
The *type* doesn't. For signing, only the value matters.

**When WOULD you want bidirectional encoding?**

If you need type-preserving serialization — sending a `Token` record over the wire and
getting a `Token` back — that's a real need, but it's a different tool:

- **Transit** does this (with type handlers for records)
- **Nippy** does this (binary format, JVM-only)
- **Tagged EDN** does this (with `miner/tagged` or `lambdaisland/data-printers`)

These are serialization formats. `canonical-pr-str` is not. Trying to make it do both
jobs would compromise its core guarantee (determinism) to serve a requirement that
other tools already handle well.

**The mental model:**

```
                    ┌─────────────────────────┐
  record            │                         │
  hash-map    ──→   │   canonical-pr-str      │ ──→  deterministic bytes ──→ sign/verify
  array-map         │   (many-to-one)         │
  sorted-map        │                         │
                    └─────────────────────────┘

  This is like:      NFC normalization, database collation, content hashing
  This is NOT like:  Transit, Nippy, Protobuf, JSON codec
```

If at some point Kex or another application needs type-preserving canonical
serialization (e.g., for content-addressed storage where you want to reconstruct
the exact record type), that can be layered on top as an opt-in extension. But it
should not be in the core spec, because it would force every user of canonical-edn
to deal with type registries and platform-specific record detection — complexity
that the signing use case doesn't need.

## 3. Existing Solutions — Deep Analysis

### 3.1 Puget's CanonicalPrinter

**What it is**: A printer record implementing fipp's `IVisitor` protocol that produces
deterministic EDN text. Sorts map keys and set elements using `arrangement/rank`.

**Source review findings** (puget/printer.clj, ~550 lines):

- Delegates primitive rendering to `pr-str` for numbers, strings, characters
- Sorts sets with `(sort order/rank value)`
- Sorts map keys with `(sort-by first order/rank value)`
- Throws `IllegalArgumentException` for unknown types (correct behavior)
- Strips metadata (correct for canonical form)
- Uses fipp's document model (`:group`, `:span`, `:align`) for output generation
- Width set to 0 in canonical mode (no line-breaking)

**Strengths**:
- Mature, well-tested (v1.3.4, 15.8M downloads on Clojars, 122 dependents)
- Correct sorting semantics via arrangement
- Strict mode rejects unknown types
- Public domain license (UNLICENSE)

**Critical limitations for our use case**:
1. **JVM-only** — `printer.clj` is a `.clj` file, not `.cljc`. Uses Java interop extensively
   (`System/identityHashCode`, `java.text.SimpleDateFormat`, `Class/forName`, etc.)
2. **Delegates to `pr-str` for primitives** — number, string, and character rendering uses
   `(pr-str value)`, inheriting all of `pr-str`'s platform-dependent behavior
3. **Heavy dependency tree** — requires fipp (pretty-printer engine) which is overkill for
   compact canonical output with width=0
4. **No number formatting specification** — relies on JVM's `pr-str` behavior for numbers,
   which is undocumented as a stable serialization contract
5. **Tagged literal formatting is loose** — `#inst` uses `SimpleDateFormat` with a specific
   format string, but this is hardcoded in the Java handler, not specified

**Verdict**: Correct architecture and sorting semantics, but not cross-platform and delegates
the hardest canonicalization questions (number formatting) to `pr-str`.

### 3.2 mvxcvi/arrangement

**What it is**: A total-ordering comparator for Clojure values. The `rank` function
provides deterministic comparison across all EDN types.

**Source review findings** (arrangement/core.cljc, ~100 lines):

Type priority order:
```
nil < false < true < number < char < string < keyword < symbol < list < vector < set < map
```

Key behaviors:
- **Cross-platform** — `.cljc` file with reader conditionals for JVM/JS
- Numbers compared via `compare` (works because Clojure unifies numeric comparison)
- Strings, keywords, symbols compared via `compare` (lexicographic)
- Sequential collections compared element-by-element (lexicographic on elements)
- Sets: compare by cardinality first, then sorted elements
- Maps: compare by cardinality first, then entries sorted by key
- Unknown types: compare by type name, then by `Comparable` or `str` representation

**Strengths**:
- Cross-platform (`.cljc`)
- Complete total ordering for all EDN types
- Well-defined tiebreaking at every level
- Public domain license
- Tiny footprint (~100 lines)

**Limitations**:
- Characters have their own priority slot (position 4), but ClojureScript has no character
  type. This needs reconciliation for cross-platform canonical EDN.
- Map comparison sorts entries by key only (not key+value), which is correct for a comparator
  but worth noting.

**Verdict**: Excellent foundation for the ordering component. Can be used directly or adapted.

### 3.3 hasch (replikativ)

**What it is**: Content-hashing library that produces deterministic SHA-512 hashes directly
from Clojure data structures, without going through text.

**Source review findings** (hasch/benc.cljc + hasch/platform.clj):

Type magics (binary type tags):
```
nil=0, boolean=1, number=2, string=3, symbol=4, keyword=5,
inst=6, uuid=7, seq=8, vector=9, map=10, set=11, literal=12, binary=13
```

Key design decisions:
- **Maps hashed via XOR of entry hashes** — commutative, so order doesn't matter.
  Brilliant for hashing, but means hasch never needs to sort map entries.
- **Sets hashed via XOR of element hashes** — same approach
- **Numbers encoded via `.toString`** — explicitly uses `(.getBytes (.toString this) "UTF-8")`
  for Integer, Long, Float, Double, BigDecimal, BigInt. This is **not canonical across
  platforms** — `(.toString 1.0)` gives `"1.0"` on JVM but JavaScript's `(1.0).toString()`
  gives `"1"`.
- **Characters treated as strings** — comment in source: "don't distinguish characters from
  string for javascript." This is a pragmatic cross-platform decision.
- **Dates encoded as epoch millis** — `(.toString (.getTime date))` — stable across platforms
- **Records normalized via incognito** — converts JVM class names to platform-neutral tags

**Strengths**:
- Cross-platform (JVM + ClojureScript)
- Battle-tested in datahike for distributed transaction log exchange
- Handles records, tagged literals, binary data, files
- XOR approach for maps/sets is cryptographically sound and avoids sorting overhead
- Type taxonomy is well-thought-out

**Critical limitations for our use case**:
1. **Produces bytes, not text** — hasch's entire design sidesteps text canonicalization.
   It goes directly from values to hashes. We need human-readable canonical text.
2. **Number encoding is platform-dependent** — `.toString` varies between JVM and JS for
   edge cases (trailing zeros, scientific notation thresholds, floating-point representation)
3. **XOR approach means no canonical ordering** — we need ordered output for text serialization
4. **No string escape rules** — hasch just UTF-8 encodes raw bytes, doesn't need to decide
   between `\n` vs literal newline

**Verdict**: Wrong tool for text canonicalization (by design), but the type taxonomy,
characters-as-strings decision, dates-as-epoch-millis encoding, and incognito integration
for tagged literals are all directly reusable insights.

### 3.4 Kex's hand-rolled approach

```clojure
(defn canonical [x]
  (cond
    (map? x)    (into (sorted-map) (for [[k v] x] [k (canonical v)]))
    (set? x)    (mapv canonical (sort x))
    (vector? x) (mapv canonical x)
    (seq? x)    (mapv canonical x)
    :else       x))
```

Then: `(.getBytes (pr-str (canonical block)) StandardCharsets/UTF_8)`

**Strengths**: Incredibly simple (~10 lines). Works for the PoC.

**Limitations**:
- `sort` on heterogeneous sets will throw (no total ordering)
- `pr-str` dependency for all the reasons above
- No type validation (silently serializes arbitrary JVM objects)
- JVM-only (`StandardCharsets/UTF_8`)
- Sets become vectors (changes the type, which changes the EDN representation)

## 4. The Hard Problems

### 4.1 Number Formatting

This is the single hardest canonicalization problem, and it's what cost RFC 8785 the most
specification effort.

EDN has these number types:
- **Integers**: `42`, `-7`, `0`
- **Arbitrary-precision integers**: `42N`
- **Floating-point (double)**: `3.14`, `1.0E7`
- **Arbitrary-precision decimal**: `3.14M`

Cross-platform issues:

| Value | JVM `pr-str` | JS `pr-str` | Problem |
|-------|-------------|-------------|---------|
| `1.0` | `"1.0"` | `"1"` | JS drops trailing `.0` |
| `1.0E21` | `"1.0E21"` | `"1e+21"` | Different exponent format |
| `0.1` | `"0.1"` | `"0.1"` | OK (same) |
| `42N` | `"42N"` | N/A | BigInt not in CLJS core |
| `1.5M` | `"1.5M"` | N/A | BigDecimal not in JS |

**Proposed canonical number rules**:

1. **Integers** (Long, int): decimal digits with optional leading `-`. No leading zeros except
   `0` itself. No `+` prefix. No `N` suffix unless the value exceeds 64-bit signed range.
   - Canonical: `42`, `-7`, `0`

2. **Floats/Doubles**: Adopt the ECMAScript Number serialization algorithm (ECMA-262
   Section 7.1.12.1 with "Note 2" round-to-even), the same algorithm JCS uses.
   This produces the **shortest decimal representation that round-trips** to the exact
   same IEEE 754 double. Implementation: reuse existing JCS implementations — on JS,
   native `Number.toString()` already implements this; on JVM, use the battle-tested
   Ryu-to-ECMAScript port from the JCS reference implementation (see **§4.1.1** for
   full analysis of options and difficulty). **Critical EDN adaptation**: unlike JSON
   where `1` and `1.0` are the same type, EDN distinguishes integers from floats.
   Therefore, if the ECMAScript algorithm produces an integer-looking result (e.g., `1`),
   we must render it as `1.0` to preserve the double type. Exponent notation (e.g.,
   `1e+23`) is acceptable as-is since it implies float.
   - Canonical: `0.1`, `1.0`, `-3.14`, `1e+23`, `4.5`, `0.002`
   - NaN and Infinity MUST cause an error (following JCS precedent)
   - Negative zero (`-0.0`) MUST be serialized as `0.0` (following JCS precedent)

3. **BigInt (`N` suffix)**: Only used when the value cannot fit in a 64-bit signed integer.
   Values that fit in Long range MUST be written as plain integers.
   - Canonical: `9223372036854775808N` (Long/MAX_VALUE + 1)

4. **BigDecimal (`M` suffix)**: The `M` suffix is always present. Normalized form: no trailing
   zeros, no leading zeros (except `0.xxx`). This is a JVM-specific type; for cross-platform
   scenarios, consider whether BigDecimal should be in the canonical subset at all.

### 4.1.1 How JCS Solved Number Formatting (And What We Can Reuse)

The proposed canonical number rules above (§4.1 item 2) specify the ECMAScript Number
serialization algorithm for doubles. This is the exact same spec that RFC 8785 / JCS uses.
A critical question is: how hard is this to implement cross-platform? The answer, after
examining the actual JCS implementations, is **much less hard than initially feared** — but
the difficulty is entirely asymmetric between platforms.

#### The JS side: zero work

The JCS reference implementation in JavaScript
([cyberphone/json-canonicalization/node-es6/canonicalize.js](https://github.com/cyberphone/json-canonicalization/blob/master/node-es6/canonicalize.js))
handles numbers with literally one line:

```javascript
buffer += JSON.stringify(object);  // For primitives including numbers
```

`JSON.stringify()` on a number calls `Number.prototype.toString()`, which **is** the
ECMAScript Number serialization algorithm. On V8 (Node.js, Chrome, Scittle), this uses
the Ryu algorithm internally. On SpiderMonkey (Firefox), it uses Grisu3 + Dragon4. Both
produce identical output because they're both implementing the same ECMAScript spec.

**For ClojureScript / Scittle**: `(.toString x)` on a JS number already produces the
canonical form. Zero adaptation code needed.

#### The JVM side: Java's `Double.toString()` is NOT the ECMAScript spec

This was the key misconception in our earlier analysis. Java 17+ uses Ryu internally for
`Double.toString()`, but it implements **Java's formatting rules**, not ECMAScript's. The
differences are not just cosmetic — they involve different thresholds for when to use
scientific notation:

| Value | Java `Double.toString()` | ECMAScript `Number.toString()` | Difference |
|-------|--------------------------|-------------------------------|------------|
| `1.0` | `"1.0"` | `"1"` | Java adds trailing `.0` |
| `10.0` | `"10.0"` | `"10"` | Java adds trailing `.0` |
| `1e-4` | `"1.0E-4"` | `"0.0001"` | Different notation threshold |
| `1e-5` | `"1.0E-5"` | `"0.00001"` | Different notation threshold |
| `1e20` | `"1.0E20"` | `"100000000000000000000"` | Different notation threshold |
| `1e+21` | `"1.0E21"` | `"1e+21"` | Both use scientific, but format differs |

ECMAScript uses scientific notation **if and only if** the exponent is outside `[-6, 21)`.
Java uses a different, more complex rule. This means a "thin fixup" on `Double.toString()`
output would need to:
1. Parse the number from Java's output
2. Determine whether ECMAScript would use scientific or fixed notation
3. Reformat accordingly (including stripping trailing `.0`, changing `E` to `e`, adding `+`)

This is error-prone. The Ryu repository itself documents that Java and ECMAScript Ryu
produce different digit sequences in some edge cases (see
[ulfjack/ryu#83](https://github.com/ulfjack/ryu/issues/83)). **A fixup approach cannot
work reliably.**

#### What the JCS implementations actually do

Both major Java JCS implementations ship a **complete Ryu-to-ECMAScript port**:

1. **cyberphone/json-canonicalization** (the RFC 8785 reference implementation):
   - `DoubleCoreSerializer.java` — ~500 LOC. A modified version of Ulf Adams' Ryu
     algorithm, adapted to output ECMAScript-compliant formatting. Handles the `[-6, 21)`
     scientific notation threshold, integer suppression of `.0`, lowercase `e`, explicit `+`
     in positive exponents, and all edge cases. Apache 2.0 licensed.
   - `NumberToJSON.java` — ~30 LOC thin wrapper: handles zero (`→ "0"`),
     NaN/Infinity (→ error), delegates to DoubleCoreSerializer for everything else.

2. **erdtman/java-json-canonicalization** (Maven Central):
   - Uses Grisu3 (V8's original algorithm) instead of Ryu — 6 files totaling ~28KB.
   - Available as Maven dependency: `[io.github.erdtman/java-json-canonicalization "1.1"]`
   - The key function: `NumberToJSON.serializeNumber(double) → String`
   - Zero dependencies. Apache 2.0 licensed.

3. **Elixir JCS** ([pzingg/jcs](https://github.com/pzingg/jcs)):
   - Uses Erlang OTP 25's `float_to_binary/2` with the `:short` option, which implements
     Ryu internally and already produces ECMAScript-compatible output.
   - One function call. Demonstrates how a BEAM platform leverages runtime internals.

#### JCS test vectors for verification

The JCS project provides **100 million deterministic test vectors** for number formatting:
each entry is `hex-ieee,expected-ecmascript-string\n`. Progressive SHA-256 checksums allow
verification at 1K, 10K, 100K, 1M, 10M, and 100M lines. Both `numgen.go` and `numgen.js`
can regenerate the test file locally.

Available at: https://github.com/cyberphone/json-canonicalization/releases/download/es6testfile/es6testfile100m.txt.gz

Sample test vectors:
```
4340000000000001,9007199254740994
4340000000000002,9007199254740996
444b1ae4d6e2ef50,1e+21
3eb0c6f7a0b5ed8d,0.000001
3eb0c6f7a0b5ed8c,9.999999999999997e-7
8000000000000000,0
0,0
```

#### Practical options for canonical-edn on JVM

Given the above, the implementation options for `canonical-edn.number/format-double` on
the JVM are:

| Option | LOC | Risk | Notes |
|--------|-----|------|-------|
| **(A) Use erdtman as Maven dependency** | 0 | Low | Call `NumberToJSON.serializeNumber()`. Adds one dependency. Already on Maven Central. |
| **(B) Copy cyberphone's 2 files** | ~530 Java | Low | `DoubleCoreSerializer.java` + `NumberToJSON.java`. Apache 2.0. Self-contained, no dependency. Wrap in Clojure interop. |
| **(C) Port DoubleCoreSerializer to Clojure** | ~400 .clj | Medium | Direct port. Gains Clojure idioms but loses line-for-line correspondence with battle-tested Java. |
| **(D) Fixup `Double.toString()` output** | ~80 .clj | **High** | Unreliable. Different thresholds, documented digit differences. NOT recommended. |

**Recommendation**: Start with **(A)** for rapid prototyping and conformance testing. The
erdtman library is zero-dependency, 28KB, Apache 2.0, and provides exactly the function
we need. For production, consider **(B)** — copying the two cyberphone files into the project
eliminates the external dependency while preserving the battle-tested implementation.

Note that option (A) or (B) means `canonical-edn.number` would have a platform-specific
implementation:
- **JVM (.clj)**: Calls into Java `NumberToJSON.serializeNumber()` (from dependency or
  copied source)
- **JS (.cljs)**: Calls `(.toString x)` — which IS the ECMAScript spec, zero adaptation

This asymmetry is appropriate. It mirrors what JCS itself does: the JS implementation is
trivial because the platform already speaks ECMAScript; the Java implementation carries a
Ryu port because Java speaks a different dialect.

#### EDN-specific adaptation: preserving the float/integer distinction

One critical difference from JCS: in JSON, `1` and `1.0` are the same type. In EDN, they
are fundamentally different (`Long` vs `Double`). The ECMAScript algorithm outputs `"1"` for
the double value `1.0`, which in EDN would read back as an integer.

Therefore, `canonical-edn.number/format-double` must add one post-processing step:

```clojure
(defn format-double [x]
  (let [s (ecmascript-format x)]  ; ← from NumberToJSON or (.toString x)
    (if (needs-decimal-point? s)   ; does s look like an integer? (no . or e)
      (str s ".0")                 ; add .0 to preserve double type in EDN
      s)))
```

Where `needs-decimal-point?` checks whether the output contains neither a `.` nor an `e`/`E`.
This handles cases like `1.0 → "1" → "1.0"` and `0.0 → "0" → "0.0"` while leaving
`1e+21` and `0.001` untouched.

This is the **only** place where canonical EDN's number formatting diverges from JCS's.

### 4.2 Characters

ClojureScript has **no character type** — `\a` doesn't exist. Characters are single-character
strings.

**Options**:
- **A)** Exclude characters from canonical EDN. Require strings instead. Simplest, most portable.
- **B)** Include characters but map them to a canonical form on all platforms. On CLJS, the
  reader already handles `\a` → `"a"`.

**Recommendation**: Option A. Canonical EDN should not contain character literals. If your
source data contains characters, the canonicalization step should convert them to single-character
strings (or error). This follows hasch's precedent.

### 4.3 String Escaping

EDN strings support: `\t`, `\r`, `\n`, `\\`, `\"`, and `\uNNNN`.

**Canonical escaping rules**:
- `"` → `\"`
- `\` → `\\`
- Newline (U+000A) → `\n`
- Carriage return (U+000D) → `\r`
- Tab (U+0009) → `\t`
- All other control characters (U+0000–U+001F, U+007F) → `\uNNNN` (**lowercase** hex, 4 digits,
  following JCS precedent for consistency)
- All other Unicode characters: pass through as literal UTF-8 (no escaping)

This is more restrictive than EDN requires but ensures determinism — there's exactly one way
to write any string.

### 4.4 Tagged Literals — The #inst Mess

EDN allows arbitrary tagged literals: `#my/tag value`. The two built-in tags — `#inst`
and `#uuid` — already illustrate the cross-platform hazards.

#### 4.4.1 `#inst` — The Poster Child for Platform Divergence

`#inst` is defined by the edn spec as "an instant in time" with "at least millisecond
precision." But the *runtime representation* is platform-dependent:

| Platform | `#inst` reads as | `pr-str` output format |
|----------|-----------------|----------------------|
| JVM Clojure | `java.util.Date` (ms precision) | `#inst "2026-02-26T12:00:00.000-00:00"` |
| JVM + java.time | `java.time.Instant` (ns precision) | `#inst "2026-02-26T12:00:00.000000000Z"` |
| JVM + java.sql | `java.sql.Timestamp` (ns precision) | `#inst "2026-02-26T12:00:00.000000000-00:00"` |
| ClojureScript | `js/Date` (ms precision) | `#inst "2026-02-26T12:00:00.000-00:00"` |
| Babashka | `java.time.Instant` | varies by version |

The problems compound:
- **Precision**: `java.util.Date` stores milliseconds. `java.time.Instant` stores
  nanoseconds. Same logical instant, different string representations.
- **Timezone offset format**: JVM default uses `-00:00`, but `Z` is also valid RFC 3339.
  Some formatters emit `+00:00`, others emit `Z`, others emit `-00:00`. All are valid.
  `Z` ≠ `+00:00` as bytes, even though they mean the same thing.
- **Trailing zeros**: Is it `.000Z` or `.0Z` or `Z` (no fractional seconds)? All valid.
- **Nanosecond precision**: `#inst "2026-02-26T12:00:00.123456789Z"` round-trips on
  `java.time.Instant` but silently truncates to `.123Z` on `java.util.Date` and `js/Date`.
  Signature verification would **fail silently** if creator used Instant and verifier
  used Date.

**Canonical rule for `#inst`**: The tagged element MUST be an RFC 3339 string in UTC,
always with `Z` suffix (not `+00:00`). Example: `#inst "2026-02-26T12:00:00.000Z"`.

**Important**: `canonical-pr-str` does NOT call `pr-str` on the Date/Instant object
and then fix up the output. It extracts the epoch value and formats it from scratch
using its own RFC 3339 formatter. This avoids any platform-dependent print-method dispatch.

**Open discussion — precision level**:

Both external reviewers flagged that **silent truncation of sub-millisecond precision in
a cryptographic context is dangerous**: if a signer creates a token from a `java.time.Instant`
with nanosecond precision, and the canonicalizer silently truncates to milliseconds, the
verifier's hash will only match if they also truncate. If they don't, silent verification
failure. This is exactly the class of bug canonical EDN exists to prevent.

The default behavior MUST be: **reject (throw `precision-exceeded`) if the source value has
precision beyond the profile's limit**, rather than silently truncating. This forces the user
to explicitly decide what precision they want *before* signing.

However, the question of what that precision limit should be remains open:

| Option | Format | LCD platform | Trade-off |
|--------|--------|-------------|-----------|
| **Milliseconds** (`.NNN`) | `#inst "2026-02-26T12:00:00.000Z"` | `java.util.Date`, `js/Date` | Lowest common denominator. But ms is genuinely too coarse for some applications (event ordering, distributed tracing). |
| **Microseconds** (`.NNNNNN`) | `#inst "2026-02-26T12:00:00.000000Z"` | `java.time.Instant` (truncated), needs JS polyfill | Better granularity. JS `Date` only stores ms but could zero-pad the extra digits. |
| **Nanoseconds** (`.NNNNNNNNN`) | `#inst "2026-02-26T12:00:00.000000000Z"` | `java.time.Instant` | Full JVM precision. JS `Date` can't represent it natively, but the canonical form is just a string at this layer — the signing/verification doesn't require parsing back to a Date. |

A pragmatic approach: CEDN-P mandates a **fixed number of fractional digits** (always
present, always zero-padded) so there's no ambiguity. Whether that's 3, 6, or 9 digits
is a decision we defer until implementation proves which causes the least pain. The key
invariant is: the digit count is fixed per profile version, never variable.

#### 4.4.2 `#uuid` — Simpler but Still Has a Trap

`#uuid` is a 128-bit value rendered as a lowercase hexadecimal string with dashes.
The main risk: some UUID libraries produce UPPERCASE hex digits. A few implementations
omit leading zeros in segments.

**Canonical rule for `#uuid`**: Lowercase hex, exactly 8-4-4-4-12 digit format.
Example: `#uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"`. Again, format from the
128-bit value directly, don't delegate to `.toString()`.

#### 4.4.3 User-Defined Tags: The Extensibility Problem

EDN's whole point is extensibility via tagged literals. Libraries define tags like
`#time/date "2026-02-26"`, `#my.app/money {:amount 42.50M :currency "EUR"}`, etc.

For canonical EDN, user tags present a **fundamental tension**:
- `canonical-pr-str` cannot know how to canonicalize arbitrary tagged values.
- But rejecting all user tags would make canonical EDN useless for real applications.

**Resolution — two tiers**:

1. **Tag + EDN value**: If the tagged value is a standard EDN type (string, map, vector,
   etc.), `canonical-pr-str` canonicalizes the value normally and emits `#tag canonical-value`.
   The tag symbol itself is written in its canonical form (see symbol rules).

2. **Tag + opaque host object**: If someone passes a `java.time.LocalDate` to
   `canonical-pr-str`, it's not an EDN type. The function MUST throw, not silently
   call `.toString()`. The caller is responsible for converting host objects to EDN
   representations before canonicalization.

This is the Puget strict-mode principle: **if we don't know how to canonicalize it,
we refuse to guess.**

#### 4.4.4 Records and Custom Types — The Elephant in the Room

This is the section that's easy to hand-wave away with "throw on unknown types" and
move on. But records and custom types are *how Clojure programs model domain data*.
If `canonical-pr-str` can't deal with them, it can't deal with real applications.

**The honest inventory of what `defrecord` actually produces across platforms:**

| Platform | What `defrecord` creates | `pr-str` output | Type identity |
|----------|------------------------|----------------|---------------|
| JVM Clojure | Real Java class | `#user.Token{:a 1}` | `user.Token` (Java class) |
| ClojureScript | JS constructor function | `#cljs.user.Token{:a 1}` | `cljs.user/Token` (JS) |
| Babashka | PersistentArrayMap + metadata | `#user.Token{:a 1}` | `{:type 'user/Token}` (metadata on a map) |
| Scittle | Same as CLJS (SCI-based) | Varies | Varies |

Notice:
- **None of these `pr-str` outputs are valid EDN.** The `#user.Token{:a 1}` notation
  is Clojure's class-literal syntax, not an EDN tagged literal. `edn/read-string`
  will throw on all of them.
- **The class names differ across platforms.** JVM uses `user.Token`, CLJS uses
  `cljs.user.Token`, Babashka has no class at all.
- **On Babashka, records aren't classes.** They're regular maps with metadata
  `{:sci.impl/record true, :type 'user/Token}`. Protocol dispatch works differently.
  `instance?` doesn't work. `print-method` dispatch (which relies on class) doesn't
  work the same way.

**And `deftype` is worse:**

| Platform | `deftype` support |
|----------|------------------|
| JVM Clojure | Full — creates Java class, no map interface by default |
| ClojureScript | Full — creates JS constructor |
| Babashka | **Not supported at all** (only protocol impls, no Java interfaces) |

So if your application uses `deftype` for custom data structures, it literally cannot
run on Babashka. And even on JVM, `deftype` instances have no standard serialization —
`pr-str` produces `#object[user.MyType 0x7a3d45e ...]` which is garbage.

**Why "just throw" is not good enough:**

Consider Kex's actual use case. Right now, Kex tokens are plain maps:
```clojure
{:authority {:facts [[:right "file1" "read"]]
             :rules []}
 :blocks [{:facts [...] :rules [...]}]
 :signature {:algorithm :ed25519
             :public-key "..."
             :value "..."}}
```

This is fine — plain maps canonicalize perfectly. But as Kex matures, there will be
pressure to use records for type safety, protocol dispatch, or performance:

```clojure
(defrecord Authority [facts rules])
(defrecord Block [facts rules])
(defrecord Token [authority blocks signature])
```

If we just "throw on records," we've created a landmine: code works fine with maps,
someone refactors to records for legitimate reasons, and canonicalization silently
starts throwing in production.

**The real design question: where does type identity live?**

Records in Clojure blend two concerns:
1. **Structure** — a map with specific keys and values (this IS data, and IS EDN)
2. **Type identity** — "this is a Token, not just any map" (this is NOT data in EDN's
   model, and is platform-specific)

For canonical serialization, we have to decide: does type identity survive the
round-trip, or doesn't it?

**Option A: Strip type identity (records → maps)**

The simplest approach: treat records as their underlying maps. A `Token` record
canonicalizes as `{:algorithm :ed25519 :authority {:facts ...} ...}` (sorted keys,
no type tag). Round-trip gives you a map, not a record. Type identity is lost.

```clojure
;; Token record in → plain map out
(canonical-pr-str (->Token auth blocks sig))
;;=> "{:authority {...} :blocks [...] :signature {...}}"

(edn/read-string *1)
;;=> {:authority {...} :blocks [...] :signature {...}}  ;; just a map, not a Token
```

Pros: Simple. Cross-platform. No registry needed.
Cons: Lossy. Can't reconstruct the original type from canonical form alone.

This is actually fine for **signing/verification** (you just need deterministic bytes),
but bad for **serialization/deserialization** (you want to get a Token back).

**Option B: Tagged literals with explicit registry**

Records canonicalize as tagged EDN literals, but only if the caller has registered
a tag for that type. The registry maps types to (tag-symbol, to-edn-fn) pairs.

```clojure
;; Registration — explicit, auditable, per-application
(canonical-edn/register-type!
  {:type    Token                              ;; the record class/constructor
   :tag     'myapp/token                       ;; the EDN tag symbol
   :to-edn  (fn [t] (select-keys t [:authority :blocks :signature]))})

;; Now canonicalization works:
(canonical-pr-str (->Token auth blocks sig))
;;=> "#myapp/token {:authority {...} :blocks [...] :signature {...}}"
```

But here's the cross-platform problem: how does `register-type!` identify the type?

- On **JVM**: `Token` is a Java class. Dispatch via `instance?` or `class`.
- On **ClojureScript**: `Token` is a JS constructor. Dispatch via `instance?`.
- On **Babashka**: `Token` is **not a class**. It's a map with
  `{:type 'user/Token}` metadata. You can't use `instance?`. You need to check
  metadata: `(= 'user/Token (:type (meta x)))`.

So the registration mechanism itself needs a cross-platform type predicate:

```clojure
(canonical-edn/register-type!
  {:pred   #(instance? Token %)       ;; JVM/CLJS — won't work on Babashka
   :tag    'myapp/token
   :to-edn (fn [t] (into {} t))})

;; OR, platform-agnostic:
(canonical-edn/register-type!
  {:pred   (fn [x] (and (record? x)
                        (= 'myapp/Token (:type (meta x)))))  ;; Babashka-only
   :tag    'myapp/token
   :to-edn (fn [t] (into {} t))})
```

Neither of these is clean. You'd need reader conditionals or a platform-detecting
wrapper.

**Option C: Protocol-based extension**

Define a protocol that types can implement to opt into canonical serialization:

```clojure
(defprotocol ICanonicalEDN
  (canonical-tag [this])   ;; → 'myapp/token
  (canonical-form [this])) ;; → plain EDN value (map, vector, string, etc.)

(extend-type Token
  ICanonicalEDN
  (canonical-tag [_] 'myapp/token)
  (canonical-form [t] (into {} t)))
```

Pros: Idiomatic Clojure. Each type controls its own canonical representation.
Cons: Protocol dispatch relies on class/constructor, which **doesn't work on
Babashka** (records are maps, not classes). Also, protocols must be extended at
load time, not discovered dynamically.

**Option D: hasch's approach — incognito normalization**

hasch handles records via its "incognito" subsystem, which normalizes JVM class names
to a platform-neutral format: `my.ns.MyRecord` → `my.ns/MyRecord`. Both sides need
to know the type, but at least the naming is consistent.

This is clever but still requires a shared type registry for deserialization.

**Recommended approach for canonical-edn:**

The critical insight is that **signing does not require bidirectional encoding.**
(See §2.1 for the full argument — this section applies that principle specifically
to records.)
The data flow is:

```
Signer:    value  →  canonical bytes  →  sign(bytes, key)  →  signature
Verifier:  value  →  canonical bytes  →  verify(bytes, sig, key)  →  bool
```

Neither side reconstructs the original type from canonical bytes. Both sides just
need to produce identical bytes from the same logical value. This means
`canonical-pr-str` is a **one-way normalization function**, not a serialization
format. Many source representations can (and should) collapse to the same
canonical output — just like Unicode NFC normalization.

This makes the design simple:

**All map-like things → sorted map output.**

A `Token` record, an `array-map`, a `hash-map`, an `ordered-map`, and a
`sorted-map` that all contain `{:a 1 :b 2}` produce the identical canonical
bytes `{:a 1 :b 2}`. They ARE the same value. The container type is a runtime
implementation detail, not data.

```clojure
;; All of these produce exactly the same canonical output:
(canonical-pr-str {:b 2 :a 1})                    ;=> "{:a 1 :b 2}"
(canonical-pr-str (array-map :b 2 :a 1))          ;=> "{:a 1 :b 2}"
(canonical-pr-str (sorted-map :b 2 :a 1))         ;=> "{:a 1 :b 2}"
(canonical-pr-str (->Token 1 2))                   ;=> "{:a 1 :b 2}"
(canonical-pr-str (flatland.ordered.map/ordered-map :b 2 :a 1))
                                                   ;=> "{:a 1 :b 2}"
```

This is many-to-one and that's correct. Type identity, insertion order, and
comparator choice are all erased. The canonical form captures the VALUE.

**Security analysis of many-to-one mapping:**

Could an attacker exploit the type collapse? The answer is no, because:

1. **Signature forgery is prevented by crypto, not by types.** An attacker can't
   produce a valid signature without the private key, regardless of container type.

2. **Substitution of equal values is harmless.** If a `Token` record and a plain
   map produce the same canonical bytes, it's because they contain identical data.
   An attacker substituting one for the other hasn't changed any data — the
   signature covers the values, not the container.

3. **Type enforcement is an application concern, not a canonicalization concern.**
   If the application requires "this must be a Token record, not just any map,"
   that's a runtime type check performed separately from signature verification.
   Type identity doesn't travel over the wire in EDN anyway.

The one case to document clearly: **two values that are NOT `=` in Clojure can
theoretically produce identical canonical output if they differ only in type
metadata.** But for map-like types with the same keys and values, this is the
desired behavior.

**Implementation — one rule, no registry needed for the common case:**

```clojure
;; In emit:
(record? x) → (emit-map sb x)    ;; records ARE maps. Emit as maps. Done.
```

No Layer 0/Layer 1 distinction. No registry. No protocol. If the value satisfies
`map?`, it emits as a sorted map. If it satisfies `set?`, it emits as a sorted set.
If it satisfies `seq?`, it emits as a list. The concrete type is irrelevant.

The `record?` check still needs to come before `map?` in the dispatch, not because
we need to do anything different, but because on some platforms `record?` values
might need special handling to extract their entries. On Babashka, `(seq record)`
works fine (they're just maps). On JVM, `(into {} record)` works. On ClojureScript,
same. So in practice the `record?` check might not even be needed — `emit-map`
already handles anything that satisfies `map?` and `seq` returns map entries.

**What about opt-in tagged output for serialization use cases?**

If a future use case requires type-preserving round-trip serialization (not just
signing), that's a separate concern that can be layered on top:

```clojure
;; Hypothetical future extension, NOT needed for Kex signing:
(canonical-pr-str token {:tagged {Token 'myapp/token}})
;;=> "#myapp/token {:authority {...} :signature {...}}"
```

But this is explicitly out of scope for the signing use case. Keep it simple:
everything map-like becomes a sorted map. Everything set-like becomes a sorted set.
Type identity is erased. The canonical form is the value, nothing more.

**What about `deftype`?**

`deftype` is fundamentally different: it doesn't implement the map interface by
default, so there's no generic way to extract data from it. `(seq my-deftype)`
doesn't work. `(into {} my-deftype)` doesn't work.

**Canonical rule for `deftype`**: Always throw. No fallback. `deftype` is for
programming constructs (custom collections, protocol implementations), not for
data. If you need to canonicalize data held in a `deftype`, extract it into EDN
types first. This is the one place where "throw and move on" is genuinely correct.

**Summary: the normalization spectrum**

```
Source type          → Canonical output
─────────────────────────────────────────────
hash-map             → sorted map (by rank)
array-map            → sorted map (by rank)
sorted-map           → sorted map (by rank)
sorted-map-by        → sorted map (by rank, OUR comparator, not theirs)
ordered-map          → sorted map (by rank)
record               → sorted map (by rank) — type identity erased
hash-set             → sorted set (by rank)
sorted-set           → sorted set (by rank)
list                 → list (order preserved)
vector               → vector (order preserved)
lazy-seq             → list (realized, order preserved)
deftype              → THROW
host objects         → THROW
```

### 4.5 Map Implementations — Ordering vs. Identity

This is your ordered-map concern, and it goes deeper than it first appears.

#### 4.5.1 The Map Zoo

Clojure has multiple map implementations that all satisfy `map?`:

| Type | Ordering | When created | Canonical behavior |
|------|----------|-------------|-------------------|
| `PersistentHashMap` | Arbitrary (hash-based) | `{:a 1 :b 2}` with >8 keys | Sort by rank ✓ |
| `PersistentArrayMap` | **Insertion order** | `{:a 1 :b 2}` with ≤8 keys | Sort by rank — **loses insertion order** |
| `PersistentTreeMap` | Sorted by comparator | `(sorted-map :a 1 :b 2)` | Sort by rank — **ignores custom comparator** |
| `PersistentStructMap` | Base keys in declaration order | `(struct-map ...)` | Sort by rank — loses field order |
| `flatland.ordered.map` | Insertion order | `(ordered-map ...)` | Sort by rank — **loses insertion order** |

The key insight: **canonicalization intentionally destroys ordering information.**
This is the correct behavior for value semantics — `{:a 1 :b 2}` and `{:b 2 :a 1}`
are the same value, so they must have the same canonical form. But it means:

- If your application **relies on key ordering as semantic information**, you cannot
  simply put that data in a map and canonicalize it. You need to use a vector of pairs
  instead: `[[:first "do this"] [:then "do that"]]`.

- `sorted-map-by` with a **custom comparator** is particularly dangerous. The comparator
  defines which keys are considered "equal" (it might merge keys that `=` considers
  distinct). `canonical-pr-str` doesn't know about the comparator and will re-sort
  using its own total order, potentially producing a map with different effective
  contents than the user intended.

**Canonical rule**: All maps are sorted by `rank` comparator regardless of their
concrete implementation type. The concrete type is erased — `array-map`, `hash-map`,
`sorted-map`, `ordered-map` all produce identical canonical output for the same
key-value pairs. This follows from the one-way normalization principle (§2.1):
`canonical-pr-str` captures the *value*, not the container. If insertion order or
custom comparator order is semantically important to your application, represent it
explicitly as a vector of pairs: `[[:first "this"] [:then "that"]]`.

#### 4.5.2 Namespace-Qualified Map Syntax

Clojure 1.9+ introduced `#:ns{:a 1 :b 2}` as syntactic sugar for
`{:ns/a 1 :ns/b 2}`. This is controlled by `*print-namespace-maps*`.

**Canonical rule**: NEVER use namespace-qualified map syntax. Always emit the fully
expanded form `{:ns/a 1 :ns/b 2}`. The `#:ns{...}` form is a printing convenience,
not a value distinction.

### 4.6 Other Counterintuitive Types

#### 4.6.1 Lazy Sequences

```clojure
(canonical-pr-str (map inc [1 2 3]))
;; What should this produce?
```

Lazy seqs satisfy `seq?` and should canonicalize as lists: `(2 3 4)`. But there's
a trap: if the lazy seq is infinite (e.g., `(range)`), canonicalization will loop
forever. And if the lazy seq has **side effects**, canonicalization will trigger them.

**Canonical rule**: Lazy seqs are realized and emitted as lists. No protection against
infinite seqs (same as `pr-str` — caveat emptor). Users should avoid passing unrealized
infinite seqs to `canonical-pr-str`.

#### 4.6.2 Numeric Type Equivalence

```clojure
;; On JVM:
(= 1 1.0)    ;=> false
(== 1 1.0)   ;=> true
(= 1 1N)     ;=> true (!!!)

;; On ClojureScript:
(= 1 1.0)    ;=> true (!!!)
```

This is one of the most dangerous cross-platform differences. On JVM, `1` (Long) and
`1.0` (Double) are different values with different types, but `1` and `1N` (BigInt)
are equal. On ClojureScript, all numbers are IEEE 754 doubles, so `1` and `1.0` are
identical.

**Canonical rule**: `canonical-pr-str` writes what it sees. A Long `1` writes as `1`.
A Double `1.0` writes as `1.0`. If your data contains a Double `1.0` on ClojureScript
but a Long `1` on JVM for the "same" value, the canonical forms will differ. This is
**correct behavior** — the types are actually different, and the canonical form reflects
that. The responsibility is on the application to ensure consistent types across
platforms. The I-EDN constraint (§7.3) recommends: don't mix integer and float keys
in the same map or set.

#### 4.6.3 Metadata

```clojure
(def x (with-meta [1 2 3] {:source "db"}))
(= x [1 2 3])  ;=> true
```

Metadata is not part of value equality in Clojure. Two values that differ only in
metadata are `=`.

**Canonical rule**: Metadata is **completely ignored**. `canonical-pr-str` never emits
metadata (`^{:foo :bar}` syntax). If metadata is semantically important to your
application, it should be represented as regular data (e.g., as a key in a wrapping map).

#### 4.6.4 Regex Patterns

```clojure
(pr-str #"foo.*bar")
;; JVM:  "#\"foo.*bar\""
;; CLJS: "#\"foo.*bar\""
```

Regex syntax is not part of EDN. It uses the `#"..."` reader macro which is a Clojure
extension, not an EDN literal. There's no `edn/read-string` support for regexes.

**Canonical rule**: Throw on regex. If you need to serialize a regex pattern,
convert it to a tagged string first: `#re/pattern "foo.*bar"`.

#### 4.6.5 Functions, Vars, Atoms, Refs, Agents

These are identity/reference types, not values. `pr-str` produces things like
`#object[clojure.core$inc ...]` which is not valid EDN.

**Canonical rule**: Throw immediately. These are fundamentally non-serializable.

#### 4.6.6 Transient Collections, Java Collections, JS Arrays

```clojure
;; Someone passes a java.util.HashMap, java.util.ArrayList, or JS array
(canonical-pr-str (java.util.HashMap. {:a 1}))
;; Should this work?
```

**Canonical rule**: Throw on non-Clojure collection types. The caller must convert
to Clojure persistent data structures first. Rationale: Java's HashMap iteration order
is undefined and can vary between JVM versions. Even if we converted it, we'd need
to call `(into {} java-map)` first, and that conversion is the caller's responsibility.

#### 4.6.7 The `nil` Ambiguity in Collections

```clojure
(pr-str [nil])   ;=> "[nil]"
(pr-str [])      ;=> "[]"
;; These are fine, but watch out for:
(pr-str {:a nil})  ;=> "{:a nil}"
;; The KEY question: should {:a nil} and {} be treated the same?
```

**Canonical rule**: `{:a nil}` and `{}` are different values (the first has a key `:a`),
so they have different canonical forms. This is straightforward but worth noting because
some serialization formats (e.g., JSON with `null` values) treat this differently.

#### 4.6.8 Duplicate-Equivalent Keys

```clojure
;; Can you construct this?
{1 "int" 1N "bigint"}
;; On JVM: NO — 1 and 1N are = so this is a duplicate key.
;; But what about:
{1 "int" 1.0 "float"}
;; On JVM: YES — 1 and 1.0 are NOT = (different types)
;; On CLJS: ??? — 1 and 1.0 are = (same type, js number)
```

**Canonical rule**: Maps and sets MUST NOT contain keys/elements that are `=` to each
other on any target platform. If `canonical-pr-str` encounters such a case, it should
throw. This is part of the I-EDN constraint — keeping the input domain clean avoids
these cross-platform equivalence traps.

### 4.7 Whitespace

**Canonical whitespace rules** (minimal):
- **Separator**: exactly one space character (U+0020) between elements
- **No commas**: commas are never used (they're optional whitespace in EDN)
- **No newlines**: everything on one line
- **No trailing/leading whitespace**: output starts with first character of the value and ends
  with last
- **Empty collections**: `{}`, `#{}`, `[]`, `()`  — no internal whitespace

## 5. Proposed Canonical EDN Specification

### 5.1 Canonical Type Ordering

For sorting map keys and set elements, values are ordered by type priority, then within-type:

```
Priority 0: nil
Priority 1: boolean        (false < true)
Priority 2: number         (by numeric magnitude; integers before floats at same value)
Priority 3: string         (lexicographic — exact semantics TBD, see §7.6)
Priority 4: keyword        (lexicographic: namespace/name, unqualified < qualified)
Priority 5: symbol         (lexicographic: namespace/name, unqualified < qualified)
Priority 6: list           (element-by-element, shorter < longer if prefix matches)
Priority 7: vector         (element-by-element, shorter < longer if prefix matches)
Priority 8: set            (cardinality first, then sorted elements compared pairwise)
Priority 9: map            (cardinality first, then entries sorted by key, compared pairwise)
Priority 10: tagged literal (tag symbol first, then tagged value)
```

This is essentially `arrangement/rank` minus the character slot, plus explicit tagged
literal handling.

Note on the JCS parallel: JSON objects only have string keys, so JCS's sorting problem
is simpler — it just needs lexicographic string comparison. EDN maps can have **any
value** as a key (keywords, strings, numbers, vectors, even other maps). This makes our
sorting problem harder, but the JCS principle still applies: **pick one concrete rule and
nail it down.** Rather than leaving cross-type comparison vague, we define a total order
across all types. Two implementations that both follow this ordering will always produce
the same canonical output, regardless of what mix of types appears as map keys or set
elements. The arrangement library already embodies this principle — it was built precisely
so that `(sort rank any-heterogeneous-collection)` always produces the same result.

### 5.2 Canonical Rendering Rules

```
nil          → "nil"
false        → "false"
true         → "true"
integer      → decimal digits, optional leading "-", no "+", no leading zeros
float        → ECMAScript shortest-roundtrip; lowercase "e" for exponent, explicit "+"
               for positive exponent; always contains "." or "e" (append ".0" if needed)
bigint       → digits + "N" (only if > Long/MAX_VALUE or < Long/MIN_VALUE)
bigdecimal   → normalized digits + "M" (no trailing zeros)
string       → quoted with canonical escaping (see §4.3)
keyword      → ":" + name, or ":" + namespace + "/" + name
symbol       → name, or namespace + "/" + name
list         → "(" elements separated by " " ")"
vector       → "[" elements separated by " " "]"
set          → "#{" elements sorted by rank, separated by " " "}"
map          → "{" entries sorted by key rank, each key " " value, separated by " " "}"
tagged       → "#" tag " " canonical-value
```

### 5.3 Encoding

The canonical EDN text is encoded as UTF-8 with **no BOM** to produce the final byte sequence
for signing/hashing.

### 5.4 Conformance Profiles: CEDN-P (Portable) and CEDN-R (Rich)

Both external reviewers (Gemini, GPT) independently recommended defining explicit profiles
to resolve the recurring tension between cross-platform portability and EDN's full type
richness. This aligns with our own analysis — the document repeatedly flagged BigInt,
BigDecimal, ratios, and characters as "open questions" that all reduce to the same
underlying issue: they don't exist on JS.

#### CEDN-P — Portable Profile

The **minimum viable** canonical subset, guaranteed identical on JVM, ClojureScript,
Babashka, Scittle, and Node.js:

| Type | Canonical form | Notes |
|------|---------------|-------|
| nil | `nil` | |
| boolean | `true`, `false` | |
| integer (64-bit signed) | `42`, `-7`, `0` | No `N` suffix. Range: Long/MIN_VALUE to Long/MAX_VALUE |
| double (IEEE 754) | `0.1`, `1.0`, `1e+21` | ECMAScript shortest-roundtrip + EDN `.0` suffix (§4.1, §4.1.1) |
| string | `"hello"` | Canonical escaping (§4.3) |
| keyword | `:foo`, `:ns/bar` | |
| symbol | `foo`, `ns/bar` | |
| list | `(1 2 3)` | |
| vector | `[1 2 3]` | |
| set | `#{1 2 3}` | Sorted by rank |
| map | `{:a 1 :b 2}` | Keys sorted by rank |
| `#inst` | `#inst "2026-02-26T12:00:00.000Z"` | UTC, `Z` suffix, precision TBD (see §4.4.1) |
| `#uuid` | `#uuid "f81d4fae-..."` | Lowercase hex, 8-4-4-4-12 format |

**CEDN-P rejects** (throws error on canonicalization):
- BigInt (`42N`)
- BigDecimal (`3.14M`)
- Ratios (`22/7`)
- Character literals (`\a`)
- NaN, Infinity, -Infinity
- Records, deftypes, host objects
- Regex patterns
- Functions, vars, atoms, refs, agents
- Custom tagged literals (unless nested value is CEDN-P)
- Metadata (silently stripped, as in §4.6.3)

**Kex tokens MUST use CEDN-P.** This is the profile for cross-runtime signature
verification.

#### CEDN-R — Rich Profile

Extends CEDN-P with JVM-specific types for use cases where all participants run on the JVM
(e.g., JVM-to-JVM microservice authorization):

| Type | Canonical form | Notes |
|------|---------------|-------|
| BigInt | `9223372036854775808N` | Only when value exceeds 64-bit signed range |
| BigDecimal | `3.14M` | Normalized: no trailing zeros |
| Ratio | `22/7` | Numerator/denominator in lowest terms, denominator > 0 |
| Character | `\a`, `\newline` | Or reject — still under consideration (see §4.2) |

**CEDN-R tokens are explicitly non-portable.** Verifiers on JS runtimes cannot process
them. Tokens created under CEDN-R SHOULD carry a profile indicator so verifiers can
fail fast rather than misinterpreting data.

#### Profile versioning

The spec should define a version identifier (e.g., `cedn-p/v1`, `cedn-r/v1`) carried
as out-of-band metadata alongside signatures. This allows future spec evolution without
breaking existing signatures — a lesson learned from JCS's lack of versioning (§7.9).

Combined with the insight that all canonicalization choices are internal to the library
(§7.6), versioning makes every spec decision **reversible**. If `cedn-p/v1` uses
millisecond `#inst` precision and we later discover nanoseconds are better, that becomes
`cedn-p/v2`. A v1 verifier still validates v1 tokens; a v2 verifier handles both. No
existing signatures break. The version field turns spec decisions from "carved in stone"
to "carved in a specific, labeled stone."

This means the remaining open questions (§8: `#inst` precision, string comparison
semantics, custom tag policy) can be resolved pragmatically — pick whatever is easiest
to implement correctly across platforms for v1, knowing the version field is our escape
hatch if we need to change later.

#### Discussion: is the two-profile split worth it?

Two profiles is genuinely worse than one in terms of cognitive load and testing surface.
The value proposition of CEDN-R is narrow: it only matters when you need BigDecimal
precision or BigInt range AND all participants are JVM. In practice, Kex targets
cross-platform from day one, so CEDN-P is the real spec. CEDN-R exists primarily to
avoid the claim that "canonical EDN can't handle BigDecimal" — but it may never be
implemented. If implementation burden becomes an issue, CEDN-R can be deferred
indefinitely without affecting the core project.

### 5.5 Canonicalization Error Taxonomy

Both external reviewers recommended defining explicit error categories. For a
security-critical library, predictable failure modes matter as much as correct output.

| Error class | Trigger | Example |
|-------------|---------|---------|
| `unsupported-type` | Value has no canonical form in active profile | Record, deftype, regex, function, Java collection |
| `invalid-number` | Non-finite double | NaN, Infinity, -Infinity |
| `out-of-range` | Integer outside 64-bit signed range in CEDN-P | `9223372036854775808` (no `N` suffix) |
| `precision-exceeded` | `#inst` with precision beyond profile limit | Sub-millisecond instant (default mode, see §4.4.1) |
| `invalid-tag-form` | Tagged literal with non-canonicalizable value | `#my/tag (fn [x] x)` |
| `invalid-unicode` | Unpaired surrogate in string | `\uD800` without matching low surrogate |
| `duplicate-key` | Map contains keys that are `=` after canonicalization | `{1 "int" 1.0 "float"}` when integer 1 = float 1.0 |
| `duplicate-element` | Set contains elements that are `=` after canonicalization | `#{1 1.0}` |

All errors MUST throw/raise with structured data (ex-info on JVM, ExceptionInfo on CLJS)
including the error class, the offending value, and its position in the input structure.
Silent fallbacks or best-effort rendering are never acceptable — this is a cryptographic
function, not a pretty-printer.

## 6. Implementation Strategy

### 6.1 Architecture: `canonical-pr-str`

The entire project converges on one function: **`canonical-pr-str`** — a dedicated,
self-contained EDN writer that guarantees deterministic output for every supported type.

This is the missing counterpart that Nitor's blog post called for. Clojure has:
- `clojure.core/read-string` → unsafe reader (executes code)
- `clojure.edn/read-string` → safe reader (data only)
- `clojure.core/pr-str` → unsafe writer (ambient state, platform-dependent)
- **nothing** → safe, deterministic writer

`canonical-pr-str` fills that gap. It is NOT a wrapper around `pr-str`. It is NOT a
post-processor that parses and re-serializes. It is a **from-scratch, type-dispatched
writer** that directly emits canonical EDN text from Clojure values, with zero dependence
on `pr-str`, dynamic vars, or platform-specific print methods.

```clojure
(ns canonical-edn.core)

;; The primary API
(defn canonical-pr-str
  "Returns the canonical EDN string representation of x.
   Deterministic across all platforms. Throws on unsupported types."
  [x]
  ...)

(defn canonical-pr-bytes
  "Returns canonical EDN as a UTF-8 byte array. Suitable for signing/hashing."
  [x]
  (.getBytes (canonical-pr-str x) "UTF-8"))

;; Optional: the "filter" mode (parse existing EDN, re-emit canonically)
(defn canonicalize
  "Takes an EDN string, parses it, and re-serializes in canonical form."
  [edn-string]
  (canonical-pr-str (clojure.edn/read-string edn-string)))
```

Internally, `canonical-pr-str` is a recursive function that dispatches on type and
writes directly to a `StringBuilder` — no fipp document model, no intermediate
representation, no pretty-printing engine. Just type → string, recursively, with
sorting at collection boundaries.

```
┌─────────────────────────────────────────────────────┐
│              canonical-edn (library)                │
├─────────────────────────────────────────────────────┤
│  canonical-edn.core (.cljc)                        │
│   - canonical-pr-str : value → canonical string    │
│   - canonical-pr-bytes : value → UTF-8 bytes       │
│   - canonicalize : EDN string → canonical string   │
├─────────────────────────────────────────────────────┤
│  canonical-edn.write (.cljc)                       │
│   - emit-nil, emit-boolean                         │
│   - emit-integer, emit-float                       │
│   - emit-string, emit-keyword, emit-symbol         │
│   - emit-list, emit-vector, emit-set, emit-map     │
│   - emit-tagged                                     │
│   Each function: (emit-X value sb) → writes to sb  │
│   NO calls to pr-str anywhere.                      │
├─────────────────────────────────────────────────────┤
│  canonical-edn.order (.cljc)                       │
│   - rank : total-order comparator                   │
│   (adapted from arrangement, minus chars)           │
├─────────────────────────────────────────────────────┤
│  canonical-edn.number (.cljc + platform shims)     │
│   - format-double : IEEE 754 → canonical string     │
│   - format-long : long → canonical string           │
│   (JVM: delegates to JCS NumberToJSON; JS: native)  │
├─────────────────────────────────────────────────────┤
│  canonical-edn.platform (.clj / .cljs)             │
│   - JVM: NumberToJSON.serializeNumber() + .0 suffix │
│     (from cyberphone/jcs or erdtman dependency)     │
│   - JS: (.toString x) + .0 suffix                  │
│   - UTF-8 byte encoding                            │
│   - Type predicates for platform-specific types     │
└─────────────────────────────────────────────────────┘
```

The key insight from JCS applies here: wherever there's a choice (how to format a
number, how to escape a string, how to order keys), `canonical-pr-str` makes **one**
concrete decision and implements it directly, rather than delegating to platform
printing functions that might make a different choice.

### 6.2 Sketch of the Core Emit Logic

To make this concrete — here's what the heart of `canonical-pr-str` looks like.
Every type gets its own emit function. No `pr-str` anywhere. No dynamic vars consulted.

```clojure
(ns canonical-edn.write
  (:require [canonical-edn.order :as order]
            [canonical-edn.number :as num]))

(declare emit)

(defn- emit-nil [_sb] (.append sb "nil"))

(defn- emit-boolean [sb x] (.append sb (if x "true" "false")))

(defn- emit-integer [sb x]
  ;; Direct digit rendering. No pr-str.
  (.append sb (Long/toString x)))

(defn- emit-float [sb x]
  ;; ECMAScript shortest-roundtrip algorithm.
  ;; JVM: delegates to JCS NumberToJSON.serializeNumber() + EDN .0 suffix
  ;; JS: (.toString x) + EDN .0 suffix
  ;; See §4.1.1 for why this is NOT (.toString x) on JVM.
  (when (or (Double/isNaN x) (Double/isInfinite x))
    (throw (ex-info "Cannot canonicalize NaN or Infinity" {:value x})))
  (.append sb (num/format-double x)))

(defn- emit-string [sb x]
  ;; Our own escaping. Not pr-str's escaping.
  (.append sb \")
  (dotimes [i (count x)]
    (let [c (.charAt x i)]
      (case c
        \" (.append sb "\\\"")
        \\ (.append sb "\\\\")
        \newline (.append sb "\\n")
        \return (.append sb "\\r")
        \tab (.append sb "\\t")
        ;; Control chars → \uNNNN (lowercase hex, following JCS)
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c)))))
  (.append sb \"))

(defn- emit-keyword [sb x]
  ;; Direct rendering: ":" + namespace + "/" + name, or ":" + name
  (.append sb ":")
  (when-let [ns (namespace x)] (.append sb ns) (.append sb "/"))
  (.append sb (name x)))

(defn- emit-symbol [sb x]
  (when-let [ns (namespace x)] (.append sb ns) (.append sb "/"))
  (.append sb (name x)))

(defn- emit-seq [sb open close xs]
  (.append sb open)
  (loop [first? true, items xs]
    (when (seq items)
      (when-not first? (.append sb " "))
      (emit sb (first items))
      (recur false (rest items))))
  (.append sb close))

(defn- emit-set [sb x]
  ;; Sort by canonical total order, then emit
  (.append sb "#{")
  (let [sorted (sort order/rank x)]
    (loop [first? true, items sorted]
      (when (seq items)
        (when-not first? (.append sb " "))
        (emit sb (first items))
        (recur false (rest items)))))
  (.append sb "}"))

(defn- emit-map [sb x]
  ;; Sort entries by key using canonical total order, then emit
  (.append sb "{")
  (let [sorted (sort-by key order/rank x)]
    (loop [first? true, entries sorted]
      (when (seq entries)
        (let [[k v] (first entries)]
          (when-not first? (.append sb " "))
          (emit sb k)
          (.append sb " ")
          (emit sb v))
        (recur false (rest entries)))))
  (.append sb "}"))

(defn emit [sb x]
  (cond
    (nil? x)     (emit-nil sb x)
    (boolean? x) (emit-boolean sb x)
    (integer? x) (emit-integer sb x)
    (float? x)   (emit-float sb x)
    (string? x)  (emit-string sb x)
    (keyword? x) (emit-keyword sb x)
    (symbol? x)  (emit-symbol sb x)
    (map? x)     (emit-map sb x)     ;; records satisfy map? — type identity erased
    (set? x)     (emit-set sb x)
    (vector? x)  (emit-seq sb "[" "]" x)
    (list? x)    (emit-seq sb "(" ")" x)
    (seq? x)     (emit-seq sb "(" ")" x)
    ;; tagged literals (from edn/read-string with :default)
    (tagged-literal? x) (do (.append sb "#") (emit-symbol sb (:tag x))
                             (.append sb " ") (emit sb (:form x)))
    :else (throw (ex-info "Cannot canonicalize value: unsupported type"
                          {:value x :type (type x)}))))
```

This is ~80 lines for the complete type dispatch. The `emit-float` implementation
(the Ryu formatter) is the only complex part — everything else is straightforward
string building. The sorting at set/map boundaries uses `order/rank` (adapted from
arrangement). And the `throw` at the bottom is the Puget strict-mode principle:
unknown types are a hard error, never silently non-deterministic output.

Note what's **absent**: no `pr-str`, no `*print-length*`, no `*print-level*`, no
`*print-namespace-maps*`, no `print-method`, no multimethod dispatch, no dynamic
vars of any kind. No record registry. No type-specific dispatch. The entire function
is pure.

Note what's also absent: **no `record?` check.** Records satisfy `map?`, so they
fall through to `emit-map` and get sorted like any other map. Type identity is
erased. This is the one-way normalization principle at work — many source types
collapse to the same canonical output, and that's correct for signing.

### 6.2 What to Borrow From Each Existing Library

| Component | Source | What to take |
|-----------|--------|-------------|
| **Total ordering** | arrangement 2.1.0 | `rank` comparator (adapt: remove char slot, add tagged literal) |
| **Type taxonomy** | hasch magics | Priority numbering, chars-as-strings decision |
| **Strict mode** | Puget CanonicalPrinter | Throw on unknown types pattern |
| **Tagged literals** | hasch/incognito | Platform-neutral record normalization |
| **Number formatting** | RFC 8785 / ECMAScript | Shortest-roundtrip Ryu algorithm for doubles; `-0` → `0`; NaN/Inf → error |
| **String escaping** | RFC 8785 (adapted) | Lowercase `\uhhhh` for control chars; short forms for `\t \r \n`; pass-through Unicode |
| **Test suite structure** | JCS testdata/ | input/output/outhex directories + deterministic number test vectors |
| **Post-processor pattern** | RFC 8785 Appendix F | "Filter" mode: parse existing EDN → re-serialize canonically |
| **Signature-in-document** | RFC 8785 Appendix F | Embed signature in the EDN map itself; verify by dissoc + canonicalize |
| **Architecture** | Puget (protocol-based dispatch) | Type-dispatched rendering, but simpler (no fipp) |
| **Input domain restriction** | RFC 8785 / I-JSON | Define "I-EDN" subset: no chars, no NaN/Inf, no metadata, valid Unicode |

### 6.3 What Must Be Written From Scratch

1. **Cross-platform number formatter adapter** — while the core algorithm already exists
   (see §4.1.1), we need a thin Clojure wrapper that delegates to the right platform
   implementation: Java `NumberToJSON.serializeNumber()` on JVM, native `.toString()` on JS.
   Plus the EDN-specific `.0` suffix logic for whole-number doubles. The hard algorithmic
   work (Ryu/ECMAScript formatting) is solved by existing Apache 2.0 implementations.

2. **The spec itself** — a document analogous to RFC 8785 that precisely defines every
   rendering rule. Without this, implementations will diverge on edge cases.

3. **Conformance test suite** — following the JCS test suite structure:
   - `input/` directory with non-canonical EDN files
   - `output/` directory with expected canonical EDN text
   - `outhex/` directory with expected canonical UTF-8 bytes in hex notation
   - For number formatting specifically, we can **reuse** the JCS test vectors
     (100M deterministic IEEE 754 test cases with SHA-256 checksums), adapting only
     the expected output for EDN's `.0` suffix on whole-number doubles
   - Edge case files for cross-platform traps (chars, number types, Unicode, etc.)
   This is what makes cross-platform compatibility verifiable rather than aspirational.

### 6.4 Estimated Scope

| Component | Estimated LOC (.cljc) | Difficulty |
|-----------|----------------------|------------|
| Core API (canonical-pr-str, canonical-pr-bytes, canonicalize) | ~30 | Low |
| Emit functions (type-dispatched writer) | ~80 | Low |
| Ordering (adapted from arrangement) | ~100 | Low |
| Number formatting — JVM adapter + EDN `.0` suffix | ~20 | **Low** (see §4.1.1) |
| Number formatting — JVM Ryu/ECMAScript (copied from JCS, Java) | ~530 Java | **Already solved** |
| Built-in tag formatting (#inst from epoch-ms, #uuid lowercase) | ~50 | Medium |
| Platform shims (.clj + .cljs for number/date formatting, UTF-8) | ~70 | Medium |
| Conformance tests (reusing JCS number test vectors) | ~400 | Medium |
| **Total (Clojure)** | **~750** | |

The dramatic reduction from earlier estimates comes from recognizing that the Ryu/ECMAScript
number formatting algorithm — previously estimated at ~150 LOC **High** difficulty — is
already implemented and battle-tested in the JCS reference implementation. We either use it
as a dependency (0 LOC) or copy two Apache 2.0 Java files (~530 LOC Java, not Clojure).
The Clojure adapter on top is ~20 LOC: delegate to platform implementation, add `.0` suffix
for whole-number doubles. See §4.1.1 for the full analysis.

## 7. Lessons from RFC 8785 (JSON Canonicalization Scheme)

RFC 8785 is the closest prior art to what we're building. It went through years of
development and review, and the design choices — and the problems encountered — are
directly instructive for Canonical EDN.

### 7.1 Architectural Principle: Lean on Existing Platform Serialization

JCS's most important design decision was **not inventing new serialization rules**.
Instead, it defined canonical JSON as "the output of ECMAScript's `JSON.stringify()`
with sorted object properties." The rationale: ECMAScript already defines exactly how
every primitive type is serialized, and every browser and Node.js already implements it.
By building on that, JCS gets billions of hours of testing for free.

**Lesson for Canonical EDN**: We should identify an existing, well-defined serialization
behavior to build on rather than inventing from scratch. The problem is that Clojure has
no equivalent of `JSON.stringify()` — `pr-str` is the closest, but it's explicitly not
a serialization contract. This means we must either:

- **(a)** Define our own serialization rules from scratch (more work, but fully controlled), or
- **(b)** Pin to a specific version of `pr-str` behavior and test exhaustively (less work,
  more fragile), or
- **(c)** Build on arrangement's ordering + a custom number formatter that matches the
  ECMAScript Number serialization algorithm (hybrid approach — reuse what's proven for
  the hardest part)

Option (c) is attractive: for doubles/floats, we can define canonical EDN number
serialization as "the output of ECMAScript's `Number.prototype.toString()`" — the exact
same rule JCS uses. This means we can use V8/Node.js as a reference oracle for
cross-platform testing, and leverage existing JCS implementations (see §4.1.1) that
provide battle-tested Ryu-to-ECMAScript formatters for the JVM. The JavaScript side
requires zero special code — native `Number.toString()` IS the ECMAScript spec.

### 7.2 The "Post-Processor" Architecture

JCS explicitly recommends (Appendix F) implementing canonicalization as a **post-processor
filter** — a function that takes already-serialized JSON text, parses it, and re-serializes
it canonically. This decouples canonicalization from data construction entirely.

**Lesson for Canonical EDN**: We should support two modes:

1. **`write-string`**: takes a Clojure value, produces canonical EDN text directly
   (the common case for Kex)
2. **`canonicalize`**: takes an existing EDN string, parses it with `clojure.edn/read-string`,
   and re-serializes canonically (the "filter" mode, useful for canonicalizing EDN from
   external sources)

The filter mode is valuable because it means any system that produces EDN can have its
output canonicalized without changes to the producing system.

### 7.3 The I-JSON Constraint (Restricting the Input Domain)

JCS doesn't attempt to canonicalize all of JSON. It constrains input to the I-JSON subset
(RFC 7493), which notably requires:
- No duplicate property names
- All strings must be valid Unicode (no lone surrogates)
- Numbers must be representable as IEEE 754 doubles

By restricting the input domain, JCS eliminates entire classes of ambiguity.

**Lesson for Canonical EDN**: We should define a "Canonical EDN subset" (I-EDN?) that
restricts what values can be canonicalized:

- **No characters** — only strings (ClojureScript has no char type)
- **No BigDecimal / BigInt / ratios** in the "portable" profile (JS can't represent them)
- **No NaN / Infinity** — reject with error
- **No metadata** — stripped (not part of value semantics)
- **No regex patterns, vars, atoms, or other non-EDN types** — reject with error
- **Map keys and set elements must not contain equivalent-but-different types**
  (e.g., don't mix `1` and `1.0` as map keys — EDN spec warns about this)
- **Strings must be valid Unicode** — no lone surrogates

This gives us a clearly defined subset where canonicalization is provably deterministic,
rather than trying to handle every possible Clojure value.

### 7.4 Number Serialization: The Hardest Problem (and How JCS Solved It)

JCS spent more specification text on number serialization than on everything else combined.
The key insight: **delegate to the ECMAScript spec**, specifically `Number.prototype.toString()`
as defined in ECMA-262 Section 7.1.12.1, including the "Note 2" round-to-even enhancement.

The rules produce the **shortest decimal representation that round-trips** to the exact
same IEEE 754 double. The reference implementations are V8 (Google's JS engine) and the
Ryu algorithm.

The JCS test suite includes a **100 million** random IEEE 754 value test file, generated
deterministically from a seeded SHA-256 PRNG. The test structure is simple:
```
hex-ieee,expected-output\n
```

Edge cases that caused real implementation bugs (from Appendix B):
- `-0` → `0` (minus zero maps to zero)
- `5e-324` (minimum positive denormalized double)
- `1.7976931348623157e+308` (maximum double)
- `9007199254740992` (max safe integer — note: integer rendering, no decimal point!)
- `1e+23` vs `9.999999999999997e+22` (adjacent doubles, one gets scientific notation)
- `333333333.3333333` (rounding at the boundary)
- `1424953923781206.2` (round-to-even case)

**Lesson for Canonical EDN**: We should:

1. **Adopt the ECMAScript number serialization algorithm for doubles.** This is
   battle-tested, has well-defined edge case behavior, and gives us a reference oracle
   (just run `JSON.stringify(number)` in Node.js).

2. **But note the important difference**: In JSON, `0` and `0.0` and `0.00` are all the
   same thing (all IEEE 754 doubles). In EDN, `0` is a Long and `0.0` is a Double — they
   are different types. So we CANNOT blindly follow ECMAScript's rule of dropping the
   decimal point (e.g., `1.0` → `1`). We must always include the decimal point for
   floating-point values to preserve type distinction.

   Proposed rule: **EDN doubles always contain a decimal point.** Use the ECMAScript
   shortest-roundtrip algorithm, but if the result has no decimal point (e.g., `1e+23`),
   it stays as-is (exponent notation implies float). If the result is a plain integer
   like `1`, render it as `1.0`.

3. **Create a deterministic test vector file** following JCS's approach — IEEE 754 hex
   paired with expected canonical EDN string, generated from a seeded PRNG, with
   progressive SHA-256 checksums at 1K/10K/100K/1M lines.

### 7.5 String Serialization and Unicode

JCS's string rules are precise and minimal:
- Control chars (U+0000–U+001F): use `\uhhhh` with **lowercase hex**, except for
  `\b`, `\t`, `\n`, `\f`, `\r` which use the short form
- `\` → `\\`
- `"` → `\"`
- Everything else (including `/`, non-ASCII Unicode): pass through literally

Note: JCS uses **lowercase** hex in `\u` escapes. This is one of those tiny decisions
that, if left unspecified, will cause interoperability failures.

**Lesson for Canonical EDN**: EDN's escape repertoire differs from JSON:
- EDN has `\t`, `\r`, `\n`, `\\`, `\"` but NOT `\b`, `\f`
- EDN does not require `\/` escaping (and neither does JCS)
- EDN supports `\uNNNN` for Unicode escapes

Proposed rule: Use the JCS approach adapted for EDN's escape set. Control characters
not in EDN's escape set (`\b`, `\f`, and others) must use `\uNNNN` with **lowercase hex**
(matching JCS precedent for consistency).

### 7.6 Sorting: The String Comparison Question

**Key architectural insight**: The sort order is an *internal implementation detail* that
no application ever observes directly. Users call `canonical-pr-bytes` and get deterministic
bytes out. No application logic depends on `:apple` sorting before `:banana` — only on the
guarantee that the same input always produces the same output. The sort order is as internal
as the Ryu lookup tables or the string escaping rules.

This means the only real constraint is: every platform running our code must agree with
itself. Since we control the comparator (it's our `canonical-edn.order/rank` function,
not something the user supplies), we can pick whatever is easiest to implement identically
on JVM + JS + Babashka and never revisit it. The spec precision matters for interop between
*independent implementations*, but as the sole implementation (at least initially), we just
need to not disagree with ourselves. This same principle applies to most of the "hard" spec
decisions — exponent format, `.0` suffix, `#inst` digit count. They're all invisible to
applications, visible only to the library's cross-platform test suite.

JCS sorts JSON object properties by comparing property name strings as **UTF-16 code unit
arrays**. This was chosen because ECMAScript strings are internally UTF-16.

The RFC explicitly acknowledges that sorting in UTF-8 or UTF-32 would produce **different
orderings** for strings containing characters above U+FFFF (because UTF-16 uses surrogate
pairs), but notes this difference only matters for non-ASCII property names, which are rare
in practice.

**Lesson for Canonical EDN**: EDN keys are not just strings — they're keywords, symbols,
numbers, vectors, or any EDN value. So our sorting problem is fundamentally different
(and harder) than JCS's. We need arrangement's cross-type total ordering, not just string
comparison.

**Open discussion — string comparison semantics**:

Both external reviewers flagged this as needing a precise decision. The options:

| Option | Behavior | Pro | Con |
|--------|----------|-----|-----|
| **UTF-8 byte order** | Sort by raw encoded bytes | EDN is always transmitted as UTF-8; simplest mental model; no surrogate pair logic | JVM and JS strings are internally UTF-16; need to encode to UTF-8 to compare |
| **UTF-16 code unit order** | Sort by 16-bit code units | Matches JVM `String.compareTo` and JS `<` natively; zero custom comparator code | Non-JS implementations must convert to UTF-16 for sorting; JCS chose this and regretted it (§7.9) |
| **Unicode codepoint order** | Sort by scalar values (decode surrogates) | Semantically cleanest; what most people expect | Need custom comparator that handles surrogates on both JVM and JS; more complex than either byte or code-unit order |

The practical divergence between these three options occurs **only for strings containing
characters above U+FFFF** (astral plane: emoji, CJK Extension B, musical symbols, etc.).
For BMP-only strings (U+0000–U+FFFF), UTF-8 byte order = UTF-16 code unit order = Unicode
codepoint order. For typical authorization token content (ASCII keywords, UUIDs, timestamps,
short string values), the choice is moot.

Given that this only matters for edge cases and that JCS explicitly regretted choosing
UTF-16, we lean toward either UTF-8 byte order or Unicode codepoint order, but defer the
final decision to implementation phase when we can test the actual impact on arrangement's
comparator. The key commitment: **whichever we choose, it will be explicitly specified with
test vectors covering astral-plane characters, and the implementation will NOT rely on
host-platform default string comparison without verification.**

### 7.7 The Signature-in-Document Pattern

JCS describes an elegant signature scheme (Appendix F):
1. Create the JSON object
2. Canonicalize it
3. Sign the canonical form
4. **Add the signature as a property of the original JSON object**
5. Transmit the now-signed object as regular JSON

Verification reverses steps 2-3: parse, remove signature property, canonicalize remainder,
verify signature.

**Lesson for Canonical EDN**: This pattern is directly applicable to Kex. A signed
Biscuit-style token could be an EDN map that carries its own signature:

```clojure
{:authority {:facts [...] :rules [...]}
 :signature {:algorithm :ed25519
             :public-key "base64..."
             :value "base64..."}}
```

To verify: read the map, dissoc the `:signature` key, canonicalize the remainder,
verify signature against canonical bytes. This is cleaner than Kex's current separate
block+signature structure and worth considering as an API option.

### 7.8 Test Suite Structure

The JCS test suite (github.com/cyberphone/json-canonicalization/testdata) uses:
- `input/` — non-canonical JSON files
- `output/` — expected canonical JSON text
- `outhex/` — expected canonical output as hex bytes (UTF-8)
- A 100M-line number serialization test file with progressive SHA-256 checksums

The hex output files are crucial — they verify the **bytes**, not just the text.
This catches encoding issues that text comparison alone might miss.

**Lesson for Canonical EDN**: Adopt this exact structure:
- `input/` — EDN files with various formatting, orderings, whitespace
- `output/` — expected canonical EDN text
- `outhex/` — expected canonical UTF-8 bytes in hex
- `numbers.edn.gz` — deterministic IEEE 754 test vectors with checksums (adapted from
  JCS's 100M test file with EDN `.0` suffix applied)
- `edge-cases/` — cross-platform traps (chars vs strings, number formatting, etc.)

**Additional test strategies recommended by external reviewers:**

1. **Cross-runtime differential tests**: Same value fixtures canonicalized on JVM Clojure,
   Node.js ClojureScript, and Babashka. Byte outputs MUST match. This is the primary
   correctness signal — if all three produce identical bytes, the implementation is likely
   correct. Run as CI on every commit.

2. **Metamorphic tests**: Take a canonical EDN value, randomly permute the order of map
   keys and set elements (and nested maps/sets), canonicalize each permutation, and assert
   all produce identical byte output. This tests the sorting invariant without needing to
   know the "correct" output.

3. **Negative corpus**: A collection of inputs that MUST fail canonicalization with specific
   error classes (§5.5). Records, regex patterns, NaN, Infinity, functions, sub-millisecond
   instants (in default mode), oversized integers in CEDN-P, etc. Each entry specifies the
   expected error class. This prevents regressions where a future change accidentally makes
   an invalid input silently pass through.

4. **Quick CI corpus**: A small, uncompressed subset (~1000 entries) of the most critical
   edge cases from the full number test vectors, checked in directly for fast CI cycles.
   The full 100M test run is for periodic deep verification, not every commit.

### 7.9 What JCS Got Wrong (Or At Least Struggled With)

**UTF-16 sorting was a mistake** (in hindsight). The JCS authors chose UTF-16 code unit
comparison because it matched ECMAScript's internal string representation. But this means
non-JavaScript implementations must convert to UTF-16 just for sorting, even though the
final output is UTF-8. It would have been simpler to sort by UTF-8 byte order or Unicode
codepoint order.

**No versioning.** JCS has no version indicator. If the spec ever needs to change, there's
no way to distinguish v1 from v2 canonical output. The RFC punt on this ("it will be up to
the developer community") is not great for a cryptographic specification.

**Big number handling is a workaround.** Appendix D recommends wrapping big numbers in
JSON strings. This works but is ugly — it means the canonical form encodes `"055"` as a
string, not as a number, and schema knowledge is needed to interpret it. EDN's BigInt
(`N`) and BigDecimal (`M`) suffixes are architecturally superior to this hack.

**Lesson for Canonical EDN**: Include a version identifier in the spec (not necessarily in
every canonical output, but in the spec document and potentially as an optional header
tag). For string comparison semantics, see the open discussion in §7.6 — we are
explicitly learning from JCS's UTF-16 regret. And leverage EDN's richer type system
rather than stuffing everything into strings.

### 8.1 Pros of this approach

- **Human-readable signed payloads** — you can `println` what you signed and read it
- **EDN-native** — no Protobuf/CBOR/Transit dependency; stays in the Clojure data ecosystem
- **Inspectable** — diff two canonical forms to see exactly what changed
- **Small scope** — ~350 lines of Clojure library code (excluding tests and the JCS Java
  dependency for number formatting)
- **Composable with hasch** — use canonical-edn for inspectable token text, hasch for
  fast content-addressing, both from the same logical values

### Cons and risks

- **No existing standard** — we're creating a de facto spec, not implementing one. If
  others adopt a different canonical form, interop breaks.
- **Number formatting is a solved problem — but integration is needed** — getting shortest-roundtrip
  double formatting identical across JVM and V8/SpiderMonkey was once the hardest part of this
  project. However, the JCS project has battle-tested implementations in both Java (~500 LOC
  Ryu port, Apache 2.0) and JavaScript (zero code — native `Number.toString()` IS the spec).
  The remaining risk is in the EDN-specific adaptation (`.0` suffix for whole-number doubles)
  and in verifying conformance across all target platforms using the JCS 100M test vector set.
  See §4.1.1 for the full analysis.
- **BigDecimal/BigInt are JVM-specific** — resolved by the CEDN-P/CEDN-R profile split
  (§5.4). CEDN-P excludes them; CEDN-R includes them for JVM-only use cases. Kex uses CEDN-P.
- **Maintenance burden** — any spec bug or ambiguity becomes a security issue when used for
  signing. RFC 8785 went through years of review; we'd be maintaining this ourselves.
- **Performance** — sorting every map and set is O(n log n) per collection. For large
  deeply-nested structures this adds up. hasch's XOR approach avoids this.

### Open questions

**Resolved by profile split (§5.4):**

1. ~~**Should BigDecimal (`M`) and BigInt (`N`) be in the canonical subset?**~~
   → **Resolved**: Not in CEDN-P (portable). Available in CEDN-R (rich/JVM-only).
   Kex tokens use CEDN-P.

2. ~~**Should character literals be supported?**~~
   → **Resolved**: Not in CEDN-P. Under consideration for CEDN-R. See §4.2.

3. ~~**What about ratios (`22/7`)?**~~
   → **Resolved**: Not in CEDN-P. Available in CEDN-R. See §5.4.

4. ~~**How to handle NaN and Infinity?**~~
   → **Resolved**: Always rejected (throw `invalid-number`). Both profiles. See §5.5.

5. ~~**What about `#inst` timezone normalization?**~~
   → **Resolved**: Always UTC with `Z` suffix. See §4.4.1.

**Remaining open questions:**

6. **`#inst` precision level** — Should the fixed fractional digit count be 3
   (milliseconds), 6 (microseconds), or 9 (nanoseconds)? Milliseconds is the lowest
   common denominator but too coarse for some applications. Nanoseconds preserves full
   JVM precision but JS can't natively represent it. See discussion in §4.4.1.

7. **String comparison semantics** — UTF-8 byte order, UTF-16 code unit order, or Unicode
   codepoint order? Only diverges for astral-plane characters (U+10000+). Deferred to
   implementation phase. See discussion in §7.6.

8. **Should the spec define a version number?**
   Yes — carrying `cedn-p/v1` or similar as out-of-band metadata alongside signatures.
   Exact mechanism TBD. See §5.4.

9. **Custom tagged literals in CEDN-P** — Currently allowed if nested value is CEDN-P.
   Should they be rejected entirely in v1 to simplify the spec? The reviewers recommend
   accepting only `#inst` and `#uuid` in v1, rejecting all custom tags. This is the
   most conservative path.

## 9. Recommended Path Forward

### Phase 1: Spec-first (write the document)

Write the Canonical EDN specification as a standalone document before any code. Define every
rule precisely enough that two independent implementors would produce identical output. Include
a comprehensive set of test vectors.

### Phase 2: JVM reference implementation — `canonical-pr-str`

Build the reference implementation targeting JVM Clojure first. This is the primary platform
for Kex and server-side token creation/verification. The core is the `emit` function tree
shown in §6.2. Adapt arrangement's `rank` for ordering.

For number formatting, use the erdtman JCS library as a Maven dependency
(`[io.github.erdtman/java-json-canonicalization "1.1"]`) to get
`NumberToJSON.serializeNumber()` — a battle-tested ECMAScript-spec double formatter. Wrap
it in a Clojure function that adds the EDN `.0` suffix for whole-number doubles (see §4.1.1).
This gives us correct number formatting from day one with zero algorithmic risk.

### Phase 3: Cross-platform conformance testing

Verify that JVM and JS produce identical canonical output. For number formatting specifically,
the JCS project provides 100M deterministic test vectors (IEEE 754 hex → expected ECMAScript
string) with SHA-256 checksums. Adapt these for EDN's `.0` suffix rule and run against both
platforms. Use property-based testing (generate random doubles, verify JVM and JS produce
identical canonical strings) as a supplement.

The "boss fight" of cross-platform number formatting is already won by JCS — our job is
integration testing, not algorithm design.

### Phase 4: ClojureScript/Babashka parity

Extend to `.cljc` with platform shims. Verify full conformance test suite passes on all
target runtimes.

### Phase 5: Integration with Kex

Replace Kex's `canonical` + `pr-str` + `.getBytes` with `canonical-pr-bytes`. This is a
near-drop-in replacement:

```clojure
;; Before (Kex current):
(defn encode-block [block]
  (.getBytes (pr-str (canonical block)) StandardCharsets/UTF_8))

;; After:
(defn encode-block [block]
  (canonical-edn/canonical-pr-bytes block))
```

One function call. No `canonical` pre-pass. No `pr-str`. No `StandardCharsets` import.
The sorting, formatting, and encoding all happen inside `canonical-pr-bytes`.

## 10. External Review Consensus

The design document was reviewed independently by Gemini and GPT-4. Their full reviews
are in `docs/canonical-edn-analysis-gemini.md` and `docs/canonical-edn-analysis-gpt.md`.
Neither reviewer had access to the other's review. The convergence of their feedback is
itself a useful signal about the maturity of the design.

### What both reviewers confirmed

- **One-way normalization is correct.** The records→maps, metadata-stripping approach is
  the right architecture for cryptographic signing. No reviewer suggested bidirectional
  serialization.
- **Strict unknown-type rejection is correct.** Both emphasized that silent best-effort
  rendering is unacceptable in a security context.
- **Dedicated writer (not `pr-str`) is correct.** Both agreed that depending on Clojure's
  print system would be fragile.
- **The architecture is ready for implementation.** Neither reviewer identified fundamental
  design flaws. Both said "proceed" with specific items to lock down.

### What both reviewers independently recommended

These items were flagged by both reviewers without coordination — strong signal that they
matter:

1. **Define explicit profiles (CEDN-P / CEDN-R)** — Both proposed essentially the same
   portable/rich split. → Adopted in §5.4.

2. **Lock down string comparison semantics** — Both flagged the codepoint vs UTF-16
   ambiguity as a concrete interop risk. → Reframed as open discussion in §7.6.

3. **`#inst`: reject sub-precision, don't truncate** — Both said silent truncation in
   crypto context is dangerous. → Adopted in §4.4.1.

4. **Numeric lexical form needs one final normative statement** — Both wanted explicit
   rules for exponent case, sign, `.0` suffix, and `-0.0`. → Resolved by our JCS analysis:
   follow ECMAScript output exactly (lowercase `e`, explicit `+`, see §4.1.1), plus
   EDN `.0` suffix for whole-number doubles. Updated in §5.2.

### What the reviewers added that we hadn't considered

- **Conformance error taxonomy** (§5.5) — structured error classes for every failure mode.
  Both reviewers wanted this. Adopted.
- **Cross-runtime differential tests** — same fixtures on JVM, Node, Babashka; byte-identical
  output. Added to §7.8.
- **Metamorphic tests** — permute map/set order, assert identical output. Added to §7.8.
- **Negative corpus** — inputs that MUST fail with specific error classes. Added to §7.8.
- **Tagged literal policy needs a hard boundary** (GPT) — only `#inst` and `#uuid` in
  CEDN-P v1, reject custom tags. Added to open questions.

### Ideas considered and rejected

- **EDN→JSON→JCS path**: Considered in our earlier discussion as a way to reuse JCS
  implementations for number formatting. Both reviewers (and our own analysis) confirmed
  this is unworkable: EDN's non-string map keys, type-system richness, and set/list
  distinction make the impedance mismatch too severe. The JCS number formatting code can
  be reused directly (§4.1.1) without going through JSON as an intermediate format.
  Formally abandoned.

### Remaining disagreements / open items

Neither reviewer disagreed with the other on any substantive point. The only variance was
in emphasis: Gemini provided more detailed analysis of the JSON translation path (and its
fatal flaws), while GPT focused more on spec-level normative language and testing strategy.
Both are useful perspectives.

The remaining open design decisions (§8 Open Questions items 6-9: `#inst` precision,
string comparison semantics, version mechanism, and custom tag policy) were flagged by
both reviewers as needing resolution before implementation, but neither proposed a strongly
preferred answer — they correctly identified these as judgment calls that depend on practical
experience.
