(ns mealops.governor
  "MealOps Governor -- the independent compliance layer that earns the
  MealOpsAdvisor the right to commit. The LLM has no notion of:
    - Whether a batch's core (thermal-centre) cook temperature reached the
      product's minimum required lethality temperature (CCP1)
    - Whether the batch's chill/freeze-down time (cook temperature through
      the microbial \"danger zone\" to safe cold-storage temperature, CCP2)
      exceeded the product's maximum allowed window
    - Whether the batch's cold-storage/transport temperature stayed within
      the product's safe cold-chain window (a cold-chain break)
    - Whether the batch's elapsed time since production exceeded the
      product's maximum shelf-life hours (a use-by-date violation)
    - Whether the batch's water activity (Aw) exceeds the product's
      maximum allowable level
    - Whether the batch's pH exceeds the product's maximum allowable
      level (insufficient acidification for reduced-oxygen-packaged
      product's secondary botulinum control)
    - Whether foreign material (metal/glass/dense-plastic fragments) was
      detected in the batch
    - Whether the metal-detector/X-ray inspection equipment calibration
      is current
    - Whether final product weight variance is acceptable
    - Whether allergen cross-contact labeling is complete and accurate
    - Whether plant sanitation/cross-contamination-control score is passed
    - Whether the batch's packaging seal (vacuum/MAP) integrity is intact
    - Whether an open food-safety concern has been resolved
    - Whether the plant/batch record was independently verified and
      registered before any proposal is made against it

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct cook-line/chill-freeze-line/packaging-line control (NEVER
  done by this actor -- cook, chill/freeze, and packaging-line operation
  remain exclusive to plant staff), the Governor operates on batch
  metadata: provenance, processing parameters, sanitation records, and
  food-safety flags. This is plant-operations coordination, not process
  control.

  CRITICAL: Any proposal involving food-safety concerns (HACCP critical-
  limit deviation, allergen cross-contact, cold-chain break) ALWAYS
  escalates to human operator for final sign-off. The LLM's confidence is
  never sufficient for food-safety decisions.

  Hard violations (always HOLD, no override):
    1. Operation outside the closed allowlist (includes any proposal
       that would touch cook-line/chill-freeze-line/packaging-line
       control or food-safety certification)
    2. Proposal asserting an `:effect` other than `:propose`
    3. Plant/batch record not independently verified/registered before
       any proposal is made against it
    4. No jurisdiction citation (jurisdiction unknown -> can't verify reqs)
    5. Evidence incomplete (missing required-evidence per jurisdiction)
    6. Core cook temperature below the product's minimum lethality
       temperature (CCP1)
    7. Chill/freeze-down time exceeds the product's maximum window (CCP2)
    8. Cold-storage/transport temperature out of the product's safe
       cold-chain window (a cold-chain break)
    9. Shelf life exceeded (elapsed time since production beyond the
       product's maximum shelf-life hours)
   10. Water activity exceeds the product's maximum allowable level
   11. pH exceeds the product's maximum allowable level (under-acidified
       reduced-oxygen-packaged product)
   12. Foreign material detected (metal/glass/dense-plastic fragments)
   13. Metal-detector/X-ray calibration overdue
   14. Weight variance excessive (portioning/filling line drift risk)
   15. Allergen cross-contact mismatch (labeling / food-safety violation)
   16. Plant sanitation/cross-contamination-control score insufficient
   17. Packaging seal (vacuum/MAP) compromised
   18. Food-safety flag unresolved (open concern, escalate required)
   19. Batch already processed (double-commit guard)
   20. Shipment already finalized (double-commit guard)

  Soft gates (always escalate for human):
    - Low confidence
    - Real actuation (`:log-production-batch`, `:coordinate-shipment`)
    - `:flag-food-safety-concern` (never auto-resolved by confidence alone)

  This design mirrors `chocops.governor` (ISIC 1073) and `bakeops.governor`
  but specializes on prepared-meals/ready-dish-specific concerns: cook-
  chill/cook-freeze HACCP critical-control-points (cook lethality,
  chill-time, cold-chain, shelf life, water activity, pH/ROP botulinum
  control, packaging-seal integrity) -- rather than confectionery-
  specific tempering-curve/cadmium/viscosity processing safety or
  bakery-specific concerns."
  (:require [mealops.facts :as facts]
            [mealops.registry :as registry]
            [mealops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a batch into production records (`:log-production-batch`) and
  coordinating shipment of finished product (`:coordinate-shipment`) are
  the two real-world actuation events this actor performs. Both require
  plant operator sign-off."
  #{:log-production-batch :coordinate-shipment})

(def always-escalate-ops
  "Operations that always require human sign-off, even when the Governor's
  hard checks are clean and confidence is high: the two high-stakes
  actuation events (`high-stakes`) plus `:flag-food-safety-concern` --
  a food-safety concern (HACCP critical-limit deviation, allergen
  cross-contact, cold-chain break) is never auto-resolved by advisor
  confidence alone, it always needs a human look."
  (conj high-stakes :flag-food-safety-concern))

(def allowed-ops
  "Closed allowlist of proposal operations this actor may ever make. Any
  proposal for an operation outside this set -- most importantly direct
  cook-line/chill-freeze-line/packaging-line control (cook line,
  chill/blast-freeze line, and packaging line) or food-safety
  certification authority -- is a hard, permanent block: this actor
  coordinates plant operations, it does not operate equipment and it
  does not certify food safety."
  #{:log-production-batch :schedule-maintenance :flag-food-safety-concern :coordinate-shipment})

;; ────────────────────────── Checks ──────────────────────────

(defn- op-not-allowed-violations
  "HARD, permanent block: any proposal outside the closed operation
  allowlist (e.g. direct cook-line/chill-freeze-line/packaging-line
  control, or a food-safety certification action) is refused
  unconditionally -- this actor has no authority to make such a
  proposal at all, let alone commit it."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのactorの許可された提案種別 (log-production-batch/"
                  "schedule-maintenance/flag-food-safety-concern/coordinate-shipment) "
                  "に含まれない -- 調理/急速冷却冷凍/包装ライン制御やfood-safety認証権限はこのactorに無い")}]))

(defn- effect-not-propose-violations
  "HARD invariant: this actor's proposals are always `:effect :propose` --
  it never claims direct write/actuation authority for itself. A proposal
  asserting any other effect is refused unconditionally."
  [_request proposal]
  (when-let [effect (:effect proposal)]
    (when (not= effect :propose)
      [{:rule :effect-not-propose
        :detail (str "この actor の提案は :propose 以外の :effect を持てない (got " effect ")")}])))

(defn- batch-not-registered-violations
  "HARD invariant: a plant/batch record must be independently verified/
  registered in the store BEFORE ANY proposal (not just shipment
  coordination) can be made against it -- this actor coordinates
  operations for an already-registered batch, it never invents or
  self-registers one from an unverified proposal."
  [{:keys [op subject]} st]
  (when (contains? allowed-ops op)
    (when-not (store/production-batch st subject)
      [{:rule :batch-not-registered
        :detail (str subject " はプラントに登録されたバッチ記録が無い -- 提案は進められない")}])))

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's food-safety requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-production-batch :coordinate-shipment :flag-food-safety-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式仕様の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-production-batch`, verify the batch's evidence checklist is
  complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when-not (and b
                     (facts/required-evidence-satisfied?
                      (:jurisdiction b)
                      (:evidence-checklist b)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(raw-material-intake-record/cook-lethality-log/chill-time-log/cold-storage-temp-log/water-activity-test/ph-test等)が充足していない状態での提案"}]))))

(defn- core-cook-temp-below-minimum-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  core cook temperature reached the product's minimum required
  lethality temperature (CCP1) via
  `registry/core-cook-temp-below-minimum?`. Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:core-cook-temp-c b)
                 (registry/core-cook-temp-below-minimum?
                  (:core-cook-temp-c b)
                  (:core-cook-temp-min-c p)))
        [{:rule :core-cook-temp-below-minimum
          :detail (str subject " の中心温度(" (:core-cook-temp-c b)
                      "℃)が製品規格の最低致死温度(" (:core-cook-temp-min-c p)
                      "℃)を下回る -- バッチ登録提案は進められない")}]))))

