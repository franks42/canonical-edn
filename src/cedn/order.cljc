(ns cedn.order
  "Total ordering over canonical EDN values (Section 5).

  Exposed as cedn.core/rank for advanced use cases like
  building custom sorted collections.")

(defn type-priority
  "Returns the integer priority for a value's type.
  nil=0, boolean=1, number=2, string=3, keyword=4,
  symbol=5, list=6, vector=7, set=8, map=9,
  tagged(bytes/inst/uuid)=10."
  [value]
  (cond
    (nil? value)     0
    (boolean? value) 1
    (number? value)  2
    (string? value)  3
    (keyword? value) 4
    (symbol? value)  5
    (seq? value)     6
    (vector? value)  7
    (set? value)     8
    (map? value)     9
    :else            10))

(declare rank)

(defn- compare-strings
  "Compare two strings by Unicode codepoint order.
  Equivalent to comparing UTF-8 byte sequences."
  [^String a ^String b]
  #?(:bb
     (let [alen (.length a)
           blen (.length b)]
       (loop [ai 0 bi 0]
         (let [a-end (>= ai alen)
               b-end (>= bi blen)]
           (cond
             (and a-end b-end) 0
             a-end -1
             b-end  1
             :else
             (let [ac (.codePointAt a ai)
                   bc (.codePointAt b bi)
                   c  (compare ac bc)]
               (if (zero? c)
                 (recur (+ ai (Character/charCount ac))
                        (+ bi (Character/charCount bc)))
                 c))))))
     :clj
     (let [ai (.iterator (.codePoints a))
           bi (.iterator (.codePoints b))]
       (loop []
         (let [a-has (.hasNext ai)
               b-has (.hasNext bi)]
           (cond
             (and (not a-has) (not b-has)) 0
             (not a-has) -1
             (not b-has)  1
             :else
             (let [ac (.nextInt ai)
                   bc (.nextInt bi)
                   c  (compare ac bc)]
               (if (zero? c)
                 (recur)
                 c))))))
     :cljs
     (let [aa (js/Array.from a)
           ba (js/Array.from b)
           alen (.-length aa)
           blen (.-length ba)
           limit (min alen blen)]
       (loop [i 0]
         (if (< i limit)
           (let [ac (.codePointAt (aget aa i) 0)
                 bc (.codePointAt (aget ba i) 0)
                 c  (compare ac bc)]
             (if (zero? c)
               (recur (inc i))
               c))
           (compare alen blen))))))

#?(:clj
   (defn- exact-decimal
     "Exact BigDecimal value of an integer or double (no rounding)."
     [x]
     (if (int? x)
       (java.math.BigDecimal/valueOf (long x))
       (java.math.BigDecimal. (double x)))))

(defn- compare-numbers
  "Compare two numbers by exact mathematical value.
  When equal, integer ranks before double.

  On the JVM, integers are never widened to double: distinct longs
  above 2^53 would compare equal, and their order would then depend
  on the input order (§5.3.3).  On JS every number is a double, so
  plain comparison is already exact."
  [a b]
  (let [a-int? (int? a)
        b-int? (int? b)
        cmp #?(:clj  (cond
                       (and a-int? b-int?) (compare (long a) (long b))
                       (or a-int? b-int?)  (compare (exact-decimal a) (exact-decimal b))
                       :else               (compare (double a) (double b)))
               :cljs (compare a b))]
    (if (zero? cmp)
      ;; Same mathematical value: int < double
      (cond
        (and a-int? (not b-int?)) -1
        (and (not a-int?) b-int?)  1
        :else                      0)
      cmp)))

(defn- compare-named
  "Compare keywords or symbols by namespace then name.
  Absent namespace sorts before any present namespace."
  [a b]
  (let [ans (namespace a)
        bns (namespace b)]
    (cond
      (and (nil? ans) (nil? bns))
      (compare-strings (name a) (name b))

      (nil? ans) -1
      (nil? bns)  1

      :else
      (let [nsc (compare-strings ans bns)]
        (if (zero? nsc)
          (compare-strings (name a) (name b))
          nsc)))))

(defn- compare-sequential
  "Compare sequences element-by-element. Shorter first."
  [a b]
  (let [sa (seq a)
        sb (seq b)]
    (loop [sa sa sb sb]
      (cond
        (and (nil? sa) (nil? sb)) 0
        (nil? sa) -1
        (nil? sb)  1
        :else
        (let [c (rank (first sa) (first sb))]
          (if (zero? c)
            (recur (next sa) (next sb))
            c))))))

