(ns grafter-2.rdf4j.repository-test
  (:require [clojure.java.io :as io :refer [file]]
            [clojure.test :refer :all]
            [grafter-2.rdf.protocols :as core]
            [grafter-2.rdf4j.io :as rio]
            [grafter-2.rdf4j.repository :as repo :refer :all]
            [grafter-2.rdf4j.templater :refer [graph]]
            [grafter.url :refer [->GrafterURL]])
  (:import [java.net URI URL]
           org.eclipse.rdf4j.repository.sparql.SPARQLRepository))

(def quad-fixture-file-path (io/resource "grafter/rdf/rdf-types.trig"))

(def triple-fixture-file-path (io/resource "grafter/rdf/rdf-types.ttl"))

(deftest with-transaction-test
  (with-open [test-db (repo/->connection (sail-repo))]
    (testing "Transactions return last result of form if there's no error."
      (is (= :return-value (with-transaction test-db
                             :return-value))))
    (testing "Adding values in a transaction are visible after the transaction commits."
      (with-transaction test-db
        (core/add test-db (graph (URI. "http://example.org/test/graph")
                               [(URI. "http://test/subj") [(URI. "http://test/pred") (URI. "http://test/obj")]])))

      (is (query test-db "ASK WHERE { <http://test/subj> ?p ?o }")))))

(deftest sparql-repo-test
  (testing "Works with a query-url arg of type String"
    (let [repo (sparql-repo "http://localhost:3001/sparql/state")]
      (is (instance? SPARQLRepository repo))))

  (testing "Works with a query-url arg which satisfies the IURI Protocol"
    (let [repo (sparql-repo (->GrafterURL "http" "localhost" 3001 ["sparql" "state"] nil nil))]
      (is (instance? SPARQLRepository repo)))))


(defn load-rdf-types-data
  ([file]
   (let [db (sail-repo)]
     (with-open [conn (->connection db)]
       (core/add conn (rio/statements file)))

     db)))

