(ns cedn.schema
  "Hand-written predicates for CEDN-P type contracts.
  Simple recursive walk over the closed CEDN-P type set.")

;; --- Leaf predicates ---

(defn- finite-double?
  [x]
  (and (double? x)
       #?(:clj  (Double/isFinite x)
          :cljs (js/isFinite x))))

(defn- inst-value?
  [x]
  #?(:clj  (or (instance? java.util.Date x)
               (instance? java.time.Instant x))
     :cljs (instance? js/Date x)))

(defn- uuid-value?
  [x]
  #?(:clj  (instance? java.util.UUID x)
     :cljs (instance? cljs.core/UUID x)))

;; --- Core recursive predicate ---

(defn- cedn-p-valid?
  "Returns true if v is a valid CEDN-P value."
  [v]
  (cond
    (nil? v)     true
    (boolean? v) true
    (string? v)  true
    (keyword? v) true
    (symbol? v)  true
    (int? v)     true
    (double? v)  (finite-double? v)
    (inst-value? v) true
    (uuid-value? v) true
    (seq? v)     (every? cedn-p-valid? v)
    (vector? v)  (every? cedn-p-valid? v)
    (set? v)     (every? cedn-p-valid? v)
    (map? v)     (every? (fn [[k val]] (and (cedn-p-valid? k) (cedn-p-valid? val)))
                         v)
    :else        false))

;; --- Explain (depth-first, returns first error) ---

(declare cedn-p-explain)

(defn- explain-sequential
  "Walk a sequential (list/vector) checking each element. path-fn builds the path entry."
  [coll path]
  (reduce
   (fn [_ [i elem]]
     (when-let [err (cedn-p-explain elem (conj path i))]
       (reduced err)))
   nil
   (map-indexed vector coll)))

(defn- explain-set
  [s path]
  (reduce
   (fn [_ elem]
     (when-let [err (cedn-p-explain elem (conj path elem))]
       (reduced err)))
   nil
   s))

(defn- explain-map
  [m path]
  (reduce
   (fn [_ [k val]]
     (or (when-let [err (cedn-p-explain k (conj path k))]
           (reduced err))
         (when-let [err (cedn-p-explain val (conj path k))]
           (reduced err))))
   nil
   m))

(defn- cedn-p-explain
  "Returns nil if v is valid, or an error map for the first invalid sub-value."
  [v path]
  (cond
    (nil? v)        nil
    (boolean? v)    nil
    (string? v)     nil
    (keyword? v)    nil
    (symbol? v)     nil
    (int? v)        nil
    (double? v)     (when-not (finite-double? v)
                      {:cedn/error :cedn/invalid-number
                       :cedn/value v
                       :cedn/path  path})
    (inst-value? v) nil
    (uuid-value? v) nil
    (seq? v)        (explain-sequential v path)
    (vector? v)     (explain-sequential v path)
    (set? v)        (explain-set v path)
    (map? v)        (explain-map v path)
    :else           {:cedn/error :cedn/unsupported-type
                     :cedn/value v
                     :cedn/path  path}))

;; --- Public API ---

(defn schema-for
  "Returns the profile keyword for the given profile.
  Validates that the profile is known."
  [profile]
  (case profile
    :cedn-p :cedn-p
    (throw (ex-info (str "Unknown CEDN profile: " profile)
                    {:profile profile}))))

(defn valid?
  "Schema-level type check. Fast, no canonicalization."
  [profile value]
  (schema-for profile)
  (cedn-p-valid? value))

(defn explain
  "Schema-level explanation. Returns nil on success or an error map
  with :cedn/error, :cedn/value, and :cedn/path on first invalid sub-value."
  [profile value]
  (schema-for profile)
  (cedn-p-explain value []))
