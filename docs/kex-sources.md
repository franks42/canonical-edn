# Kex / Biscuit Research — Sources

Sources referenced across our discussion sessions on capability-based authorization,
Kex, Biscuit, canonical EDN, and cross-platform cryptography.

---

## Kex

- **Kex repository** — https://github.com/serefayar/kex
- **"Reconstructing Biscuit in Clojure"** (Seref R. Ayar, Feb 19, 2026) — https://serefayar.substack.com/p/reconstructing-biscuit-in-clojure

### Seref Ayar's blog series

- "De-mystifying Agentic AI: Building a Minimal Agent Engine from Scratch with Clojure" (Jan 28, 2026) — https://serefayar.substack.com/p/minimal-agent-engine-from-scratch-with-clojure
- "OCapN and Structural Authority in Agentic AI" — https://serefayar.substack.com/p/ocapn-and-structural-authority-in-agentic-ai
- "Interpreting OCapN Principles in Cloud-Native Agentic AI Architectures" — https://serefayar.substack.com/p/interpreting-ocapn-principles-in-cloud-native-agentic-ai

---

## Biscuit

- **Biscuit specification** — https://doc.biscuitsec.org/reference/specifications
- **Biscuit introduction** — https://doc.biscuitsec.org/getting-started/introduction
- **Authorization policies** — https://doc.biscuitsec.org/getting-started/authorization-policies
- **Datalog reference** — https://doc.biscuitsec.org/reference/datalog
- **Biscuit website** — https://biscuitsec.org
- **Biscuit repo (Eclipse)** — https://github.com/eclipse-biscuit/biscuit
- **Biscuit repo (original)** — https://github.com/biscuit-auth/biscuit
- **Eclipse project page** — https://projects.eclipse.org/proposals/eclipse-biscuit
- **Biscuit Python docs (datalog examples)** — https://python.biscuitsec.org/datalog
- **HN discussion** — https://news.ycombinator.com/item?id=38635617
- **HN discussion (2025)** — https://news.ycombinator.com/item?id=47080297

---

## Canonical EDN / Serialization

### EDN specification

- **EDN format** — https://github.com/edn-format/edn
- **EDN format site** — http://edn-format.org

### Puget (canonical pretty-printer)

- **Puget repo** — https://github.com/greglook/puget
- **Puget API docs** — https://cljdoc.org/d/mvxcvi/puget/1.3.3/doc/readme
- **Clojars** — https://clojars.org/mvxcvi/puget

### Arrangement (deterministic sort)

- **Arrangement docs** — https://cljdoc.org/d/mvxcvi/arrangement/2.0.0/doc/readme
- **Clojars** — https://clojars.org/mvxcvi/arrangement

### Hasch (cross-platform content hashing)

- **Hasch repo** — https://github.com/replikativ/hasch
- **Hasch API docs** — https://cljdoc.org/d/io.replikativ/hasch/0.3.94/doc/readme
- **Clojars** — https://clojars.org/io.replikativ/hasch
- **Hasch core source** — https://raw.githubusercontent.com/replikativ/hasch/main/src/hasch/core.cljc
- **Hasch platform.clj** — https://github.com/replikativ/hasch/blob/master/src/hasch/platform.clj
- **Hasch intro blog** — http://functional-nomads.github.io/clojure/clojurescript/2014/04/18/hasch-intro.html

### Multiformats

- **Multiformats (Clojure)** — https://cljdoc.org/d/mvxcvi/multiformats/0.2.1/doc/readme

### JSON Canonicalization (RFC 8785 — parallel approach)

- **RFC 8785** — https://datatracker.ietf.org/doc/html/rfc8785
- **JSON Canonicalization repo** — https://github.com/cyberphone/json-canonicalization
- **Interactive reference** — https://cyberphone.github.io/ietf-json-canon
- **Java JCS implementation** — https://github.com/erdtman/java-json-canonicalization
- **Titanium JCS** — https://github.com/filip26/titanium-jcs
- **Rust serde_json_canonicalizer** — https://docs.rs/serde_json_canonicalizer/latest/serde_json_canonicalizer/

---

## Cross-Platform Cryptography

### fluree.crypto (reference cross-platform pattern)

- **fluree.crypto repo** — https://github.com/fluree/fluree.crypto
- **Ed25519 source** — https://github.com/fluree/fluree.crypto/blob/main/src/fluree/crypto/ed25519.cljc
- **SHA-2 source** — https://github.com/fluree/fluree.crypto/blob/main/src/fluree/crypto/sha2.cljc
- **AES source** — https://github.com/fluree/fluree.crypto/blob/main/src/fluree/crypto/aes.cljc
- **JWS source** — https://github.com/fluree/fluree.crypto/blob/main/src/fluree/crypto/jws.cljc
- **Fluree DB** — https://github.com/fluree/db

### Web Crypto API

- **W3C Web Crypto spec** — https://w3c.github.io/webcrypto/
- **MDN Web Crypto docs** — https://developer.mozilla.org/en-US/docs/Web/API/Web_Crypto_API
- **Modern algorithms proposal (Ed25519)** — https://wicg.github.io/webcrypto-modern-algos/
- **Synchronous Web Crypto discussion** — https://discourse.wicg.io/t/synchronous-webcrypto/628/
- **Cloudflare Workers Web Crypto** — https://developers.cloudflare.com/workers/runtime-apis/web-crypto/
- **Node.js Web Crypto** — https://nodejs.org/api/webcrypto.html

### Other crypto libraries surveyed

- **Buddy (JVM-only)** — https://github.com/funcool/buddy
- **Geheimnis (replikativ)** — https://github.com/replikativ/geheimnis
- **Uniformity** — https://github.com/skinkade/uniformity
- **noble-ed25519 (JS)** — https://github.com/paulmillr/noble-ed25519
- **hiredman/ed25519** — https://github.com/hiredman/ed25519
- **clj-crypto** — https://github.com/macourtney/clj-crypto

### Ed25519 reference

- **Ed25519 reference implementation** — http://ed25519.cr.yp.to/python/ed25519.py

---

## Babashka

- **Babashka v1.12.215 release** — https://github.com/babashka/babashka/releases/download/v1.12.215/babashka-1.12.215-linux-amd64-static.tar.gz
- **Clojure Ed25519 deps discussion** — https://ask.clojure.org/index.php/8725/deps-support-newer-private-file-formats-types-such-ed25519

---

## Datascript / Datomic (comparison context)

- **Replikativ Datahike** — https://github.com/replikativ/datahike

---

## Related projects (from Seref Ayar)

- **Ayatori** — https://github.com/serefayar/ayatori
- **Cljengine** — https://github.com/serefayar/cljengine

---

## Other references

- **Clojure official** — https://clojure.org
- **Biscuit authorization tutorial (Space and Time)** — https://www.spaceandtime.io/blog/biscuit-authorization
- **Clojurians log (crypto discussion)** — https://clojurians-log.clojureverse.org/clojure/2021-05-19
- **Securing JSON objects with HMAC** — https://connect2id.com/blog/how-to-secure-json-objects-with-hmac
