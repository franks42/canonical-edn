# canonical-edn (cedn) — project guide

**Read `context.md` first**: it is this project's full guide (design
decisions, current status, release procedure, open items).

## Current state (2026-09-28)

- Latest release: **v1.6.1** (Devin review fixes; see CHANGELOG).
  `com.github.franks42/cedn {:mvn/version "1.6.1"}`. main is clean, CI green.
- Used by signet (0.10.0) for canonical bytes, and `cedn/readers` to read
  them back (`#bytes`, strict `#inst` / `#uuid`).
- No work planned. Open items (context.md, "Open items"): `inspect`
  returns `:sha-256 nil` on CLJS (async SubtleCrypto); CEDN-R stays
  rejected; the deprecated `assert!` / `!` error helpers go in 2.0.
- Conventions shared with signet, nacljc and uuidv7: `!` only for lasting
  writes; docstrings say Pure / Impure / Throws `{:type …}`; commit and
  push only when the user asks; failing-first tests for bugs.
