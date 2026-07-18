(ns mealops.store
  "Store abstraction for prepared-meals/ready-dish (cook-chill and
  cook-freeze) production batches. Current implementation operates on
  plain data (`{:batches {batch-id batch-map} :facts [...]}`); production
  should migrate this seam to Datomic/kotoba-server (the same seam point
  all cloud-itonami actors use) while keeping the same pure-function
  surface.

  A production batch is the minimal unit of work: one processing run of
  a prepared-meal/ready-dish product, tracked from raw-material intake
  through prep, cook, chill/freeze, packaging, inspection, and shipment.
  Representative batch keys:
    - :product-type keyword product id (see `mealops.facts/product-types`)
    - :jurisdiction keyword jurisdiction id (see `mealops.facts/jurisdictions`)
    - :core-cook-temp-c / :chill-time-minutes / :cold-storage-temp-c /
      :shelf-life-hours-elapsed / :water-activity / :ph-level finished-
      product actuals
    - :foreign-material-detected? true if metal-detector/X-ray inspection
      flagged tramp metal, glass, or dense-plastic fragments
    - :packaging-seal-compromised? true if seal-integrity inspection
      flagged a vacuum/MAP packaging breach
    - :sanitation-score 0-100 plant hygiene/cross-contamination-control score
    - :metal-detector-last-calibration-date epoch-ms of last
      metal-detection/X-ray equipment calibration
    - :weight-variance-grams finished-product weight drift from target
    - :cross-contact-risk set of allergen keywords actually present via
      shared prep surfaces/equipment (e.g. #{:milk :gluten})
    - :declared-allergens set of allergen keywords declared on label
    - :evidence-checklist evidence items present for the batch
    - :material-lot raw-material intake lot record (see
      `mealops.facts/material-lot-keys`) backing the
      `:raw-material-intake-record` evidence-checklist item with real
      supplier-verification data. MAY carry an optional
      `:material/handoff` (the upstream supplier actor's own outbound
      `:handoff` record, passed through unchanged -- see
      `mealops.facts`'s Inbound Cross-Actor Handoff section)
    - :packaging-lot packaging-material intake lot record (see
      `mealops.facts/packaging-lot-keys`) backing the
      `:packaging-seal-check` evidence-checklist item. MAY carry an
      optional `:packaging/handoff` (the upstream packaging supplier
      actor's own outbound `:handoff` record, passed through unchanged)
    - :safety-concern-raised? / :safety-concern-resolved? food-safety flag
    - :processed? true once a `:log-production-batch` proposal commits
    - :shipment-finalized? true once a `:coordinate-shipment` proposal commits

  The ledger (`:facts`) is a separate append-only vector of audit facts,
  kept alongside `:batches` in the same store value.")

(defn production-batch
  "Retrieve a batch by id, or nil if it does not exist / is not yet
  registered."
  [st batch-id]
  (get-in st [:batches batch-id]))

(defn batch-already-processed?
  "True only if the batch exists and has already been marked processed."
  [st batch-id]
  (true? (:processed? (production-batch st batch-id))))

(defn batch-shipment-finalized?
  "True only if the batch exists and its shipment has already been
  finalized."
  [st batch-id]
  (true? (:shipment-finalized? (production-batch st batch-id))))

(defn log-batch
  "Register/update `batch-data` under `batch-id` and mark it processed
  (one-way flag). Used once a `:log-production-batch` proposal commits."
  [st batch-id batch-data]
  (assoc-in st [:batches batch-id] (assoc batch-data :processed? true)))

(defn finalize-shipment
  "Mark an existing batch's shipment as finalized (one-way flag). Used once
  a `:coordinate-shipment` proposal commits."
  [st batch-id]
  (assoc-in st [:batches batch-id :shipment-finalized?] true))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet)."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger."
  [st fact]
  (update st :facts (fnil conj []) fact))
