;; ============================================================
;; CEDN — Core API Namespace Design
;; ============================================================
;;
;; This file defines the public API surface and internal module
;; structure for the Canonical EDN library.  It is a DESIGN
;; DOCUMENT, not runnable code — function bodies are stubs that
;; document contracts, arguments, and return values.
;;
;; Design principles:
;;   1. One primary entry point: canonical-bytes
;;   2. Everything else is a convenience built on that
;;   3. Cross-platform (.cljc) from day one
;;   4. Errors are ex-info with structured data, never nil/false
;;   5. Profile is explicit, never ambient state
;; ============================================================


;; ============================================================
;; Module structure
;; ============================================================
;;
;; cedn.core          Public API.  Most users only need this.
;; cedn.emit          Per-type emit functions (internal).
;; cedn.order         Rank comparator for sets/maps (internal,
;;                    but exposed for advanced use).
;; cedn.schema        Malli schemas for CEDN-P and CEDN-R type
;;                    contracts.
;; cedn.number        ECMAScript number formatting (internal).
;;                    Platform-specific: delegates to JCS Java
;;                    lib on JVM, native toString on JS.
;; cedn.error         Error constructors (internal).
;; cedn.gen           Generators for property-based testing.
;;                    Depends on malli.generator.
;;
;; Dependency graph (arrows = "depends on"):
;;
;;   cedn.core
;;     ├── cedn.emit
;;     │     ├── cedn.number
;;     │     ├── cedn.order
;;     │     └── cedn.error
;;     ├── cedn.schema
;;     └── cedn.order (re-exported)
;;
;;   cedn.gen
;;     └── cedn.schema
;;
;; No circular dependencies.  cedn.emit is the workhorse;
;; cedn.core is the thin public face.
;; ============================================================


(ns cedn.core
  "Canonical EDN — deterministic serialization for cryptographic use.

  Primary entry point:
    (canonical-bytes value)  → byte array

  Everything else is built on this."
  (:require [cedn.emit   :as emit]
            [cedn.order  :as order]
            [cedn.schema :as schema]
            [cedn.error  :as err]))


;; =============================================================
;; 1. Core canonicalization — the reason this library exists
;; =============================================================

(defn canonical-bytes
  "Canonicalize an EDN value to a UTF-8 byte array.

  This is the PRIMARY function.  The returned bytes are the input
  to sign, verify, and hash operations.

  Options:
    :profile  — :cedn-p (default) or :cedn-r
    :validate — if true, run schema validation before
                canonicalization (default: false, the canonicalizer
                itself will catch type errors regardless)

  Throws ex-info on any error (see cedn.error for classes).

  Examples:
    (canonical-bytes {:a 1 :b 2})
    ;=> #bytes[7B 3A 61 20 31 20 3A 62 20 32 7D]

    (canonical-bytes 22/7)
    ;=> throws {:cedn/error :cedn/unsupported-type ...}

    (canonical-bytes {:a 1N} {:profile :cedn-r})
    ;=> throws (1N fits in Long, must be plain integer)"
  ([value]
   (canonical-bytes value {}))
  ([value {:keys [profile validate]
           :or   {profile :cedn-p validate false}}]
   ;; 1. Optional: schema pre-validation
   ;; 2. Emit canonical text via cedn.emit/emit
   ;; 3. Encode to UTF-8 bytes
   ;; 4. Return byte array
   ))

(defn canonical-str
  "Canonicalize an EDN value to its canonical string representation.

  Same as canonical-bytes but returns a String instead of bytes.
  Useful for debugging, logging, and REPL exploration.
  NOT for cryptographic use — always use canonical-bytes for that.

  Same options as canonical-bytes."
  ([value]
   (canonical-str value {}))
  ([value opts]
   ;; Emit canonical text via cedn.emit/emit, return string
   ))


;; =============================================================
;; 2. Validation — is this value safe to canonicalize?
;; =============================================================

(defn valid?
  "Returns true if value consists exclusively of types allowed
  by the given profile.  This is a schema-level check only —
  it does NOT attempt canonicalization.

  Fast: no string building, no sorting.  Use this as a gate
  before expensive operations.

  Options:
    :profile — :cedn-p (default) or :cedn-r"
  ([value]
   (valid? value {}))
  ([value {:keys [profile] :or {profile :cedn-p}}]
   ;; Delegates to cedn.schema/valid?
   ))

