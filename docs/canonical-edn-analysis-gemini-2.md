# Review of Updated `canonical-edn-analysis.md` (Gemini - Round 2)

**Overall Verdict:** The updates are fantastic. You have taken the theoretical concerns from the previous reviews and translated them into concrete, pragmatic specification rules. The document is now a highly robust, implementation-ready blueprint.

Here is how the updates specifically addressed the core concerns:

### 1. The Portability vs. Richness Tension
**Addressed via Profiles (Section 5.4):** 
Defining `CEDN-P` (Portable) and `CEDN-R` (Rich) completely solves the ambiguity around `BigInt`, `BigDecimal`, and characters. Mandating `CEDN-P` for Kex tokens ensures cross-platform signature verification will actually work. 
*Bonus points:* The addition of **Profile Versioning** (`cedn-p/v1`) is a brilliant escape hatch. As you noted, it makes every spec decision reversible without breaking existing signatures.

### 2. The Float vs. Integer Rule
**Addressed via `needs-decimal-point?` (Section 4.1.1):**
Your deep dive into how JCS solved number formatting (and the differences between Java's `Double.toString()` and ECMAScript) is excellent. The proposed Clojure snippet using `needs-decimal-point?` to append `.0` perfectly bridges the gap between JSON's unified number type and EDN's strict float/integer distinction. Reusing the `erdtman` Maven dependency for the JVM Ryu port is a very smart, low-risk starting point.

### 3. The `#inst` Truncation Trap
**Addressed via `precision-exceeded` (Section 4.4.1):**
Changing the rule to **reject and throw** if a source value has precision beyond the profile's limit is exactly the right move for a cryptographic context. It forces the developer to make an explicit decision about data loss *before* signing, rather than failing silently during verification.

### 4. String Sorting Semantics
**Addressed via Explicit Trade-off Analysis (Section 7.6):**
The new table comparing UTF-8 byte order, UTF-16 code unit order, and Unicode codepoint order correctly identifies the exact boundary where they diverge (astral-plane characters > U+FFFF). Deferring the final choice to the implementation phase—while committing to explicit test vectors for those edge cases—is a mature and safe architectural decision.

### 5. Predictable Failure Modes
**Addressed via Error Taxonomy (Section 5.5):**
Adding explicit error classes (`unsupported-type`, `out-of-range`, `precision-exceeded`) elevates this from a "script" to a "security library." Verifiers and signers now have a predictable contract for *how* canonicalization fails.

---

### Conclusion
You have successfully closed the loop on all the major architectural risks. The impedance mismatch with JSON is avoided, the cross-platform numeric formatting has a concrete implementation path, and the boundaries of the canonical subset are strictly defined. 

You are completely clear to move into **Phase 1 (Spec finalization)** and **Phase 2 (JVM reference implementation)**.