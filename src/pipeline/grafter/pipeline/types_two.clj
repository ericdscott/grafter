(ns grafter.pipeline.types-two
  "This namespace code for parsing and interpreting
  grafter.pipeline/declare-pipeline type signatures.  In particular it
  defines a macro deftype-reader that can be used to coerce/read
  strings into their appropriate clojure types.

  We use the declared pipelines signature to guide the interpretation
  of a string the target type."
  (:require [clojure.data :refer [diff]]
            [clojure.edn :as edn]
            [clojure.instant :as inst]
            [clojure.instant :refer [read-instant-date]]
            [clojure.set :as set]
            [grafter.tabular :as tabular]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.net URI URL]
           [java.util UUID Date Map]
           [incanter.core Dataset]))

(def parameter-types
  "Atom containing the parameter type hierarchy of supported pipeline
  types.  As declared through declare-pipeline.

  Types in this hierarchy should ultimately be coerceable through the
  parse-parameter multi-method.

  In addition to using the multi-method to introduce new types, you
  can gain more code reuse by extending the hierarchy with:

  (swap! parameter-types derive ::foo ::bar)"
  (atom (make-hierarchy)))

(defmulti parse-parameter (fn [target-type value options]
                            (let [input-type (type value)]
                              [input-type target-type]))
  :hierarchy parameter-types)

(defmethod parse-parameter :default [target-type input-val opts]
  (throw (ex-info (str "No grafter.pipeline.types/parse-parameter defined to coerce values to type " target-type)
                  {:error :type-reader-error
                   :target-type target-type
                   :value input-val
                   :options opts})))

(defmethod parse-parameter ::edn-primitive [_ val opts]
  (edn/read-string val))

(defmethod parse-parameter [String Boolean] [_ val opts]
  (boolean (edn/read-string val)))

(defmethod parse-parameter [String Integer] [_ val opts]
  (Integer/parseInt val))

(defmethod parse-parameter [String clojure.lang.BigInt] [_ val opts]
  (bigint (edn/read-string val)))

(defmethod parse-parameter [String Double] [_ val opts]
  (Double/parseDouble val))

(defmethod parse-parameter [String String] [_ val opts]
  val)

(defmethod parse-parameter [String Float] [_ val opts]
  (Float/parseFloat val))

(defmethod parse-parameter [String ::uri] [_ val opts]
  (java.net.URI. val))

(defmethod parse-parameter [String java.util.Date] [_ val opts]
  (inst/read-instant-date val))

(defmethod parse-parameter [String clojure.lang.Keyword] [_ val opts]
  (keyword val))

(defmethod parse-parameter [String java.util.UUID] [_ val opts]
  (java.util.UUID/fromString val))

(defmethod parse-parameter [String ::file] [_ val opts]
  val)

(defmethod parse-parameter [String java.io.Reader] [_ val opts]
  (io/reader val))

(swap! parameter-types derive ::value ::root-type)

(swap! parameter-types derive ::edn-primitive ::value)

(swap! parameter-types derive Boolean ::edn-primitive)

(swap! parameter-types derive Boolean ::edn-primitive)

(swap! parameter-types derive String ::edn-primitive)

(swap! parameter-types derive Float ::edn-primitive)

(swap! parameter-types derive Long ::edn-primitive)

(swap! parameter-types derive Integer ::edn-primitive)

(swap! parameter-types derive Double ::edn-primitive)

(swap! parameter-types derive clojure.lang.Keyword ::edn-primitive)

(swap! parameter-types derive java.util.Date ::edn-primitive)

(swap! parameter-types derive java.util.UUID ::edn-primitive)

(swap! parameter-types derive ::uri ::value)

(swap! parameter-types derive ::url ::uri)

(swap! parameter-types derive ::dataset-uri ::uri)

(swap! parameter-types derive java.net.URI ::uri)

(swap! parameter-types derive ::edn-map ::value)

(swap! parameter-types derive ::edn-map ::json-map)

(swap! parameter-types derive ::tabular-file ::file)

(swap! parameter-types derive incanter.core.Dataset ::tabular-file)

