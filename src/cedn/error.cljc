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