(defn- chill-time-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  chill/freeze-down time (CCP2) did not exceed the product's maximum
  window via `registry/chill-time-exceeds-max?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:chill-time-minutes b)
                 (registry/chill-time-exceeds-max?
                  (:chill-time-minutes b)
                  (:chill-time-max-minutes p)))
        [{:rule :chill-time-exceeds-max
          :detail (str subject " の急速冷却/冷凍時間(" (:chill-time-minutes b)
                      "分)が製品規格上限(" (:chill-time-max-minutes p)
                      "分)を超過 -- バッチ登録提案は進められない")}]))))

(defn- cold-storage-temp-out-of-range-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  cold-storage/transport temperature falls within the product's safe
  cold-chain window via `registry/cold-storage-temp-out-of-range?`.
  Evaluated UNCONDITIONALLY -- a cold-chain break is one of the most
  common root causes of prepared-meal foodborne-illness incidents."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:cold-storage-temp-c b)
                 (registry/cold-storage-temp-out-of-range?
                  (:cold-storage-temp-c b)
                  (:cold-storage-temp-min-c p)
                  (:cold-storage-temp-max-c p)))
        [{:rule :cold-storage-temp-out-of-range
          :detail (str subject " の低温保管/輸送温度(" (:cold-storage-temp-c b)
                      "℃)が製品規格範囲外(コールドチェーン逸脱) -- バッチ登録提案は進められない")}]))))

(defn- shelf-life-exceeded-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  elapsed time since production has not exceeded the product's maximum
  shelf-life hours via `registry/shelf-life-exceeded?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:shelf-life-hours-elapsed b)
                 (registry/shelf-life-exceeded?
                  (:shelf-life-hours-elapsed b)
                  (:max-shelf-life-hours p)))
        [{:rule :shelf-life-exceeded
          :detail (str subject " の経過時間(" (:shelf-life-hours-elapsed b)
                      "時間)が製品規格の消費期限(" (:max-shelf-life-hours p)
                      "時間)を超過 -- バッチ登録提案は進められない")}]))))

