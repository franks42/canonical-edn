# Review of Updated `canonical-edn-analysis.md` (GPT - Round 2)

## Verdict

Yes — the updated document addresses the major concerns from my prior review and is now substantially more implementation-ready.

It moves from “good analysis” to “specification with guardrails,” especially around profile scoping, error behavior, and cross-platform numeric strategy.

---

## What Is Now Strongly Addressed

### 1) Profile Ambiguity → Resolved
The new **CEDN-P / CEDN-R split** in §5.4 is the single biggest improvement.

- CEDN-P is now clearly the cross-runtime contract.
- CEDN-R is explicitly non-portable and optional.
- Kex is explicitly pinned to CEDN-P.

This removes recurring ambiguity around BigInt/BigDecimal/ratio/char portability.

### 2) Failure Semantics → Resolved
The new **error taxonomy** in §5.5 is excellent for a cryptographic API.

- `unsupported-type`, `invalid-number`, `precision-exceeded`, etc. are explicit.
- Structured failures are required (no silent fallback).

This is exactly what deterministic signing systems need.

### 3) `#inst` Silent Data Loss Risk → Largely Resolved
The default behavior now rejects over-precision with `precision-exceeded` rather than silently truncating. That is the right security posture.

### 4) Number Formatting Path → Strongly Improved
§4.1.1 now contains a concrete, realistic strategy:

- JS uses native ECMAScript behavior.
- JVM uses a JCS-compatible serializer (erdtman/cyberphone path), not `Double.toString()` fixups.
- EDN-specific `.0` preservation is clearly identified.

This significantly de-risks cross-platform drift.

### 5) Versioning Strategy → Resolved
Profile/version labeling (`cedn-p/v1`, `cedn-r/v1`) is a major practical improvement. It gives you an escape hatch for future spec changes without invalidating old signatures.

---

## Remaining Gaps (Small but Important)

### A) String Ordering Is Still Open
§7.6 still leaves final string comparison semantics undecided (UTF-8 byte order vs UTF-16 code unit vs codepoint).

- This is now clearly documented, which is good.
- But canonical specs need one normative choice before implementation freeze.

**Recommendation:** choose one for `cedn-p/v1` now and add explicit astral-plane test vectors.

### B) `#inst` Fractional Digit Count Is Still Open
The document correctly requires fixed digit count and reject-on-overflow behavior, but digit precision (3/6/9) is still deferred.

**Recommendation:** lock this for `cedn-p/v1` before coding starts (or mark the implementation as draft until finalized).

### C) Minor Internal Tension on Custom Tags
`#my/tag <canonical-value>` behavior is mostly clear, but there is slight wording tension between permissive handling and profile restrictions.

**Recommendation:** add one normative rule table for tags per profile:

- CEDN-P: allowed/forbidden tags and value constraints
- CEDN-R: extension behavior

---

## Suggested Final Tightening Before Implementation

1. Freeze `cedn-p/v1` string ordering algorithm.
2. Freeze `cedn-p/v1` `#inst` fractional precision.
3. Add a normative “tag policy by profile” table.
4. Add 10–20 mandatory cross-runtime conformance vectors that cover:
   - astral Unicode ordering
   - edge IEEE-754 doubles
   - precision-exceeded instants
   - duplicate-key/duplicate-element failures

---

## Bottom Line

The update addresses the core concerns and materially improves spec quality. Remaining issues are now mostly **finalization decisions**, not architectural flaws.

You are in good shape to proceed into implementation once string-ordering and `#inst` precision are frozen for `cedn-p/v1`.