(defn explain
  "Returns nil if value is valid for the given profile, or a
  humanized explanation map showing which sub-value violates
  the type contract.

  The explanation includes :cedn/path pointing to the offending
  sub-value.

  Options:
    :profile — :cedn-p (default) or :cedn-r"
  ([value]
   (explain value {}))
  ([value {:keys [profile] :or {profile :cedn-p}}]
   ;; Delegates to cedn.schema/explain
   ))

(defn assert!
  "Like valid?, but throws ex-info on failure instead of
  returning false.  Intended for use at system boundaries.

  Options:
    :profile — :cedn-p (default) or :cedn-r"
  ([value]
   (assert! value {}))
  ([value opts]
   ;; (when-let [explanation (explain value opts)]
   ;;   (throw (ex-info "CEDN type violation" explanation)))
   ))


;; =============================================================
;; 3. Inspection — what does the canonicalizer see?
;; =============================================================

(defn inspect
  "Canonicalize with full diagnostics.  Returns a map:

    {:status     :ok | :error
     :canonical  \"...\"          ; canonical string (nil on error)
     :bytes      #bytes[...]     ; canonical bytes  (nil on error)
     :sha-256    \"a1b2c3...\"   ; hex digest       (nil on error)
     :errors     [{...} ...]     ; error details    (nil on :ok)
     :profile    :cedn-p}

  Never throws.  Intended for REPL exploration and debugging."
  ([value]
   (inspect value {}))
  ([value {:keys [profile] :or {profile :cedn-p}}]
   ;; try/catch around canonical-bytes, assemble result map
   ))

(defn canonical?
  "Given an EDN string, returns true if it is already in
  canonical form for the given profile.

  Reads the string, re-canonicalizes, and checks byte equality.
  Returns false (not throws) if the string contains non-CEDN-P
  types.

  Options:
    :profile — :cedn-p (default) or :cedn-r"
  ([edn-str]
   (canonical? edn-str {}))
  ([edn-str {:keys [profile] :or {profile :cedn-p}}]
   ;; 1. read-string
   ;; 2. canonical-str
   ;; 3. (= edn-str result)
   ))


;; =============================================================
;; 4. Re-exported from cedn.order for advanced use
;; =============================================================

