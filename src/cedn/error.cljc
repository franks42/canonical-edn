(ns cedn.error
  "Error constructors for CEDN canonicalization errors: throw-* functions,
  which always throw (a trailing ! would mark a write; these only throw).

  All errors are ex-info with a map containing at minimum:
    :cedn/error  — keyword identifying the error class
    :cedn/value  — the offending value
    :cedn/path   — (optional) path to the value in the structure")

(defn throw-unsupported-type
  "Throw :cedn/unsupported-type for a value with no canonical form."
  ([value]      (throw-unsupported-type value nil))
  ([value path] (throw (ex-info "CEDN: unsupported type"
                                {:cedn/error :cedn/unsupported-type
                                 :cedn/value value
                                 :cedn/type  (type value)
                                 :cedn/path  path}))))

(defn throw-invalid-number
  "Throw :cedn/invalid-number for NaN or Infinity."
  ([value]      (throw-invalid-number value nil))
  ([value path] (throw (ex-info "CEDN: invalid number"
                                {:cedn/error :cedn/invalid-number
                                 :cedn/value value
                                 :cedn/path  path}))))

(defn throw-out-of-range
  "Throw :cedn/out-of-range for integers outside 64-bit signed range."
  ([value]      (throw-out-of-range value nil))
  ([value path] (throw (ex-info "CEDN: integer out of range"
                                {:cedn/error :cedn/out-of-range
                                 :cedn/value value
                                 :cedn/path  path}))))

(defn throw-duplicate-key
  "Throw :cedn/duplicate-key for maps with equal keys after normalization."
  ([key]        (throw-duplicate-key key nil))
  ([key path]   (throw (ex-info "CEDN: duplicate map key"
                                {:cedn/error :cedn/duplicate-key
                                 :cedn/value key
                                 :cedn/path  path}))))

(defn throw-duplicate-element
  "Throw :cedn/duplicate-element for sets with equal elements."
  ([elem]       (throw-duplicate-element elem nil))
  ([elem path]  (throw (ex-info "CEDN: duplicate set element"
                                {:cedn/error :cedn/duplicate-element
                                 :cedn/value elem
                                 :cedn/path  path}))))

(defn throw-invalid-unicode
  "Throw :cedn/invalid-unicode for strings with unpaired surrogates."
  ([value]      (throw-invalid-unicode value nil))
  ([value path] (throw (ex-info "CEDN: invalid unicode"
                                {:cedn/error :cedn/invalid-unicode
                                 :cedn/value value
                                 :cedn/path  path}))))

(defn throw-reader-error
  "Throw :cedn/invalid-tag-form for tagged-literal text that cannot be
  read back as exactly one canonical value."
  [tag text reason]
  (throw (ex-info (str "CEDN: cannot read #" tag " literal: " reason)
                  {:cedn/error  :cedn/invalid-tag-form
                   :cedn/tag    tag
                   :cedn/value  text
                   :cedn/reason reason})))

(defn throw-invalid-name
  "Throw :cedn/invalid-name for keywords/symbols whose namespace or name
  cannot be serialized as a distinct EDN token."
  ([value reason]      (throw-invalid-name value reason nil))
  ([value reason path] (throw (ex-info "CEDN: invalid keyword or symbol name"
                                       {:cedn/error  :cedn/invalid-name
                                        :cedn/value  value
                                        :cedn/reason reason
                                        :cedn/path   path}))))

(defn throw-invalid-tag-form
  "Throw :cedn/invalid-tag-form for tagged literals that can't be canonicalized."
  ([value]      (throw-invalid-tag-form value nil))
  ([value path] (throw (ex-info "CEDN: invalid tagged literal"
                                {:cedn/error :cedn/invalid-tag-form
                                 :cedn/value value
                                 :cedn/path  path}))))

;; ---------------------------------------------------------------------------
;; Deprecated names (until 1.6.0). A trailing ! marks a function that writes
;; state; these only throw, so they are now throw-*. The old names keep
;; their exact behaviour and will be removed in 2.0.
;; ---------------------------------------------------------------------------

(defn ^{:deprecated "1.6.0"} unsupported-type!
  "Deprecated since 1.6.0: use throw-unsupported-type."
  ([value] (throw-unsupported-type value))
  ([value path] (throw-unsupported-type value path)))

(defn ^{:deprecated "1.6.0"} invalid-number!
  "Deprecated since 1.6.0: use throw-invalid-number."
  ([value] (throw-invalid-number value))
  ([value path] (throw-invalid-number value path)))

(defn ^{:deprecated "1.6.0"} out-of-range!
  "Deprecated since 1.6.0: use throw-out-of-range."
  ([value] (throw-out-of-range value))
  ([value path] (throw-out-of-range value path)))

(defn ^{:deprecated "1.6.0"} duplicate-key!
  "Deprecated since 1.6.0: use throw-duplicate-key."
  ([key] (throw-duplicate-key key))
  ([key path] (throw-duplicate-key key path)))

(defn ^{:deprecated "1.6.0"} duplicate-element!
  "Deprecated since 1.6.0: use throw-duplicate-element."
  ([elem] (throw-duplicate-element elem))
  ([elem path] (throw-duplicate-element elem path)))

(defn ^{:deprecated "1.6.0"} invalid-unicode!
  "Deprecated since 1.6.0: use throw-invalid-unicode."
  ([value] (throw-invalid-unicode value))
  ([value path] (throw-invalid-unicode value path)))

(defn ^{:deprecated "1.6.0"} reader-error!
  "Deprecated since 1.6.0: use throw-reader-error."
  [tag text reason]
  (throw-reader-error tag text reason))

(defn ^{:deprecated "1.6.0"} invalid-name!
  "Deprecated since 1.6.0: use throw-invalid-name."
  ([value reason] (throw-invalid-name value reason))
  ([value reason path] (throw-invalid-name value reason path)))

(defn ^{:deprecated "1.6.0"} invalid-tag-form!
  "Deprecated since 1.6.0: use throw-invalid-tag-form."
  ([value] (throw-invalid-tag-form value))
  ([value path] (throw-invalid-tag-form value path)))
