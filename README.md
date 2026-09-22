# canonical-edn

Deterministic serialization of EDN values to UTF-8 byte sequences.
Same logical value produces the same bytes, always, on every Clojure platform.

> **Requires JDK 19 or newer on the JVM.** `Double/toString` only became
> guaranteed shortest-round-trip in JDK 19 ([JDK-4511638]); on JDK 17 and
> older, cedn would emit extra digits for some doubles, so its bytes would
> not match those produced on JavaScript, Babashka or nbb — and signatures
> would not verify across platforms. Rather than fail silently, **cedn
> throws on load** on an unsupported JVM. Babashka, nbb, ClojureScript and
> Scittle are unaffected.

[JDK-4511638]: https://bugs.openjdk.org/browse/JDK-4511638

**Why?** EDN maps and sets have no defined order, so `(pr-str {:b 2 :a 1})` can produce `"{:b 2, :a 1}"` or `"{:a 1, :b 2}"` depending on the runtime. This breaks cryptographic signing — you can't verify a signature if the serializer reorders keys. CEDN defines a canonical form with deterministic key ordering, so `(canonical-bytes value)` is stable across JVM, ClojureScript, Babashka, nbb, and browser.

```clojure
(require '[cedn.core :as cedn])

(cedn/canonical-str {:b 2 :a 1})          ;=> "{:a 1 :b 2}"
(cedn/canonical-bytes {:b 2 :a 1})        ;=> UTF-8 bytes of "{:a 1 :b 2}"
(cedn/canonical? "{:a 1 :b 2}")           ;=> true
(cedn/valid? {:a [1 "two" :three]})       ;=> true
(sort cedn/rank [3 :a nil true "b"])       ;=> (nil true 3 "b" :a)
```

Zero production dependencies beyond Clojure itself.

## Installation

### deps.edn

```clojure
com.github.franks42/cedn {:mvn/version "1.5.0"}
```

### Babashka (bb.edn)

```clojure
{:deps {com.github.franks42/cedn {:mvn/version "1.5.0"}}}
```

### nbb (nbb.edn)

nbb cannot read JAR files, so use a git dependency instead:

```clojure
{:deps {com.github.franks42/cedn
        {:git/url "https://github.com/franks42/canonical-edn"
         :git/tag "v1.5.0"
         :git/sha "eac1b3a"}}}
```

### Scittle (Browser)

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.js"
        type="application/javascript"></script>
<script type="application/x-scittle"
        src="https://cdn.jsdelivr.net/gh/franks42/canonical-edn@main/dist/cedn.cljc"></script>
<script type="application/x-scittle">
(require '[cedn.core :as cedn])
(println (cedn/canonical-str {:b 2 :a 1}))
;; => {:a 1 :b 2}
</script>
```

## What cedn rejects

Canonicalization is a cryptographic function, so cedn errors rather than
emitting bytes that are wrong, ambiguous, or platform-dependent. Every
error is `ex-info` carrying `:cedn/error`, `:cedn/value` and, where
known, `:cedn/path`.

| `:cedn/error` | Cause |
|---|---|
| `:cedn/unsupported-type` | No canonical form: ratio, BigDecimal, regex, function, … |
| `:cedn/invalid-number` | `##NaN`, `##Inf`, `##-Inf` |
| `:cedn/out-of-range` | `#inst` year outside 0000–9999 (an integer beyond 64 bits is a `BigInt`, reported as `:cedn/unsupported-type`) |
| `:cedn/invalid-unicode` | String contains an unpaired UTF-16 surrogate |
| `:cedn/invalid-name` | Keyword/symbol name that would serialize as a different value, e.g. `(symbol "nil")`, `(keyword "a b")` |
| `:cedn/duplicate-key` / `:cedn/duplicate-element` | Two keys or elements with identical canonical form, e.g. a `Date` and an `Instant` for the same moment |
| `:cedn/invalid-tag-form` | Unreadable `#inst`, `#uuid` or `#bytes` text |
| `:cedn/unsupported-profile` / `:cedn/unknown-profile` | Any profile other than `:cedn-p` |

`:cedn-p` is the only implemented profile. `:cedn-r` (spec §4 — BigInt,
BigDecimal, ratios, characters) is rejected rather than quietly treated
as `:cedn-p`.

## API

```clojure
(require '[cedn.core :as cedn] '[clojure.edn :as edn])

(cedn/canonical-bytes value)        ; → UTF-8 bytes; the primary entry point
(cedn/canonical-str value)          ; → the same text, for debugging
(cedn/canonical? edn-string)        ; → is this text already canonical?

(cedn/valid? value)                 ; → true/false, no canonicalization
(cedn/explain value)                ; → nil, or a map describing the first problem
(cedn/assert! value)                ; → nil, or throws

(cedn/inspect value)                ; → {:status :ok/:error :canonical … :sha-256 … :errors …}
                                    ;   never throws; :sha-256 is nil on JS
(sort cedn/rank values)             ; → the spec's total ordering
cedn/readers                        ; → strict #inst / #uuid / #bytes readers
cedn/version                        ; → "1.5.0"
```

Reading back canonical text needs `cedn/readers`, which preserve
precision and reject malformed literals:

```clojure
(edn/read-string {:readers cedn/readers} "#inst \"2025-02-26T12:00:00.123456789Z\"")
;; => java.time.Instant, nanoseconds intact (the default reader gives a
;;    millisecond java.util.Date and loses the last six digits)
```

## Command line

`cedn` is a Babashka script that canonicalizes EDN on stdin. Download it
from the [latest release](https://github.com/franks42/canonical-edn/releases/latest):

```bash
curl -fsSL -o cedn https://github.com/franks42/canonical-edn/releases/download/v1.5.0/cedn-v1.5.0
chmod +x cedn
```

```bash
cedn --edn '{:b 2 :a 1}'                  # {:a 1 :b 2}
cat config.edn | cedn | sha256sum         # content hash of a config
cedn --input config.edn --output out.edn  # canonicalize a file
```

It reads zero or more top-level forms and writes each canonically, one
per line. `--objects` joins them with single spaces and no trailing
newline instead. Exit codes: `0` success, `1` malformed EDN or I/O
error, `2` bad flags. A closed downstream pipe (`cedn | head`) is not an
error.

## Distribution

| Platform | Mechanism | Test |
|---|---|---|
| Clojure (JVM) | Maven JAR via `deps.edn` | `bb test:jar` |
| Babashka | Maven JAR via `bb.edn` | `bb test:jar` |
| nbb | Git dep via `nbb.edn` | `bb test:nbb-dep` |
| shadow-cljs | Source (classpath) | `bb test:cljs` |
| Scittle (browser) | CDN script tag via jsdelivr | `bb test:scittle-cdn` |
| `cedn` CLI | GitHub release asset | `bb test:cli`, `bb test:cli-release` |

Every platform runs in CI on each push, because the guarantee this
library makes is that they all produce the same bytes.

## Specification

[`docs/cedn-spec.md`](docs/cedn-spec.md) is the normative spec: the type
rules (§3), the total ordering (§5), byte encoding (§6), errors (§7) and
the security properties (§8). `test/cedn/cedn-p-compliance-vectors.edn`
holds golden vectors any implementation can check itself against.

## Development

```bash
bb test:all     # every platform, plus lint and format
bb test:jvm     # or: test:bb, test:nbb, test:cljs, test:scittle, test:cli
bb lint         # clj-kondo
bb fmt          # cljfmt
```

## License

Copyright (c) Frank Siebenlist. Distributed under the [Eclipse Public License v2.0](LICENSE).
