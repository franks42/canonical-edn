# Review of `canonical-edn-analysis.md` (Gemini)

## Executive Summary

The analysis document is exceptionally well-reasoned. The central insight—that canonicalization for cryptographic signing is a **one-way normalization function** rather than a bidirectional serialization format—is the key breakthrough that makes this project feasible. By accepting that type identity (e.g., records vs. maps, sets vs. vectors in some contexts) can be erased in the canonical form, you avoid the massive complexity of cross-platform type registries.

The proposed architecture (a dedicated `canonical-pr-str` function, strict rejection of unknown types, and a total ordering comparator) is sound and ready for implementation.

---

## The "One-Way Encoding to JSON" Idea

You raised an interesting new idea: *If we only need a one-way canonical encoding, could we define a one-way encoding to JSON and reuse/leverage the canonical JSON (RFC 8785 / JCS) work after that?*

This is a very tempting architectural shortcut. Let's break down the pros and cons.

### How it would work
Instead of writing `canonical-pr-str`, you write `edn->json-ast`. You map EDN values to JSON-compatible structures, and then pass the result to an off-the-shelf JCS library (like `java-json-canonicalization` on the JVM, or a standard JCS package on npm).

### Pros
1. **Outsources the Hardest Problem (Numbers):** JCS libraries already implement the Ryu algorithm and ECMAScript shortest-roundtrip formatting. You wouldn't have to write or maintain cross-platform number formatters.
2. **Outsources String Escaping:** JCS handles Unicode, surrogate pairs, and control character escaping perfectly.
3. **Leverages Existing Audited Code:** JCS libraries have been heavily tested against the 100M-line test vectors.
4. **Ecosystem Compatibility:** If the final bytes are just Canonical JSON, tooling outside the Clojure ecosystem might have an easier time verifying signatures if they can reconstruct the JSON AST.

### Cons & Fatal Flaws
1. **The Non-String Map Key Problem:** JSON objects *only* allow string keys. EDN maps allow any value as a key (e.g., `{[1 2] :a}`). To map this to JSON, you have two choices:
   * *Stringify the keys:* `{"[1 2]": "a"}`. But wait—how do you stringify `[1 2]` deterministically? You would need a Canonical EDN stringifier to do it! This defeats the purpose.
   * *Represent maps as arrays of pairs:* `[[[1, 2], "a"]]`. If you do this, **JCS will not sort the map keys for you**, because JCS only sorts JSON object properties. You would still have to implement the `arrangement/rank` total ordering in Clojure before passing the array to JCS.
2. **Type Collapse Collisions:** If you map EDN to JSON, you lose type distinctions that might be semantically important.
   * Does `:a` (keyword) and `"a"` (string) both map to `"a"`? If so, `{:a 1}` and `{"a" 1}` produce the exact same canonical bytes.
   * Does `#{1 2}` (set) and `[1 2]` (vector) both map to `[1, 2]`?
   * While the document argues for many-to-one normalization (records -> maps), collapsing keywords into strings and sets into vectors might be *too* lossy, opening up substitution attacks where a user swaps a vector for a set and the signature still validates.
3. **To prevent collisions, you'd need a tagged JSON format:**
   * Keyword `:a` -> `{"@type": "keyword", "val": "a"}`
   * Set `#{1 2}` -> `{"@type": "set", "val": [1, 2]}`
   * Map `{[1] 2}` -> `{"@type": "map", "val": [[{"@type": "vector", "val": [1]}, 2]]}`
   * This becomes incredibly verbose, bloating the payload size (violating Requirement 7: Minimal output). And again, because maps become arrays of pairs, JCS won't sort them for you.

### Verdict on the JSON Idea
**Do not do it.** While it solves the number formatting problem, EDN's data model is too rich for JSON. Because EDN allows complex map keys, you cannot use JSON objects to represent EDN maps without stringifying the keys first. If you represent maps as JSON arrays, you lose JCS's automatic sorting, meaning you still have to write the complex sorting logic yourself. The impedance mismatch is too high.

---

## Key Strengths of the Current Proposal

1. **One-Way Normalization:** Treating records as maps and ignoring metadata is the correct approach for cryptographic signing. It aligns perfectly with how content-addressing and signature verification actually work in practice.
2. **Strict Mode (Throw on Unknown):** Refusing to guess how to serialize `deftype` or host objects (like `java.util.Date`) prevents silent non-determinism.
3. **Avoiding `pr-str`:** Recognizing that `pr-str` is a developer convenience tool and not a serialization contract is a mature architectural decision.
4. **Total Ordering:** Adapting `mvxcvi/arrangement` to provide a deterministic cross-type sort is exactly what is needed to handle EDN's heterogeneous collections.

---

## Areas for Improvement & Recommendations

### 1. Clarify the "Float vs. Integer" Rule
The document states: *"If the ECMAScript algorithm produces an integer-looking result (e.g., `1`), we must render it as `1.0` to preserve the double type."*
* **Recommendation:** Make this a hard, normative rule. Specify exactly what "integer-looking" means (i.e., matches the regex `^-?[0-9]+$`). Also, explicitly state that `-0.0` must canonicalize to `0.0` (as JCS does), but ensure it retains the `.0` so it doesn't become an integer `0`.

### 2. String Sorting Semantics (Codepoint vs. UTF-16)
Section 7.6 notes that JCS uses UTF-16 code unit comparison, but suggests Canonical EDN should use Unicode codepoint order.
* **Recommendation:** Be extremely precise here. Clojure's `compare` on the JVM uses `String.compareTo`, which is UTF-16 code unit order. ClojureScript's `compare` on strings also relies on JS string comparison (UTF-16). If you mandate Unicode codepoint order, you will have to write a custom string comparator that decodes surrogate pairs on both platforms. It is much easier and faster to mandate **UTF-16 code unit order** (matching the host platforms' native string comparison), even if it feels less "pure".

### 3. Define Explicit Profiles (Portable vs. Rich)
The document wrestles with whether to include `BigInt` (`N`), `BigDecimal` (`M`), and characters.
* **Recommendation:** Define two profiles in the spec:
  * **CEDN-P (Portable):** Only types that exist identically on JVM, JS, and Babashka. (nil, booleans, strings, keywords, symbols, vectors, lists, sets, maps, 64-bit integers, IEEE 754 doubles). Rejects BigInt, BigDecimal, and chars.
  * **CEDN-R (Rich):** Includes JVM-specific types.
  * For Kex and cross-platform signing, mandate CEDN-P.

### 4. The `#inst` Truncation Trap
Section 4.4.1 suggests truncating sub-millisecond precision to milliseconds.
* **Recommendation:** Silent truncation in a cryptographic context is dangerous. If a user signs a token with nanosecond precision, and the canonicalizer silently truncates it, the signature will cover the truncated value. If another system reads the token and *doesn't* truncate, hashes will mismatch.
* **Better Rule:** The canonicalizer MUST throw an error if the `#inst` value has sub-millisecond precision. Force the user to explicitly truncate their data before passing it to `canonical-pr-str`.

## Conclusion
The analysis is excellent and the proposed `canonical-pr-str` architecture is the right path forward. The JSON translation idea, while clever, introduces too much impedance mismatch with EDN's complex map keys and rich types. Stick to the native EDN emitter, lock down the numeric and string-sorting edge cases, and proceed with Phase 1 (Spec) and Phase 2 (JVM implementation).
