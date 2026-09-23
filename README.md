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

## Idempotence: many EDNs in, one EDN out — and it stays put

This is the property the whole library rests on.

A value has endlessly many EDN spellings. `edn-1`, `edn-2`, … `edn-n`
differ in whitespace and indentation, key order in maps, element order
in sets, commas, comments and `#_` discards, map type (array/hash/sorted),
records vs plain maps, metadata, number spelling (`1.50`, `1.5000`,
`-0.0`), case in `#uuid` and `#bytes`, and timezone in `#inst`
(`2020-01-01T00:00:00Z`, `2020-01-01`, `2020-01-01T09:00:00+09:00`,
`2019-12-31T19:00:00-05:00` are all the same instant). They carry the
same information, so they all canonicalize to one representative,
`cedn-0`:

```
  edn-1  ┐
  edn-2  ├──  cedn  ──▶  cedn-0
  …      │
  edn-n  ┘
```

`cedn-0` is not a separate format. It is ordinary EDN — any EDN reader
reads it — so it is one of the `edn-i` itself. Canonicalizing it again
therefore lands on the same representative:

```
  cedn(edn-i)   =  cedn-0        for every equivalent edn-i
  cedn(cedn-0)  =  cedn-0        because cedn-0 is one of them
```

That is what makes `cedn` an *idempotent projection* (spec §1.2.3):
`cedn ∘ cedn = cedn`. Running the pipeline twice, or ten times, changes
nothing after the first pass.

Four inputs that share no byte-level resemblance — different key order,
set order, commas, comments, a discard, metadata, `1.50` vs `1.5000`,
mixed-case UUIDs, and the same instant written in UTC, as a bare date,
in Tokyo and in New York:

```clojure
{:b 2 :a 1 :s #{2 1} :d 1.50
 :t #inst "2020-01-01T00:00:00Z"
 :u #uuid "F81D4FAE-7DEC-11D0-A765-00A0C91E6BF6"}

{:a 1, :b 2, :d 1.5, :s #{1 2},
 :t #inst "2020-01-01",
 :u #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"}

{:s #{1 2}   ; Tokyo offset, same instant
 :t #inst "2020-01-01T09:00:00+09:00" :b 2 :a 1 :d 1.5000
 :u #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6" #_ :dropped}

^{:meta "gone"} {:a 1 :b 2 :d 1.5 :s #{2 1}
                 :t #inst "2019-12-31T19:00:00-05:00"
                 :u #uuid "F81d4fAE-7dEC-11d0-A765-00a0C91E6bF6"}
```

All four canonicalize to the same text, and so hash to the same digest
(`963d50b968a2b944…`):

```
{:a 1 :b 2 :d 1.5 :s #{1 2} :t #inst "2020-01-01T00:00:00.000000000Z" :u #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"}
```

```bash
$ cat any-of-them.edn | cedn | sha256sum          # one digest for all four
$ cat any-of-them.edn | cedn | cedn | cedn        # identical to one pass
```

The first pass burns off everything that is presentation rather than
information: key and element order, whitespace, comments, sorted-vs-hash
maps, records, metadata, `-0.0`, trailing zeros, uppercase hex. What
remains is already a fixed point, which is exactly what
`(cedn/canonical? s)` tests.

Two conditions on the round trip:

- **Read with `cedn/readers`.** The default EDN reader turns a
  nanosecond `#inst` into a millisecond `java.util.Date` and drops the
  last six digits, so re-canonicalizing after it is not a no-op. The
  CLI already uses them.
- **JavaScript holds milliseconds.** Re-canonicalizing a nanosecond
  `#inst` *on a JS runtime* yields `…123000000Z`. JS output is a fixed
  point on JS; JVM output with sub-millisecond precision is not, if you
  run it through JS.

## One value with everything in it