(defn- compare-sets
  "Compare sets: by cardinality first, then pairwise sorted elements."
  [a b]
  (let [cc (compare (count a) (count b))]
    (if (not (zero? cc))
      cc
      (compare-sequential (sort rank a) (sort rank b)))))

(defn- compare-maps
  "Compare maps: by entry count, then sorted keys, then sorted values."
  [a b]
  (let [cc (compare (count a) (count b))]
    (if (not (zero? cc))
      cc
      (let [ak (sort rank (keys a))
            bk (sort rank (keys b))
            kc (compare-sequential ak bk)]
        (if (not (zero? kc))
          kc
          (let [av (map #(get a %) ak)
                bv (map #(get b %) bk)]
            (compare-sequential av bv)))))))

(defn- compare-bytes
  "Compare byte arrays lexicographically by unsigned byte value."
  [a b]
  (let [alen #?(:clj (alength ^bytes a) :cljs (.-length a))
        blen #?(:clj (alength ^bytes b) :cljs (.-length b))
        limit (min alen blen)]
    (loop [i 0]
      (if (< i limit)
        (let [ab (bit-and #?(:clj (aget ^bytes a i) :cljs (aget a i)) 0xff)
              bb (bit-and #?(:clj (aget ^bytes b i) :cljs (aget b i)) 0xff)
              c  (compare ab bb)]
          (if (zero? c)
            (recur (inc i))
            c))
        (compare alen blen)))))

(defn- tag-kind
  "Returns a keyword for the tagged-literal kind, for sub-ordering.
  :bytes < :inst < :uuid (alphabetical)."
  [v]
  (cond
    #?(:clj  (bytes? v)
       :cljs (instance? js/Uint8Array v))               :bytes
    #?(:clj  (or (instance? java.util.Date v)
                 (instance? java.time.Instant v))
       :cljs (instance? js/Date v))                      :inst
    (uuid? v)                                             :uuid
    :else                                                 :unknown))

#?(:clj
   (defn- ->instant
     "Normalize java.util.Date / java.time.Instant to Instant."
     ^java.time.Instant [v]
     (if (instance? java.time.Instant v)
       v
       (.toInstant ^java.util.Date v))))

(defn- compare-tagged
  "Compare two tagged values (bytes/inst/uuid).
  First by tag-kind (alphabetical), then by value within same kind.

  Within a kind, the order matches the canonical text of the value:
  #inst is chronological (epoch seconds, then nanos) and #uuid is by
  its lowercase string.  Never compare platform toString output —
  Date.toString is timezone- and locale-dependent (§5.3.10)."
  [a b]
  (let [ka (tag-kind a)
        kb (tag-kind b)]
    (if (not= ka kb)
      (compare ka kb)
      (case ka
        :bytes (compare-bytes a b)
        :inst  #?(:clj  (compare (->instant a) (->instant b))
                  :cljs (compare (.getTime a) (.getTime b)))
        :uuid  (compare (.toLowerCase (str a)) (.toLowerCase (str b)))
        0))))

(defn rank
  "Comparator implementing the total order from Section 5.

  1. Compare by type-priority
  2. Within same type, compare by type-specific rules:
     - numbers: mathematical value (int before double if equal)
     - strings: lexicographic (Unicode codepoint order)
     - keywords/symbols: namespace then name
     - seqs/vectors: element-by-element, shorter first
     - sets: cardinality, then pairwise elements
     - maps: count, then keys, then values
     - tagged: tag kind (bytes < inst < uuid), then value"
  [a b]
  (if (identical? a b)
    0
    (let [pa (type-priority a)
          pb (type-priority b)]
      (if (not= pa pb)
        (compare pa pb)
        (case (int pa)
          0 0  ;; nil vs nil
          1 (compare a b)  ;; booleans: false < true
          2 (compare-numbers a b)
          3 (compare-strings a b)  ;; strings: Unicode codepoint order
          4 (compare-named a b)
          5 (compare-named a b)
          6 (compare-sequential a b)  ;; lists/seqs
          7 (compare-sequential a b)  ;; vectors
          8 (compare-sets a b)
          9 (compare-maps a b)
          ;; default: tagged (bytes/inst/uuid)
          (compare-tagged a b))))))