(swap! parameter-types derive ::binary-file ::file)

(swap! parameter-types derive ::text-file ::file)

(swap! parameter-types derive ::rdf-file ::file)

(swap! parameter-types derive ::another-file ::file)

(swap! parameter-types derive java.io.Reader ::text-file)

(swap! parameter-types derive java.io.InputStream ::binary-file)

(defn supported-parameter?
  "Predicate function that returns true if the parameter type is
  a supported by parameter-type.

  Supported parameters are parameter types (either classes or
  keywords) which are supported either an explicit dispatch for the
  parse-parameter multimethod or are reachable through
  @parameter-types type hierarchy."
  [p]
  (some (partial isa? @parameter-types p)
        (map second (remove keyword?
                            (keys (methods parse-parameter))))))

(defn preferred-type [t]
  (let [prefs (prefers parse-parameter)]
    (reduce
     (fn [acc [pref-type types]]
       (if-let [pref (types t)]
         pref-type
         t))
     false prefs)))

(defn- parameter-type-chain*
  [t]
  (when (supported-parameter? t)
    (let [ps (parents @parameter-types t)]
      (->> (cons t (lazy-seq (mapcat parameter-type-chain* ps)))
           (map preferred-type)))))

(defn parameter-type-chain
  "Interogates the parse-parameter multi-method and returns an ordered
  sequence representing the hierarchy chain.  The order of items in
  the chain respects both the hierarchy of parameter-types and
  prefer-method."
  [t]
  (distinct (parameter-type-chain* t)))

;;[Symbol] -> {:arg-types [Symbol], :return-type Symbol]}
(defn ^:no-doc parse-type-list
  "Parses a given list of symbols expected to represent a pipeline
  type definition. Validates the list has the expected format - n >= 0
  parameter types followed by a '-> followed by the return
  type. Returns a record containing the ordered list of parameter
  symbols and the return type symbol."
  [l]
  (let [c (count l)]
    (if (< c 2)
      (throw (IllegalArgumentException. "Invalid type declaration - requires at least -> and return type"))
      (let [arg-count (- c 2)
            separator (l arg-count)
            return-type (last l)
            arg-types (vec (take arg-count l))]

        (if (= '-> separator)
          {:arg-types arg-types :return-type return-type}
          (throw (IllegalArgumentException. "Invalid type declaration: Expected [args -> return-type")))))))

;;[a] -> [b] -> ()
(defn- validate-argument-count
  "Throws an exception if the number of parameter types in a pipeline
  type declaration do not match the number of elements in the pipeline
  var's argument list."
  [declared-arg-list type-list]
  (let [comp (compare (count declared-arg-list) (count type-list))]
    (if (not= 0 comp)
      (let [det (if (pos? comp) "few" "many")
            msg (str "Too " det " argument types provided for pipeline argument list " declared-arg-list " (got " type-list ")")]
        (throw (IllegalArgumentException. msg))))))

;;Symbol -> Class
(defn resolve-parameter-type
  "Attempts to resolve a symbol representing a pipeline parameter type
  to the class instance representing the class. Throws an exception if
  the resolution fails."
  ([sym] (resolve-parameter-type *ns* sym))
  ([ns sym]
   (cond
     (symbol? sym) (if-let [cls (ns-resolve ns sym)]
                     cls
                     (throw (IllegalArgumentException. (str "Failed to resolve " sym " to class in namespace" ns))))
     (keyword? sym) sym
     :else (throw (IllegalArgumentException. (str "Unexpected type of parameter " (type sym)))))
   ))

(defn- get-arg-descriptor [name-sym type-sym doc doc-meta]
  (let [common {:name name-sym :class type-sym :doc doc}]
    (if (supported-parameter? (resolve-parameter-type type-sym))
      (if doc-meta
        (assoc common :meta doc-meta)
        common)
      (throw (IllegalArgumentException. (str "Unsupported pipeline parameter type: " type-sym))))))

#_(defn ^:no-doc resolve-var
  "Attempts to resolve a named var inside the given namespace. Throws
  an exception if the resolution fails."
  [ns v]
  (if-let [rv (ns-resolve ns v)]
    rv
    (throw (IllegalArgumentException. (str "Cannot resolve var " v " in namespace " (.getName ns))))))