(defn- water-activity-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  water activity (Aw) does not exceed the product's maximum via
  `registry/water-activity-exceeds-max?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:water-activity b)
                 (registry/water-activity-exceeds-max?
                  (:water-activity b)
                  (:water-activity-max p)))
        [{:rule :water-activity-exceeds-max
          :detail (str subject " の水分活性(" (:water-activity b)
                      ")が製品規格上限(" (:water-activity-max p)
                      ")を超過 -- バッチ登録提案は進められない")}]))))

(defn- ph-level-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  pH does not exceed the product's maximum via
  `registry/ph-level-exceeds-max?`. Evaluated UNCONDITIONALLY -- for
  reduced-oxygen-packaged (vacuum/MAP) cook-chill product this is a
  recognised secondary control against non-proteolytic Clostridium
  botulinum growth."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:ph-level b)
                 (registry/ph-level-exceeds-max?
                  (:ph-level b)
                  (:ph-max p)))
        [{:rule :ph-level-exceeds-max
          :detail (str subject " のpH(" (:ph-level b)
                      ")が製品規格上限(" (:ph-max p)
                      ")を超過(酸性化不足) -- バッチ登録提案は進められない")}]))))

(defn- foreign-material-detected-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the batch's own
  foreign-material-detection result via `registry/foreign-material-
  detected?`. A detection on THIS batch's own inspection is a hard,
  physical-hazard block -- distinct from `food-safety-flag-unresolved-
  violations` below, which covers a separately-raised, not-yet-resolved
  concern."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (registry/foreign-material-detected? (:foreign-material-detected? b)))
        [{:rule :foreign-material-detected
          :detail (str subject " で異物(金属/ガラス/硬質プラスチック混入)が検出された -- バッチ登録提案は進められない")}]))))

(defn- now-epoch-ms
  "Current time in epoch milliseconds, portable across Clojure/
  ClojureScript. Isolated to this single call site so the rest of the
  namespace (and all of `mealops.registry`) stays free of host-clock
  calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- metal-detector-calibration-overdue-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the
  metal-detector/X-ray inspection equipment's calibration is current
  (recalibration required every 24 hours)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:metal-detector-last-calibration-date b)
                 (registry/metal-detector-calibration-overdue? (:metal-detector-last-calibration-date b) (now-epoch-ms)))
        [{:rule :metal-detector-calibration-overdue
          :detail (str subject " の異物検出機(金属探知機/X線検査機)校正が期限切れ -- バッチ登録提案は進められない")}]))))

(defn- weight-variance-excessive-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the weight variance."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:weight-variance-grams b)
                 (registry/weight-variance-excessive? (:weight-variance-grams b) 20))
        [{:rule :weight-variance-excessive
          :detail (str subject " の重量分散(" (:weight-variance-grams b)
                      "g)が許容範囲(20g)を超過 -- バッチ登録提案は進められない")}]))))

(defn- allergen-label-mismatch-violations
  "For `:log-production-batch`, INDEPENDENTLY verify allergen
  cross-contact declaration completeness via
  `registry/allergen-label-mismatch?` -- a common recall reason in
  mixed-menu prepared-meal manufacturing (milk/egg/tree-nut/peanut/soy/
  gluten/crustacean/fish cross-contact on shared prep lines)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:cross-contact-risk b)
                 (registry/allergen-label-mismatch? (:cross-contact-risk b) (:declared-allergens b)))
        [{:rule :allergen-label-mismatch
          :detail (str subject " のアレルゲン交差接触(cross-contact)宣言が不完全 -- バッチ登録提案は進められない")}]))))

(defn- sanitation-score-insufficient-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the plant's
  sanitation/cross-contamination-control score meets minimum
  requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:sanitation-score b)
                 (registry/sanitation-score-insufficient? (:sanitation-score b) 75))
        [{:rule :sanitation-score-insufficient
          :detail (str subject " のプラント衛生/交差汚染管理スコア(" (:sanitation-score b)
                      ")が最低要件(75)を下回る -- バッチ登録提案は進められない")}]))))

(defn- packaging-seal-compromised-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the batch's
  packaging seal (vacuum/MAP) integrity inspection result via
  `registry/packaging-seal-compromised?`. A compromised seal undermines
  both the reduced-oxygen-packaging safety assumptions and the batch's
  shelf-life calculation."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (registry/packaging-seal-compromised? (:packaging-seal-compromised? b)))
        [{:rule :packaging-seal-compromised
          :detail (str subject " の包装シール(真空/ガス置換)完全性が損なわれている -- バッチ登録提案は進められない")}]))))

(defn- food-safety-flag-unresolved-violations
  "An unresolved food-safety flag is a HARD, un-overridable hold.
  Food-safety concerns (HACCP critical-limit deviation, allergen
  cross-contact, cold-chain break) raised during production or
  inspection MUST be resolved before the batch can be logged. Evaluated
  UNCONDITIONALLY at `:log-production-batch`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and (true? (:safety-concern-raised? b))
                 (not (true? (:safety-concern-resolved? b))))
        [{:rule :food-safety-flag-unresolved
          :detail (str subject " は未解決の食品安全フラグがある -- バッチ登録提案は進められない")}]))))

(defn- already-processed-violations
  "For `:log-production-batch`, refuse to process the SAME batch twice, off
  a dedicated `:processed?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (when (store/batch-already-processed? st subject)
      [{:rule :already-processed
        :detail (str subject " は既に登録済み")}])))

