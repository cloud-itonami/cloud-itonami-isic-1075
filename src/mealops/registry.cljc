(ns mealops.registry
  "Pure validation functions for prepared-meals/ready-dish (cook-chill and
  cook-freeze) manufacturing production parameters. These are called by
  the Governor to independently verify physical/operational HACCP
  critical-control-point (CCP) constraints -- the advisor's confidence is
  NOT sufficient to override these checks.

  All functions here are pure arithmetic/set/boolean predicates with no
  host-clock or I/O calls, so this namespace stays trivially portable
  across Clojure/ClojureScript. Callers that need the current time (see
  `metal-detector-calibration-overdue?`) obtain it themselves via a
  `:clj`/`:cljs` reader-conditional at the call site (see
  `mealops.governor`)."
  (:require [clojure.set :as set]))

(defn core-cook-temp-below-minimum?
  "Independently verify that the batch's actual core (thermal-centre)
  cook temperature did not fall below the product's minimum required
  lethality temperature (CCP1). Below the product's minimum indicates
  the cook step may not have achieved sufficient pathogen kill -- a
  hard food-safety hazard, never overridable by advisor confidence."
  [actual-celsius min-celsius]
  (< actual-celsius min-celsius))

(defn chill-time-exceeds-max?
  "Independently verify that the batch's actual chill/freeze-down time
  (from cook temperature down through the microbial \"danger zone\" to
  safe cold-storage temperature, CCP2) did not exceed the product's
  maximum allowed window. Exceeding the window risks spore-forming
  pathogen outgrowth (e.g. Clostridium perfringens, Bacillus cereus)
  during the slow cool-down."
  [actual-minutes max-minutes]
  (> actual-minutes max-minutes))

(defn cold-storage-temp-out-of-range?
  "Independently verify that the batch's actual cold-storage/transport
  temperature falls within the product's safe cold-chain window. Out of
  range (a cold-chain break) risks pathogen growth for chilled product
  or partial thaw/refreeze quality and safety loss for frozen product."
  [actual-celsius min-celsius max-celsius]
  (or (< actual-celsius min-celsius)
      (> actual-celsius max-celsius)))

(defn shelf-life-exceeded?
  "Independently verify that the batch's elapsed time since production
  has not exceeded the product's maximum shelf-life hours. Exceeding
  shelf life is a use-by-date violation -- the batch's safety margin
  against pathogen growth under correct cold-chain conditions is
  exhausted."
  [actual-hours-elapsed max-hours]
  (> actual-hours-elapsed max-hours))

(defn water-activity-exceeds-max?
  "Independently verify that the batch's actual water activity (Aw) does
  not exceed the product's maximum allowable level. Higher Aw enables
  faster microbial growth if the cold chain is broken."
  [actual-aw max-aw]
  (> actual-aw max-aw))

(defn ph-level-exceeds-max?
  "Independently verify that the batch's actual pH does not exceed the
  product's maximum allowable level. For reduced-oxygen-packaged
  (vacuum/MAP-sealed) cook-chill product, insufficient acidification
  (pH above the product's ceiling) removes one of the recognised
  secondary controls against non-proteolytic Clostridium botulinum
  growth."
  [actual-ph max-ph]
  (> actual-ph max-ph))

(defn metal-detector-calibration-overdue?
  "Independently verify that the metal-detection/X-ray inspection
  equipment (catches tramp metal, glass, or dense-plastic fragments
  before the finished product ships) was calibrated within the last 24
  hours. Prepared-meal/ready-dish lines run frequent, shift-based
  recalibration (unlike lower-throughput confectionery lines), so this
  actor's reference interval is daily rather than the longer intervals
  used elsewhere in cloud-itonami. `last-calibration-epoch-ms` and
  `now-epoch-ms` are both epoch milliseconds -- callers obtain `now` via
  a `:clj`/`:cljs` reader-conditional, keeping this namespace free of
  any host-clock call."
  [last-calibration-epoch-ms now-epoch-ms]
  (> (- now-epoch-ms last-calibration-epoch-ms)
     (* 24 60 60 1000)))

(defn weight-variance-excessive?
  "Independently verify that a batch's finished-product weight variance
  (drift from target fill/portion weight, in grams) does not exceed the
  maximum tolerance. Excessive variance indicates the portioning/filling
  line is out of calibration or the fill weight was measured
  incorrectly -- also a labeled-net-quantity compliance concern."
  [actual-variance-grams max-variance-grams]
  (> actual-variance-grams max-variance-grams))

(defn allergen-label-mismatch?
  "True when the batch's actual cross-contact allergen risk set (e.g.
  milk, egg, tree nuts, peanuts, soy, gluten, crustaceans, fish --
  from shared prep surfaces/equipment on a mixed-menu prepared-meal
  line) is not fully covered by `declared-allergens` (mislabeling /
  under-declaration risk -- a genuine food-safety hazard for allergic
  consumers). Declaring MORE than the actual risk set is conservative
  and never a risk."
  [cross-contact-risk declared-allergens]
  (boolean
   (seq (set/difference (set cross-contact-risk) (set declared-allergens)))))

(defn foreign-material-detected?
  "Independently verify a batch's foreign-material-detection result
  (metal, glass, or dense-plastic fragments caught by metal-detector/
  X-ray inspection). Any detection is a genuine physical hazard -- this
  predicate simply coerces the raw fact to a boolean so the Governor's
  check functions stay uniform in shape with every other independently-
  verified physical constraint in this namespace."
  [actual-detected?]
  (boolean actual-detected?))

(defn sanitation-score-insufficient?
  "Independently verify that the plant's pre-production sanitation/
  pest-control score meets the minimum required. Score is 0-100,
  assessed by a third-party auditor against food-safety sanitation and
  cross-contamination-control standards -- a significant HACCP concern
  for mixed-menu prepared-meal lines handling multiple raw proteins and
  allergens on shared equipment."
  [actual-score min-score-required]
  (< actual-score min-score-required))

(defn packaging-seal-compromised?
  "Independently verify a batch's packaging seal-integrity inspection
  result. Prepared-meal/ready-dish cook-chill product safety relies on
  intact vacuum/modified-atmosphere (MAP) packaging to maintain its
  reduced-oxygen environment and cold-chain assumptions -- a compromised
  seal allows aerobic recontamination and undermines the shelf-life
  calculation entirely. This predicate simply coerces the raw fact to a
  boolean so the Governor's check functions stay uniform in shape."
  [actual-compromised?]
  (boolean actual-compromised?))