(def rank
  "Total ordering comparator over canonical EDN values.

  Returns negative, zero, or positive integer.
  Implements Section 5 of the CEDN specification.

  Can be used standalone:
    (sort rank [3 :a nil true \"b\"])
    ;=> (nil true 3 \"b\" :a)"
  order/rank)


;; =============================================================
;; cedn.emit — Internal emit protocol
;; =============================================================
;; (not in cedn.core, shown here for design completeness)

(comment

(ns cedn.emit
  "Per-type canonical text emission.

  The emit function dispatches on value type and writes canonical
  text to a StringBuilder.  This is the inner loop of
  canonicalization.

  Not a public API — use cedn.core/canonical-bytes."
  (:require [cedn.order  :as order]
            [cedn.number :as number]
            [cedn.error  :as err]))

(defn emit
  "Emit the canonical text of value into the StringBuilder sb.

  profile is :cedn-p or :cedn-r.

  Dispatches on type.  Each branch is a few lines:
    nil       → (.append sb \"nil\")
    boolean   → (.append sb (if v \"true\" \"false\"))
    integer   → range check, then (.append sb (str v))
    double    → cedn.number/format-double, then append
    string    → escape per §3.5, then append
    keyword   → emit colon, namespace/name
    symbol    → emit namespace/name
    list/seq  → emit elements in order
    vector    → emit elements in order
    set       → sort by rank, emit
    map       → sort entries by key rank, emit
    #inst     → format from epoch, emit
    #uuid     → lowercase hex, emit
    tagged    → emit tag symbol, space, emit value
    otherwise → throw unsupported-type"
  [sb profile value]
  ;; (cond
  ;;   (nil? value)     (emit-nil sb)
  ;;   (boolean? value) (emit-boolean sb value)
  ;;   (int? value)     (emit-integer sb profile value)
  ;;   (double? value)  (emit-double sb value)
  ;;   (string? value)  (emit-string sb value)
  ;;   (keyword? value) (emit-keyword sb value)
  ;;   (symbol? value)  (emit-symbol sb value)
  ;;   (seq? value)     (emit-seq sb profile value)
  ;;   (vector? value)  (emit-vector sb profile value)
  ;;   (set? value)     (emit-set sb profile value)
  ;;   (map? value)     (emit-map sb profile value)
  ;;   (inst? value)    (emit-inst sb profile value)
  ;;   (uuid? value)    (emit-uuid sb value)
  ;;   :else            (err/unsupported-type! value))
  )

) ;; end comment


;; =============================================================
;; cedn.order — Rank comparator
;; =============================================================

(comment

(ns cedn.order
  "Total ordering over canonical EDN values (Section 5).

  Exposed as cedn.core/rank for advanced use cases like
  building custom sorted collections.")

(defn type-priority
  "Returns the integer priority for a value's type.
  nil=0, boolean=1, number=2, string=3, keyword=4,
  symbol=5, list=6, vector=7, set=8, map=9, tagged=10."
  [value]
  ;; cond dispatch
  )

(defn rank
  "Comparator implementing the total order from Section 5.

  1. Compare by type-priority
  2. Within same type, compare by type-specific rules:
     - numbers: mathematical value (int before double if equal)
     - strings: lexicographic (see §5.3.4 open issue)
     - keywords/symbols: namespace then name
     - seqs/vectors: element-by-element, shorter first
     - sets: cardinality, then pairwise elements
     - maps: count, then keys, then values
     - tagged: tag symbol, then value"
  [a b]
  ;; ...
  )

) ;; end comment


;; =============================================================
;; cedn.number — ECMAScript number formatting
;; =============================================================

(comment

(ns cedn.number
  "ECMAScript-compatible double formatting per ECMA-262 §7.1.12.1,
  with EDN .0 suffix adaptation.

  On JS:  trivial — Number.prototype.toString() IS the spec.
  On JVM: delegates to a JCS implementation (erdtman or cyberphone)
          then applies .0 suffix rule.")

(defn format-double
  "Format a double to its canonical string representation.

  - NaN, Infinity, -Infinity → throws :cedn/invalid-number
  - -0.0 → \"0.0\"
  - Integer-looking output → appends \".0\"
  - Otherwise → ECMAScript shortest representation

  Cross-platform: identical output on JVM and JS."
  [x]
  #?(:clj  ;; (let [s (JCS/serializeNumber x)]
     ;;   (cond
     ;;     (or (.contains s \".\") (.contains s \"e\")) s
     ;;     :else (str s \".0\")))
     :cljs ;; (let [s (.toString x)]
     ;;   (if (or (str/includes? s \".\") (str/includes? s \"e\"))
     ;;     s
     ;;     (str s \".0\")))
     ))

) ;; end comment


;; =============================================================
;; cedn.schema — Malli schemas
;; =============================================================

(comment

(ns cedn.schema
  "Malli schemas for CEDN-P and CEDN-R type contracts.
  See cedn-p-schema.cljc for the full implementation."
  (:require [malli.core :as m]))

;; cedn-p-value — the portable schema (see cedn-p-schema.cljc)
;; cedn-r-value — extends cedn-p-value with BigInt, BigDecimal, ratio

(defn valid?
  "Schema-level type check.  Fast, no canonicalization."
  [profile value]
  ;; (m/validate (schema-for profile) value)
  )

(defn explain
  "Schema-level explanation.  Returns nil or error map."
  [profile value]
  ;; (m/explain (schema-for profile) value)
  )

) ;; end comment


;; =============================================================
;; cedn.error — Structured errors
;; =============================================================

(comment

(ns cedn.error
  "Error constructors for CEDN canonicalization errors.

  All errors are ex-info with a map containing at minimum:
    :cedn/error  — keyword identifying the error class
    :cedn/value  — the offending value
    :cedn/path   — (optional) path to the value in the structure")

(defn unsupported-type!
  "Throw :cedn/unsupported-type for a value with no canonical form."
  ([value]      (unsupported-type! value nil))
  ([value path] (throw (ex-info "CEDN: unsupported type"
                                {:cedn/error :cedn/unsupported-type
                                 :cedn/value value
                                 :cedn/type  (type value)
                                 :cedn/path  path}))))

(defn invalid-number!
  "Throw :cedn/invalid-number for NaN or Infinity."
  ([value]      (invalid-number! value nil))
  ([value path] (throw (ex-info "CEDN: invalid number"
                                {:cedn/error :cedn/invalid-number
                                 :cedn/value value
                                 :cedn/path  path}))))

(defn out-of-range!
  "Throw :cedn/out-of-range for integers outside 64-bit signed range."
  ([value]      (out-of-range! value nil))
  ([value path] (throw (ex-info "CEDN: integer out of range"
                                {:cedn/error :cedn/out-of-range
                                 :cedn/value value
                                 :cedn/path  path}))))

(defn duplicate-key!
  "Throw :cedn/duplicate-key for maps with equal keys after normalization."
  ([key]        (duplicate-key! key nil))
  ([key path]   (throw (ex-info "CEDN: duplicate map key"
                                {:cedn/error :cedn/duplicate-key
                                 :cedn/value key
                                 :cedn/path  path}))))

(defn duplicate-element!
  "Throw :cedn/duplicate-element for sets with equal elements."
  ([elem]       (duplicate-element! elem nil))
  ([elem path]  (throw (ex-info "CEDN: duplicate set element"
                                {:cedn/error :cedn/duplicate-element
                                 :cedn/value elem
                                 :cedn/path  path}))))

(defn invalid-unicode!
  "Throw :cedn/invalid-unicode for strings with unpaired surrogates."
  ([value]      (invalid-unicode! value nil))
  ([value path] (throw (ex-info "CEDN: invalid unicode"
                                {:cedn/error :cedn/invalid-unicode
                                 :cedn/value value
                                 :cedn/path  path}))))

(defn invalid-tag-form!
  "Throw :cedn/invalid-tag-form for tagged literals that can't be canonicalized."
  ([value]      (invalid-tag-form! value nil))
  ([value path] (throw (ex-info "CEDN: invalid tagged literal"
                                {:cedn/error :cedn/invalid-tag-form
                                 :cedn/value value
                                 :cedn/path  path}))))

) ;; end comment


;; =============================================================
;; cedn.gen — Generators for property-based testing
;; =============================================================

(comment

(ns cedn.gen
  "Property-based testing generators for CEDN values.

  Built on malli.generator — generates arbitrary values that
  conform to the CEDN-P or CEDN-R type contract."
  (:require [cedn.schema :as schema]
            [malli.generator :as mg]))

(defn gen-cedn-p
  "Returns a test.check generator that produces arbitrary CEDN-P
  values.  Useful for property-based testing of canonicalization.

  Key properties to test:
    1. Idempotency:  canonical(v) == canonical(read(canonical(v)))
    2. Determinism:  canonical(v) always produces the same bytes
    3. Valid EDN:    (edn/read-string (canonical-str v)) succeeds
    4. Cross-platform: same bytes on JVM, JS, and Babashka"
  ([]    (gen-cedn-p {}))
  ([opts]
   ;; (mg/generator schema/cedn-p-value opts)
   ))

(defn gen-cedn-r
  "Returns a generator for CEDN-R values (JVM only)."
  ([]    (gen-cedn-r {}))
  ([opts]
   ;; (mg/generator schema/cedn-r-value opts)
   ))

) ;; end comment


;; =============================================================
;; Summary: What a user sees
;; =============================================================
;;
;; Typical usage — just two functions:
;;
;;   (require '[cedn.core :as cedn])
;;
;;   ;; Sign a Kex block:
;;   (let [block {:cedn/version "cedn-p.v1"
;;                :authority [[:right :resource "file1" :read]]
;;                :created   #inst "2026-02-26T12:00:00.000Z"}
;;         bytes (cedn/canonical-bytes block)]
;;     (sign keypair bytes))
;;
;;   ;; Verify:
;;   (let [block  (deserialize token)
;;         bytes  (cedn/canonical-bytes block)
;;         valid? (verify pubkey bytes signature)]
;;     ...)
;;
;;   ;; Debug:
;;   (cedn/inspect {:a 1 :b ##NaN})
;;   ;=> {:status :error
;;   ;    :errors [{:cedn/error :cedn/invalid-number
;;   ;              :cedn/value ##NaN
;;   ;              :cedn/path [:b]}]
;;   ;    :profile :cedn-p}
;;
;;   ;; Validate before signing:
;;   (cedn/valid? user-supplied-data)
;;   ;=> false
;;   (cedn/explain user-supplied-data)
;;   ;=> {:cedn/error :cedn/unsupported-type
;;   ;    :cedn/value 22/7
;;   ;    :cedn/path [:threshold]}
;;
;; That's it.  Five functions cover 95% of use cases:
;;   canonical-bytes  — the core (sign/verify)
;;   canonical-str    — debugging
;;   valid?           — fast gate
;;   explain          — error diagnostics
;;   inspect          — full REPL diagnostics