This is the compliance vector in
[`test/cedn/cedn-p-compliance-vectors.edn`](test/cedn/cedn-p-compliance-vectors.edn)
— every CEDN-P type in one map, with its canonical form. Every platform
must produce exactly these bytes, and the file is what another
implementation checks itself against.

```clojure
{:nil-val     nil
 :bools       [true false]
 :ints        [0 1 -1 42 -7 9007199254740991]
 :doubles     [3.141592653589793 0.5 -1.5 0.1 1e-7]
 :strings     ["" "hello" "café" "a\tb" "a\nb" "a\"b" "a\\b" "\u0000"]
 :keywords    [:foo :ns/bar]
 :symbols     ['foo 'ns/bar]
 :collections {:list '(1 2 3) :map {:b 2 :a 1} :set #{3 1 2} :vec [1 2 3]}
 :empties     [() [] #{} {}]
 :nested      {:a [1 #{:x :y}] :b "hello"}
 :inst        #inst "1970-01-01T00:00:00.000Z"
 :uuid        #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"
 :bytes       (byte-array [0xde 0xad 0xbe 0xef])}
```

canonicalizes to (one line, shown wrapped):

```
{:bools [true false] :bytes #bytes "deadbeef" :collections {:list (1 2 3)
 :map {:a 1 :b 2} :set #{1 2 3} :vec [1 2 3]} :doubles [3.141592653589793
 0.5 -1.5 0.1 1e-7] :empties [() [] #{} {}] :inst
 #inst "1970-01-01T00:00:00.000000000Z" :ints [0 1 -1 42 -7 9007199254740991]
 :keywords [:foo :ns/bar] :nested {:a [1 #{:x :y}] :b "hello"} :nil-val nil
 :strings ["" "hello" "café" "a\tb" "a\nb" "a\"b" "a\\b" "\u0000"]
 :symbols [foo ns/bar] :uuid #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"}
```

Map keys are sorted, the set is ordered, `#inst` gains nine fractional
digits, the byte array becomes lowercase hex, and the NUL character is
escaped while `café` stays literal UTF-8.

## Installation

### deps.edn

```clojure
com.github.franks42/cedn {:mvn/version "1.5.1"}
```

### Babashka (bb.edn)

```clojure
{:deps {com.github.franks42/cedn {:mvn/version "1.5.1"}}}
```

### nbb (nbb.edn)

nbb cannot read JAR files, so use a git dependency instead:

```clojure
{:deps {com.github.franks42/cedn
        {:git/url "https://github.com/franks42/canonical-edn"
         :git/tag "v1.5.1"
         :git/sha "359a9bc"}}}
```

### Scittle (Browser)

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.8.33/dist/scittle.js"
        type="application/javascript"></script>
<script type="application/x-scittle"
        src="https://cdn.jsdelivr.net/gh/franks42/canonical-edn@v1.5.1/dist/cedn.cljc"></script>
<script type="application/x-scittle">
(require '[cedn.core :as cedn])
(println (cedn/canonical-str {:b 2 :a 1}))
;; => {:a 1 :b 2}
</script>
```

Pin a release tag as above.  `@main` also works but follows unreleased
development, and bytes you sign should come from a fixed version.

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
cedn/version                        ; → "1.5.1"
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
curl -fsSL -o cedn https://github.com/franks42/canonical-edn/releases/download/v1.5.1/cedn-v1.5.1
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
| nbb | Git dep via `nbb.edn` | `bb test:nbb-dep`, `bb test:nbb-git` |
| shadow-cljs | Source (classpath) | `bb test:cljs` |
| Scittle (browser) | CDN script tag via jsdelivr | `bb test:scittle-cdn [ref]` |
| `cedn` CLI | GitHub release asset | `bb test:cli`, `bb test:cli-release` |

Every platform runs in CI on each push, because the guarantee this
library makes is that they all produce the same bytes.  The published
artifacts — the CDN bundle at the tag the README pins, and the README's
nbb git coordinates — need the network, so `bb test:published` checks
them in a weekly scheduled workflow instead.

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