;;Symbol -> PipelineType
(defn- pipeline-type-from-return-type-sym
  "Infers the 'type' (graft or pipe) of a pipeline function from its
  return type. Throws an exception if the return type symbol is
  invalid."
  [ret-sym]
  (condp = ret-sym
    '(Seq Statement) :graft ;; deprecated
    '(Seq Quad) :graft
    '[Quad] :graft
    'Quads :graft
    'Dataset :pipe
    (let [msg (str "Invalid return type " ret-sym " for pipeline function: required Dataset or [Quad]")]
      (throw (IllegalArgumentException. msg)))))

;;[a] -> {a b} -> [[a b]]
(defn- correlate-pairs
  [ordered-keys m]
  "Orders the pairs in a map so the keys are in the same order as the
  elements in the given 'reference' vector.
  (correlate-pairs [:b :a] {:a 1 :b 2}) => [[:b 2] [:a 1]]"
  {:pre [(= (count ordered-keys) (count m))
         (= (set ordered-keys) (set (keys m)))]}
  (let [indexes (into {} (map-indexed #(vector %2 %1) ordered-keys))]
    (vec (sort-by (comp indexes first) m))))


;;[Symbol] -> [Symbol] -> {Symbol String} -> [ArgDescriptor]
(defn- resolve-pipeline-arg-descriptors [arg-names arg-type-syms doc-map]
  (validate-argument-count arg-names arg-type-syms)
  (let [[missing-doc unknown-doc _] (diff (set arg-names) (set (keys doc-map)))]
    (cond
     (not (empty? missing-doc))
     (throw (IllegalArgumentException. (str "No documentation found for variable(s): " missing-doc)))

     (not (empty? unknown-doc))
     (throw (IllegalArgumentException. (str "Found documentation for unknown variable(s): " unknown-doc)))

     :else
     (let [correlated-docs (correlate-pairs arg-names doc-map)]
       (mapv (fn [n ts [doc-name doc]] (get-arg-descriptor doc-name ts doc (-> (meta doc-name)
                                                                              ;; remove line number metadata inserted by clojure as its superfluous here
                                                                              (dissoc :file :line :column))))
             arg-names
             arg-type-syms
             correlated-docs)))))

(defn- validate-supported-pipeline-operations! [supported-operations]
  (let [ops (set supported-operations)
        valid-operations #{:append :delete}
        invalid-operations (set/difference ops valid-operations)]
    (when-not (empty? invalid-operations)
      (throw (IllegalArgumentException. (str "Invalid supported operations for pipeline: "
                                             (str/join ", " invalid-operations)
                                             ". Valid operations are: " (str/join ", " valid-operations)))))))

;;Var -> [Symbol] -> Metadata -> PipelineDef
(defn ^:no-doc create-pipeline-declaration
  "Inspects a var containing a pipeline function along with an
  associated type definition and metadata map. The type definition
  should declare the type of each parameter and the return type of the
  pipeline. The metadata map must contain a key-value pair for each
  named parameter in the pipeline function argument list. The value
  corresponding to each key in the metadata map is expected to be a
  String describing the parameter. The opts map can contain
  an optional :supported-operations key associated to a collection
  containing :append and/or :delete. These operations indicate whether
  the data returned from the pipeline can be appended to or deleted
  from the destination."
  [sym type-list metadata opts]
  (let [def-var (resolve-parameter-type *ns* sym)
        def-meta (meta def-var)
        arg-list (first (:arglists def-meta))
        {:keys [arg-types return-type]} (parse-type-list type-list)
        pipeline-type (pipeline-type-from-return-type-sym return-type)
        args (resolve-pipeline-arg-descriptors arg-list arg-types metadata)
        supported-operations (:supported-operations opts #{:append})]
    (validate-supported-pipeline-operations! supported-operations)
     {:var def-var
      :doc (or (:doc def-meta) "")
      :args args
      :type pipeline-type
      :declared-args arg-list
      :supported-operations supported-operations}))