(defn- already-shipment-finalized-violations
  "For `:coordinate-shipment`, refuse to finalize the SAME batch's shipment
  twice, off a dedicated `:shipment-finalized?` fact."
  [{:keys [op subject]} st]
  (when (= op :coordinate-shipment)
    (when (store/batch-shipment-finalized? st subject)
      [{:rule :already-shipment-finalized
        :detail (str subject " は既に出荷確定済み")}])))

(defn check
  "Censors a MealOpsAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  Stakes (high-stakes actuation vs. always-escalate) are read off the
  REQUEST's `:op` -- not off the proposal -- since the operation being
  proposed (not the advisor's self-reported stake) is what determines
  whether a human must sign off."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (batch-not-registered-violations request st)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (core-cook-temp-below-minimum-violations request st)
                           (chill-time-exceeds-max-violations request st)
                           (cold-storage-temp-out-of-range-violations request st)
                           (shelf-life-exceeded-violations request st)
                           (water-activity-exceeds-max-violations request st)
                           (ph-level-exceeds-max-violations request st)
                           (foreign-material-detected-violations request st)
                           (metal-detector-calibration-overdue-violations request st)
                           (weight-variance-excessive-violations request st)
                           (allergen-label-mismatch-violations request st)
                           (sanitation-score-insufficient-violations request st)
                           (packaging-seal-compromised-violations request st)
                           (food-safety-flag-unresolved-violations request st)
                           (already-processed-violations request st)
                           (already-shipment-finalized-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        actuation? (boolean (high-stakes (:op request)))
        escalate-op? (boolean (always-escalate-ops (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not escalate-op?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? escalate-op?))
     :high-stakes? actuation?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