(deftest query-test
  (with-open [test-db (->connection (load-rdf-types-data triple-fixture-file-path))]
    (are [type f?]
        (is (f? (let [o (-> (query test-db (str "PREFIX : <http://example/> SELECT ?o WHERE {" type " :hasValue ?o . }"))
                            first
                            (get :o))]
                  o)))

      :integer integer?
      :string (fn [v] (= "hello" (str v)))
      :date (partial instance? java.time.OffsetDateTime)
      :decimal (partial instance? java.math.BigDecimal)
      :float float?
      :double (partial instance? java.lang.Double)
      :boolean (fn [v] (#{true false} v)))))

(deftest round-tripping-data-and-queries
  (testing "When loading triples from a file and round-tripping througb a SPARQL
  repo it should be identical to the one read in."

    (testing "roundtripping ttl file"
      (let [file triple-fixture-file-path]
        (with-open [conn (->connection (load-rdf-types-data file))]
          (is (= (set (rio/statements conn))
                 (set (rio/statements file)))))))

    (testing "roundtripping trig file"
      (let [file quad-fixture-file-path]
        (with-open [conn (->connection (load-rdf-types-data file))]
          (is (= (set (rio/statements conn))
                 (set (rio/statements file)))))))))

(deftest delete-statement-test
  (testing "arity 2 delete"
    (are [initial-data delete-form]
        (with-open [test-db (->connection (load-rdf-types-data initial-data))]
          (let [quads-to-delete (rio/statements test-db)]
            delete-form
            (is (not (query test-db "ASK { ?s ?p ?o } LIMIT 1"))
                "Should be deleted")))

      triple-fixture-file-path (core/delete test-db quads-to-delete)
      quad-fixture-file-path (core/delete test-db quads-to-delete)))

  (testing "arity 3 delete"
    (let [repo (sail-repo)]

      (with-open [conn (->connection repo)]
        (-> conn
            (core/add
             (URL. "http://a")
             (rio/statements triple-fixture-file-path))
            (core/add
             (URL. "http://b")
             (rio/statements triple-fixture-file-path))))

      (with-open [test-db (->connection repo)]
        (core/delete test-db
                   (URL. "http://a")
                   (rio/statements triple-fixture-file-path))
        (is (not (query test-db "ASK { GRAPH <http://a> { ?s ?p ?o } } LIMIT 1"))
            "Should be deleted")

        (is (query test-db "ASK { GRAPH <http://b> { ?s ?p ?o } } LIMIT 1")
            "Should not be deleted")))))

(deftest col-reduce-repo-test
  (is (= (into #{} (sail-repo))
         #{}))

  (is (= (into #{} (fixture-repo (io/resource "grafter/rdf/1.nt")))
         #{(core/->Triple (URI. "http://one")
                     (URI. "http://lonely")
                     (URI. "http://triple"))})))

(deftest fixture-repo-test
  (is (= (into #{} (fixture-repo))
         #{}))

  (is (= (into #{} (fixture-repo (io/resource "grafter/rdf/1.nt")))
         #{(core/->Triple (URI. "http://one")
                     (URI. "http://lonely")
                     (URI. "http://triple"))}))

  (testing "Calling with multiple sets of quads appends them all into the repo"
    (with-open [conn (->connection (fixture-repo (io/resource "grafter/rdf4j/repository/quads.nq")
                                                 (io/resource "grafter/rdf4j/repository/quads.trig")))]
      (is (= 2 (count (rio/statements conn)))))))

(deftest resource-repo-test
  (testing "Calling with multiple sets of quads appends them all into the repo"
    (with-open [conn (->connection (resource-repo "grafter/rdf4j/repository/quads.nq"
                                                  "grafter/rdf4j/repository/quads.trig"))]
      (is (= 2 (count (rio/statements conn)))))))

(deftest sail-repo-test
  (is (instance? org.eclipse.rdf4j.repository.Repository (sail-repo)))
  (is (= (into #{} (sail-repo))
         #{})))


(deftest rdfs-inferencer-test
  (let [r (sail-repo (rdfs-inferencer (memory-store)))]
    (with-open [c (->connection r)]
      (core/add c (rio/statements (io/resource "grafter/rdf4j/repository/rdfs/foaf.ttl"))) ;; add foaf vocab for reasoning...
      (core/add c (rio/statements (io/resource "grafter/rdf4j/repository/rdfs/rdfs-inferencing.trig")))) ;; add data to reason about...

    (let [prefixes {"" "http://www.grafter.org/example#"
                    "foaf" "http://xmlns.com/foaf/0.1/"
                    "geopos" "http://www.w3.org/2003/01/geo/wgs84_pos#"}]
      (with-open [c (->connection r)]
        (testing "RDFS Inference"
          (is (query c "ASK { :rick a foaf:Person . }"
                     :prefixes prefixes
                     :reasoning? true))
          (is (query c "ASK { :manchester a geopos:SpatialThing . }"
                     :prefixes prefixes
                     :reasoning? true))
          (is (query c "ASK { :swirrl a foaf:Agent . }"
                     :prefixes prefixes
                     :reasoning? true))
          (is (query c "ASK { <http://swirrl.com/> a foaf:Document . }"
                     :prefixes prefixes
                     :reasoning? true)))))))


(comment

  (do
    ;; convert foaf RDFXML into Turtle as it's easier on the eyes...
    (def prefixes (-> rio/default-prefixes
                      (select-keys ["owl" "rdf" "rdfs"])
                      (assoc "ns" "http://www.w3.org/2003/06/sw-vocab-status/ns#"
                             "geopos" "http://www.w3.org/2003/01/geo/wgs84_pos#"
                             "foaf" "http://xmlns.com/foaf/0.1/"
                             "dce" "http://purl.org/dc/elements/1.1/"
                             "dcterms" "http://purl.org/dc/terms/"
                             "schema" "http://schema.org/")))

    (let [foaf (rio/rdf-writer "resources/foaf.ttl" :format :ttl :prefixes prefixes)]
      (core/add foaf (rio/statements "http://xmlns.com/foaf/spec/index.rdf" :format :rdf))))
  )
