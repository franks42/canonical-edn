# canonical-edn

Deterministic serialization of EDN values to UTF-8 byte sequences.
Same logical value produces the same bytes, always, on every Clojure platform.

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
com.github.franks42/cedn {:mvn/version "1.1.0"}
```

### Babashka (bb.edn)

```clojure
{:deps {com.github.franks42/cedn {:mvn/version "1.1.0"}}}
```

### nbb (nbb.edn)

nbb cannot read JAR files, so use a git dependency instead:

```clojure
{:deps {com.github.franks42/cedn
        {:git/url "https://github.com/franks42/canonical-edn"
         :git/tag "v1.1.0"
         :git/sha "03fe8e3"}}}
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

## Distribution

| Platform | Mechanism | Test |
|---|---|---|
| Clojure (JVM) | Maven JAR via `deps.edn` | `bb test:jar` |
| Babashka | Maven JAR via `bb.edn` | `bb test:jar` |
| nbb | Git dep via `nbb.edn` | `bb test:nbb-dep` |
| shadow-cljs | Source (classpath) | `bb test:cljs` |
| Scittle (browser) | CDN script tag via jsdelivr | `bb test:scittle-cdn` |

## License

Copyright (c) Frank Siebenlist. Distributed under the [Eclipse Public License v2.0](LICENSE).
