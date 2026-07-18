(ns mealops.facts
  "Reference facts for prepared-meals/ready-dish manufacturing (cook-chill
  and cook-freeze lines): product-type processing parameters (core-cook-
  temperature/chill-time/cold-storage-temperature/shelf-life/water-
  activity/pH windows), jurisdiction evidence-checklist requirements.
  This namespace contains pure lookup functions for regulatory/food-
  safety (HACCP) compliance checks -- the Governor calls these to
  independently validate proposals; the advisor's confidence is never
  sufficient on its own."
  (:require [clojure.set :as set]))

(def product-types
  "Valid prepared-meal/ready-dish product categories and their safe
  cook-chill/cook-freeze processing windows. `core-cook-temp-min-c` is
  the minimum core (thermal-centre) temperature the batch must reach
  during cooking (CCP1 -- the pasteurization/lethality step; UK FSA
  cook-chill/cook-freeze guidance and equivalent US FDA Food Code /
  Codex CAC/RCP 39 lethality standards use 70C as the reference
  cook-lethality temperature). `chill-time-max-minutes` is the maximum
  time allowed to bring the batch from cook temperature down through
  the microbial \"danger zone\" to safe cold-storage temperature (CCP2 --
  cook-chill guidance requires 70C->3C within 90 minutes; cook-freeze
  lines are allowed longer to reach a deep-frozen target). `cold-
  storage-temp-min-c` / `cold-storage-temp-max-c` describe the safe
  cold-chain storage/transport window (chilled: 0C-3C; frozen: -22C to
  -18C). `max-shelf-life-hours` is the maximum time from production to
  use-by/consumption the product may be held under correct cold-chain
  conditions before the safety margin against pathogen growth is
  exhausted. `water-activity-max` is the maximum allowable water
  activity (Aw) -- higher Aw enables faster microbial growth if the
  cold chain is broken. `ph-max` is the maximum allowable pH -- for
  reduced-oxygen-packaged (vacuum/MAP-sealed) cook-chill product, a
  sufficiently LOW pH is one of the recognised secondary controls
  against non-proteolytic Clostridium botulinum growth (UK FSA / Health
  Canada REPFED guidance), so a batch with pH ABOVE the product's
  ceiling is under-acidified and therefore a violation.

  `:unspsc-code` is an 8-digit UNSPSC (United Nations Standard Products
  and Services Code) COMMODITY code, reported HONESTLY per this
  fleet's anti-fabrication discipline (see superproject `90-docs/adr/`
  UNSPSC/GTIN-linkage ADR): UNSPSC family 50190000 ('Prepared and
  preserved foods') -> class 501927 ('Packaged combination meals') has
  two confirmed sibling commodities, `50192701` ('Fresh Combination
  Meals') and `50192702` ('Frozen combination meals') -- used here for
  the chilled (cook-chill) vs. frozen (cook-freeze) product types
  respectively, since UNSPSC does not publish separate 8-digit
  commodities per protein/base ingredient (poultry/beef/fish/
  vegetarian) within this family; fabricating that granularity would
  violate the same 'never invent a jurisdiction's requirements'
  discipline `pressureequip.facts`/every sibling actor's facts registry
  already applies.

  `:gtin` is NOT a classification taxonomy code -- a GTIN (Global Trade
  Item Number) is an identifier GS1 issues per REGISTERED PHYSICAL
  PRODUCT only after a real company enrolls with GS1 (see
  `cloud-itonami-gtin-issuance`'s README / superproject
  ADR-2607031800). Every `:gtin` value here is a SYNTACTICALLY VALID
  but NEVER-ISSUED placeholder built on GS1's own officially-documented
  'Restricted Circulation Number' (RCN) prefix range '020'-'029' (GS1
  GSCN-23-006-RCN / gs1.org 'GS1 Company Prefix' docs -- a range GS1
  itself reserves for company-internal/restricted use, i.e. explicitly
  NOT a globally-unique retail identifier) with a correctly computed
  Modulo-10 GTIN-13 check digit. The sibling key `:gtin/status
  :unissued-blueprint-placeholder` makes the non-issuance explicit;
  treat `:gtin` here as an EXAMPLE VALUE ONLY."
  {:meal/cook-chill-poultry
   {:id :meal/cook-chill-poultry
    :name "クックチル調理済み食品(鶏肉ベース)"
    :core-cook-temp-min-c 70.0
    :chill-time-max-minutes 90.0
    :cold-storage-temp-min-c 0.0
    :cold-storage-temp-max-c 3.0
    :max-shelf-life-hours 120.0
    :water-activity-max 0.97
    :ph-max 5.0
    :unspsc-code "50192701"
    :gtin "0211075000011"
    :gtin/status :unissued-blueprint-placeholder}

   :meal/cook-chill-beef
   {:id :meal/cook-chill-beef
    :name "クックチル調理済み食品(牛肉ベース)"
    :core-cook-temp-min-c 70.0
    :chill-time-max-minutes 90.0
    :cold-storage-temp-min-c 0.0
    :cold-storage-temp-max-c 3.0
    :max-shelf-life-hours 120.0
    :water-activity-max 0.97
    :ph-max 5.0
    :unspsc-code "50192701"
    :gtin "0211075000028"
    :gtin/status :unissued-blueprint-placeholder}

   :meal/cook-freeze-fish
   {:id :meal/cook-freeze-fish
    :name "クックフリーズ調理済み食品(魚介ベース)"
    :core-cook-temp-min-c 70.0
    ;; Cook-freeze lines are allowed a longer window to reach a deep-frozen
    ;; target (vs. cook-chill's 90-minute chill-to-3C rule).
    :chill-time-max-minutes 240.0
    :cold-storage-temp-min-c -22.0
    :cold-storage-temp-max-c -18.0
    ;; ~6 months of frozen storage, a typical cook-freeze ready-meal
    ;; shelf life.
    :max-shelf-life-hours 4380.0
    :water-activity-max 0.97
    :ph-max 6.5
    :unspsc-code "50192702"
    :gtin "0211075000035"
    :gtin/status :unissued-blueprint-placeholder}

   :meal/cook-chill-vegetarian
   {:id :meal/cook-chill-vegetarian
    :name "クックチル調理済み食品(野菜/植物性ベース)"
    :core-cook-temp-min-c 70.0
    :chill-time-max-minutes 90.0
    :cold-storage-temp-min-c 0.0
    :cold-storage-temp-max-c 3.0
    ;; Vegetarian cook-chill product lacks a meat "kill step" safety
    ;; margin and carries a different spoilage flora profile, so this
    ;; actor's reference shelf life for it is shorter than the
    ;; meat-based cook-chill product types.
    :max-shelf-life-hours 96.0
    :water-activity-max 0.98
    :ph-max 5.5
    :unspsc-code "50192701"
    :gtin "0211075000042"
    :gtin/status :unissued-blueprint-placeholder}})

(defn product-type-by-id [id]
  (get product-types id))

(def jurisdictions
  "Prepared-meals/ready-dish manufacturing jurisdictions and their
  evidence-checklist requirements."
  {:jp/mhlw
   {:id :jp/mhlw
    :name "日本 (食品衛生法・HACCP制度化・厚生労働省)"
    :required-evidence
    [:raw-material-intake-record
     :cook-lethality-log
     :chill-time-log
     :cold-storage-temp-log
     :water-activity-test
     :ph-test
     :allergen-declaration
     :weight-check
     :packaging-seal-check]}

   :us/fda
   {:id :us/fda
    :name "United States (FDA Food Code / HACCP)"
    :required-evidence
    [:raw-material-intake-record
     :cook-lethality-log
     :chill-time-log
     :cold-storage-temp-log
     :water-activity-test
     :ph-test
     :allergen-declaration
     :weight-check
     :packaging-seal-check]}

   :eu/efsa
   {:id :eu/efsa
    :name "European Union (Regulation (EC) 852/2004 & 853/2004 hygiene package)"
    :required-evidence
    [:raw-material-intake-record
     :cook-lethality-log
     :chill-time-log
     :cold-storage-temp-log
     :water-activity-test
     :ph-test
     :allergen-declaration
     :weight-check
     :packaging-seal-check]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list
  is present in `evidence`. `jurisdiction` may be a resolved jurisdiction
  map (as returned by `jurisdiction-by-id`) or a raw jurisdiction id --
  both call conventions are in use (tests pass a resolved map; the
  Governor passes the raw id straight off batch metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn core-cook-temp-meets-minimum?
  "Positive-sense convenience predicate: does `celsius` meet or exceed
  `product`'s minimum required core (thermal-centre) cook temperature
  (CCP1 lethality step)?"
  [celsius product]
  (boolean
   (and (some? product)
        (>= celsius (:core-cook-temp-min-c product)))))

(defn chill-time-within-max?
  "Positive-sense convenience predicate: did the batch stay at or below
  `product`'s maximum allowed chill/freeze-down time (CCP2)?"
  [minutes product]
  (boolean
   (and (some? product)
        (<= minutes (:chill-time-max-minutes product)))))

(defn cold-storage-temp-in-range?
  "Positive-sense convenience predicate: does `celsius` fall within
  `product`'s safe cold-chain storage/transport window (inclusive)?"
  [celsius product]
  (boolean
   (and (some? product)
        (>= celsius (:cold-storage-temp-min-c product))
        (<= celsius (:cold-storage-temp-max-c product)))))

(defn shelf-life-within-max?
  "Positive-sense convenience predicate: has the batch stayed at or
  below `product`'s maximum shelf-life hours since production?"
  [hours-elapsed product]
  (boolean
   (and (some? product)
        (<= hours-elapsed (:max-shelf-life-hours product)))))

(defn water-activity-within-max?
  "Positive-sense convenience predicate: does `aw` stay at or below
  `product`'s maximum allowable water activity?"
  [aw product]
  (boolean
   (and (some? product)
        (<= aw (:water-activity-max product)))))

(defn ph-level-within-max?
  "Positive-sense convenience predicate: does `ph` stay at or below
  `product`'s maximum allowable pH (i.e. sufficiently acidified for a
  reduced-oxygen-packaged product's secondary botulinum control)?"
  [ph product]
  (boolean
   (and (some? product)
        (<= ph (:ph-max product)))))

;; ─────────────── Cross-Actor Handoff (isic-1075 <-> jsic-4721) ───────────────
;;
;; `:coordinate-shipment` proposals that hand a finished batch off to a
;; downstream cold-chain 3PL actor (e.g. cloud-itonami-jsic-4721) carry a
;; `:handoff` record: a small, self-contained wire-shape (documented in
;; superproject ADR-2607177600, `90-docs/adr/`) that both this actor and
;; the receiving actor independently validate as PURE PREDICATES against
;; their own reference data -- no shared code, no shared store, just the
;; same field names on both sides. This actor's half of that contract is
;; a single check: does the handoff's declared cold-chain-temp-min-c/
;; max-c window stay within the margin this actor's own `product-types`
;; registry already knows to be safe for that product?
;;
;;   {:handoff/id "..."
;;    :handoff/source-actor "cloud-itonami-isic-1075"
;;    :handoff/batch-id "..."
;;    :handoff/product-type-id :meal/cook-chill-poultry
;;    :handoff/cold-chain-temp-min-c 0.0
;;    :handoff/cold-chain-temp-max-c 3.0
;;    :handoff/quantity-kg 120.5
;;    :handoff/dispatched-at-iso "..."
;;    :handoff/unspsc-code "50192701"    ; OPTIONAL, pass-through only -- see
;;    :handoff/gtin "0211075000011"}     ; superproject UNSPSC/GTIN-linkage
;;                                       ; ADR. Neither side validates these
;;                                       ; two (no existing predicate reads
;;                                       ; them); they ride along on the
;;                                       ; record for downstream traceability,
;;                                       ; the same asymmetric-optional,
;;                                       ; no-new-hard-check discipline
;;                                       ; `coldchain.governor`'s grid-outage
;;                                       ; reference fields already establish.

(defn handoff-window-within-product-safety-margin?
  "Positive-sense convenience predicate: is the declared handoff's
  cold-chain-temp-min-c/max-c window entirely contained within (a
  subset of) `product`'s own cold-storage-temp-min-c/max-c safety
  window? A handoff declaring a WIDER or shifted window than the
  product's proven-safe cold-chain range (e.g. -5C to 5C for a product
  whose safety margin is only 0C to 3C) is a cross-actor handoff
  violation -- the same kind of cold-chain-break concern
  `cold-storage-temp-in-range?` catches for a single instantaneous
  reading, but evaluated over the whole declared window up front,
  before the batch ever leaves this actor's custody."
  [handoff-min-c handoff-max-c product]
  (boolean
   (and (some? product)
        (some? handoff-min-c)
        (some? handoff-max-c)
        (<= handoff-min-c handoff-max-c)
        (>= handoff-min-c (:cold-storage-temp-min-c product))
        (<= handoff-max-c (:cold-storage-temp-max-c product)))))

;; ─────────────── Raw-Material Lot / Supplier Verification ───────────────
;;
;; `:raw-material-intake-record` used to be a bare evidence-checklist
;; checkbox with no underlying supply-chain data behind it. This makes
;; that data explicit: a `:material-lot` record attached to a production
;; batch (see `mealops.store`'s batch-key docs) carries which lot, from
;; which declared supplier, whether this actor has independently
;; VERIFIED that supplier (never inferred from the supplier's own
;; self-declaration), and when the lot was received.
;;
;;   {:material/lot-id "..."
;;    :material/supplier-name "..."
;;    :material/supplier-verified? true
;;    :material/received-at-iso "..."}

(def material-lot-keys
  "The canonical key shape of a raw-material intake lot record. Not a
  reference catalog like `product-types`/`jurisdictions` -- lot data is
  per-batch instance data that lives in the store, this is just the
  documented shape."
  #{:material/lot-id :material/supplier-name :material/supplier-verified?
    :material/received-at-iso})

(defn material-lot-supplier-verified?
  "Positive-sense convenience predicate: does `material-lot` explicitly
  declare its supplier as verified? Missing, false, or nil are all
  treated as NOT verified -- an absent declaration is never silently
  trusted as a pass."
  [material-lot]
  (true? (:material/supplier-verified? material-lot)))

;; ─────────── Inbound Cross-Actor Handoff (this actor as RECEIVER) ───────────
;;
;; The "Cross-Actor Handoff" section above documents this actor's OUTBOUND
;; `:handoff` (isic-1075 -> jsic-4721, dispatching side). This actor is
;; ALSO the RECEIVING side of the identical `:handoff/*` wire shape for
;; its own raw-material and packaging-material supply chain: an upstream
;; supplier actor's own outbound `:coordinate-shipment`-equivalent
;; proposal hands its `:handoff` value through unchanged, and this
;; actor's `:material-lot` (raw material) / `:packaging-lot` (packaging
;; material) records MAY optionally carry it under `:material/handoff` /
;; `:packaging/handoff`. Documented in superproject
;; `90-docs/adr/2607181500-isic1075-inbound-supply-chain-handoff.edn`.
;; No new wire shape -- the SAME `:handoff/id`/`:handoff/source-actor`/
;; `:handoff/batch-id`/`:handoff/product-type-id`/`:handoff/quantity-kg`/
;; `:handoff/dispatched-at-iso` (+ optional cold-chain/unspsc/gtin) fields,
;; reused as a receiving-side reference instead of a dispatching-side one.
;;
;;   {:material/lot-id "..."
;;    :material/supplier-name "..."
;;    :material/supplier-verified? true
;;    :material/received-at-iso "..."
;;    :material/handoff
;;    {:handoff/id "..."
;;     :handoff/source-actor "cloud-itonami-isic-1010"
;;     :handoff/batch-id "..."
;;     :handoff/product-type-id "fresh-poultry"
;;     :handoff/quantity-kg 500.0
;;     :handoff/dispatched-at-iso "..."}}

(defn handoff-record-well-formed?
  "Positive-sense convenience predicate: does `handoff` carry every
  REQUIRED `:handoff/*` field (id/source-actor/batch-id/product-type-id/
  quantity-kg/dispatched-at-iso) with a plausible value (quantity-kg a
  positive number, the string fields non-blank)? Never validates the
  OPTIONAL cold-chain/unspsc/gtin fields -- the same asymmetric-optional,
  no-new-hard-check discipline the outbound handoff checks above apply.
  Shared by both the raw-material (`:material/handoff`) and
  packaging-material (`:packaging/handoff`) receiving-side checks below,
  since both reuse the identical wire shape."
  [handoff]
  (boolean
   (and (map? handoff)
        (seq (:handoff/id handoff))
        (seq (:handoff/source-actor handoff))
        (seq (:handoff/batch-id handoff))
        (some? (:handoff/product-type-id handoff))
        (number? (:handoff/quantity-kg handoff))
        (pos? (:handoff/quantity-kg handoff))
        (seq (:handoff/dispatched-at-iso handoff)))))

(def raw-material-source-actors
  "This actor's ACTUAL, actually-registered immediate upstream
  raw-material supplier roster, keyed by this actor's own meal
  product-type id -- a supply-chain/provenance fact, not a food-safety
  fact, so it carries none of the 'never invent a jurisdiction's
  requirements' risk the rest of this namespace is careful about.

  The immediate supplier for poultry/beef/fish/vegetarian raw material
  is the PROCESSING actor one step downstream of the farm/vessel, not
  the farm/vessel itself: `cloud-itonami-isic-0146` (poultry farm),
  `cloud-itonami-isic-0141` (cattle ranch), and `cloud-itonami-isic-0113`
  (vegetable/root-crop farm) have NO shipment/dispatch-equivalent
  proposal op in their own closed operation allowlist at all (back-office
  farm-record coordination only -- see each actor's own `governor.cljc`
  `known-ops`), so they cannot originate a `:handoff` for this actor to
  receive directly; deliberately absent here rather than invented. See
  ADR-2607181500 for the fuller inventory (including the isic-0311/0312/
  0321/0322 fishery/aquaculture actors that exist upstream of isic-1020
  but are also out of THIS actor's direct-supplier scope)."
  {:meal/cook-chill-poultry #{"cloud-itonami-isic-1010"}
   :meal/cook-chill-beef #{"cloud-itonami-isic-1010"}
   :meal/cook-freeze-fish #{"cloud-itonami-isic-1020"}
   :meal/cook-chill-vegetarian #{"cloud-itonami-isic-1030"}})

(defn material-handoff-source-actor-known?
  "Positive-sense convenience predicate: for `product-type-id`, is
  `source-actor` one of this actor's actually-registered immediate
  upstream raw-material suppliers (`raw-material-source-actors`)? A
  product type with no registered roster entry, or a nil source-actor,
  always returns false -- absence is never silently treated as valid,
  the same discipline `material-lot-supplier-verified?` applies to a
  missing verification flag."
  [product-type-id source-actor]
  (boolean
   (and source-actor
        (contains? (get raw-material-source-actors product-type-id #{}) source-actor))))

;; ─────────── Packaging-Material Lot / Supplier Verification ───────────
;;
;; Mirrors the raw-material-lot section above, but backing the
;; `:packaging-seal-check` evidence-checklist item (vacuum/MAP packaging
;; film integrity) instead of `:raw-material-intake-record`. A production
;; batch's `:packaging-lot` record carries which lot, from which declared
;; supplier, whether this actor has independently VERIFIED that supplier,
;; when it was received, and (optionally) the upstream packaging
;; supplier's own outbound `:handoff` record.
;;
;;   {:packaging/lot-id "..."
;;    :packaging/supplier-name "..."
;;    :packaging/supplier-verified? true
;;    :packaging/received-at-iso "..."
;;    :packaging/handoff {:handoff/id "..." ...}}

(def packaging-lot-keys
  "The canonical key shape of a packaging-material intake lot record.
  Not a reference catalog -- lot data is per-batch instance data that
  lives in the store, this is just the documented shape."
  #{:packaging/lot-id :packaging/supplier-name :packaging/supplier-verified?
    :packaging/received-at-iso})

(defn packaging-lot-supplier-verified?
  "Positive-sense convenience predicate: does `packaging-lot` explicitly
  declare its supplier as verified? Missing, false, or nil are all
  treated as NOT verified, mirroring `material-lot-supplier-verified?`."
  [packaging-lot]
  (true? (:packaging/supplier-verified? packaging-lot)))

(def packaging-source-actors
  "This actor's ACTUAL, actually-registered immediate upstream
  packaging-material supplier roster: `cloud-itonami-isic-1702`
  (corrugated shipping cases) and `cloud-itonami-isic-2220` (vacuum/MAP
  packaging film) -- both `:implemented`, not category-keyed like
  `raw-material-source-actors` since every product type uses the same
  packaging-supplier pool."
  #{"cloud-itonami-isic-1702" "cloud-itonami-isic-2220"})

(defn packaging-handoff-source-actor-known?
  "Positive-sense convenience predicate: is `source-actor` one of this
  actor's actually-registered packaging-material suppliers
  (`packaging-source-actors`)? A nil source-actor always returns false."
  [source-actor]
  (boolean (contains? packaging-source-actors source-actor)))
