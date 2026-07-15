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
  ceiling is under-acidified and therefore a violation."
  {:meal/cook-chill-poultry
   {:id :meal/cook-chill-poultry
    :name "クックチル調理済み食品(鶏肉ベース)"
    :core-cook-temp-min-c 70.0
    :chill-time-max-minutes 90.0
    :cold-storage-temp-min-c 0.0
    :cold-storage-temp-max-c 3.0
    :max-shelf-life-hours 120.0
    :water-activity-max 0.97
    :ph-max 5.0}

   :meal/cook-chill-beef
   {:id :meal/cook-chill-beef
    :name "クックチル調理済み食品(牛肉ベース)"
    :core-cook-temp-min-c 70.0
    :chill-time-max-minutes 90.0
    :cold-storage-temp-min-c 0.0
    :cold-storage-temp-max-c 3.0
    :max-shelf-life-hours 120.0
    :water-activity-max 0.97
    :ph-max 5.0}

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
    :ph-max 6.5}

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
    :ph-max 5.5}})

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
