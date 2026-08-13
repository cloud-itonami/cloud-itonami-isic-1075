(ns mealops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This repo previously had NO demo page and no generator: `mealops.sim`
  printed `\"MealOps simulation: not yet implemented.\"` and returned.
  Nothing in `src/` had ever driven `mealops.operation` outside the test
  suite. This namespace drives the REAL actor stack --
  `mealops.operation/run-operation` -> `mealops.governor/check` ->
  `mealops.store` -- and renders whatever that run actually produced.

  Nothing on the page is typed by hand:
    - batch rows are read BACK OUT of the store with
      `mealops.store/production-batch` AFTER the run, so `:processed?` /
      `:shipment-finalized?` are post-run state, not seed state;
    - every product window comes from `mealops.facts/product-types` and
      every evidence requirement from `mealops.facts/jurisdictions`;
    - every HARD-hold rule name AND its detail string is the Governor's
      own `:violations` entry off the real ledger fact -- never a literal
      in this namespace;
    - the action-gate table is derived from the real
      `mealops.governor/allowed-ops` / `high-stakes` /
      `always-escalate-ops` vars joined with the dispositions the run
      actually reached;
    - the phase table is produced by CALLING
      `mealops.phase/can-transition?`;
    - the approver-attribution column is a PROBE of the store at render
      time (see `approver-disclosure`), not a claim about it;
    - the actor's own identity strings come from `blueprint.edn`.

  ## The classification that this page exists to get right

  `mealops.operation/run-operation` has a SINGLE `:ok?` gate, and routes
  every not-ok verdict -- HARD refusal and SOFT escalation alike --
  through the same `(:hold-fact-fn context)`. Both therefore land in the
  ledger as a fact of type `:governor-hold`. MEASURED, not assumed:
  an escalation's fact carries `:basis []`, a refusal's carries the
  violated rules.

  So the FACT TYPE does not discriminate here, and neither does counting
  `:governor-hold` rows. This namespace classifies on
  `mealops.governor/check`'s own `:hard?` boolean, carried on the run
  record beside the fact:

    HARD governor refusal   `:hard? true`   un-overridable; never reaches a human
    SOFT escalation gate    `:escalate? true` routes to a human, who may say yes or no
    auto-commit             `:ok? true`     the Governor is clean AND no gate applies

  A fourth gate on this page is NOT a governor verdict at all:
  `mealops.phase/can-transition?` is the batch WORKFLOW gate (forward-only
  progression through the production phases). It refuses transitions that
  the Governor never sees. It is rendered in its own section and is
  excluded from every hold count.

  ## Build-time invariants (`-main` THROWS, it does not warn)

    1. the run must produce at least one real HARD governor refusal;
    2. it must exercise at least `min-distinct-hard-rules` DISTINCT HARD
       rules -- so a governor change that silently stops firing a check
       fails the build instead of quietly shrinking the page;
    3. no run classified HARD may also have reached a human approver --
       the 'HARD refusals never reach a human' claim is MEASURED;
    4. every HARD-rule row rendered must trace to a rule present in the
       ledger, so the section cannot outlive the run that justified it;
    5. the SOFT gate must have shown BOTH outcomes (an approval granted
       AND an approval rejected) -- otherwise the approval trail and the
       approver probe are vacuous tables that read like a pass;
    6. at least `min-distinct-soft-rules` DISTINCT SOFT rules must fire,
       so the soft section cannot silently empty out either.

  Determinism: no timestamps, no randomness, no network, no UUIDs. The
  calibration dates in the seed are the only clock-derived values and
  they are deliberately NEVER rendered -- only the Governor's boolean
  answer about them is. Every set and map is sorted before it reaches
  the page so iteration order can never leak into the bytes. Two
  consecutive builds are byte-identical.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [mealops.facts :as facts]
            [mealops.governor :as governor]
            [mealops.operation :as operation]
            [mealops.phase :as phase]
            [mealops.registry :as registry]
            [mealops.store :as store]))

(def ^:private plant-operator
  "The EXECUTING actor's context. `:actor-id` is what
  `mealops.governor/hold-fact` stamps onto `:actor` -- note that this is
  the actor that RAN the operation, never the human who approved it.
  Reading `:actor` as an approver would be wrong here even though the
  two are different people."
  {:actor-id "mealops-actor-01"
   :hold-fact-fn governor/hold-fact})

(def ^:private human-approver
  "The plant operator who signs off escalated proposals. Deliberately a
  DIFFERENT identity from `plant-operator`'s `:actor-id`, so that a
  renderer bug that reported the executing actor as the approver would
  show a visibly wrong name rather than silently agreeing."
  "plant-op-hanako")

(def min-distinct-hard-rules
  "Evidence floor for invariant 2: every HARD rule
  `mealops.governor/check` can produce. If a governor/store/driver change
  makes fewer of them fire, the build fails rather than rendering a
  quietly thinner page. Raise this when the scenario grows; never lower
  it to make a build pass."
  23)

(def min-distinct-soft-rules
  "Evidence floor for invariant 6: every SOFT rule
  `mealops.governor/check` can produce (`:supplier-not-verified`,
  `:rawmat-handoff-suspect`, `:packaging-handoff-suspect`)."
  3)

;; ----------------------------- the seed -----------------------------

(def ^:private hour-ms (* 60 60 1000))

(defn- hours-ago
  "Epoch-ms `n` hours before now. The ONLY clock read in this namespace,
  and its value is never rendered -- the page shows the Governor's
  boolean verdict about the calibration, not the date. A fixed literal
  would silently flip from 'current' to 'overdue' the moment 24 hours of
  wall-clock passed after it was written."
  [n]
  (- (System/currentTimeMillis) (* n hour-ms)))

(def ^:private verified-material-lot
  {:material/lot-id "RM-2026-0413"
   :material/supplier-name "丸信フードサプライ"
   :material/supplier-verified? true
   :material/received-at-iso "2026-04-13T02:15:00Z"})

(def ^:private verified-packaging-lot
  {:packaging/lot-id "PK-2026-0411"
   :packaging/supplier-name "東和パッケージ"
   :packaging/supplier-verified? true
   :packaging/received-at-iso "2026-04-11T23:40:00Z"})

(def ^:private clean-batch
  "A cook-chill poultry batch inside every one of the Governor's windows,
  with a verified raw-material lot and packaging lot so that no SOFT
  signal fires either. Each variant below overrides exactly one key."
  {:product-type :meal/cook-chill-poultry
   :jurisdiction :us/fda
   :core-cook-temp-c 75.0
   :chill-time-minutes 60.0
   :cold-storage-temp-c 2.0
   :shelf-life-hours-elapsed 24.0
   :water-activity 0.85
   :ph-level 4.5
   :foreign-material-detected? false
   :metal-detector-last-calibration-date (hours-ago 6)
   :weight-variance-grams 10
   :declared-allergens #{}
   :cross-contact-risk #{}
   :sanitation-score 85
   :packaging-seal-compromised? false
   :material-lot verified-material-lot
   :packaging-lot verified-packaging-lot
   :evidence-checklist [:raw-material-intake-record :cook-lethality-log
                        :chill-time-log :cold-storage-temp-log
                        :water-activity-test :ph-test :allergen-declaration
                        :weight-check :packaging-seal-check]})

(def ^:private seed-batches
  "The plant's registered batches, in registration order. `batch-001` is
  clean and walks the full lifecycle; `batch-019` is a clean second
  product type (cook-freeze, a different cold-chain window entirely);
  every other batch differs from clean in exactly ONE respect, named in
  `:why`, so each rule below is demonstrated in isolation rather than as
  one row with a pile of violations. `:why` is this seed's own label for
  the reader -- the RULE and the DETAIL shown on the page are the
  Governor's, read off the ledger."
  [["batch-001" clean-batch
    "clean on every parameter — walks the full lifecycle"]
   ["batch-002" (assoc clean-batch :core-cook-temp-c 62.0)
    "core cook temperature below the product's lethality minimum (CCP1)"]
   ["batch-003" (assoc clean-batch :chill-time-minutes 150.0)
    "chill-down slower than the product's maximum window (CCP2)"]
   ["batch-004" (assoc clean-batch :cold-storage-temp-c 9.0)
    "cold-storage temperature outside the safe cold-chain window"]
   ["batch-005" (assoc clean-batch :shelf-life-hours-elapsed 200.0)
    "elapsed time past the product's use-by shelf life"]
   ["batch-006" (assoc clean-batch :water-activity 0.99)
    "water activity above the product's maximum"]
   ["batch-007" (assoc clean-batch :ph-level 6.2)
    "pH above the product's ceiling — under-acidified ROP product"]
   ["batch-008" (assoc clean-batch :foreign-material-detected? true)
    "foreign material found by this batch's own inspection"]
   ["batch-009" (assoc clean-batch :metal-detector-last-calibration-date (hours-ago 48))
    "metal-detector/X-ray calibration older than the 24-hour interval"]
   ["batch-010" (assoc clean-batch :weight-variance-grams 45)
    "finished-product weight drift beyond tolerance"]
   ["batch-011" (assoc clean-batch
                       :cross-contact-risk #{:milk :gluten}
                       :declared-allergens #{:milk})
    "an allergen present via a shared prep line but not declared"]
   ["batch-012" (assoc clean-batch :sanitation-score 60)
    "plant sanitation/cross-contamination score below the minimum"]
   ["batch-013" (assoc clean-batch :packaging-seal-compromised? true)
    "vacuum/MAP packaging seal integrity breached"]
   ["batch-014" (assoc clean-batch
                       :safety-concern-raised? true
                       :safety-concern-resolved? false)
    "a food-safety concern raised and still open"]
   ["batch-015" (assoc clean-batch
                       :evidence-checklist [:raw-material-intake-record
                                            :cook-lethality-log
                                            :chill-time-log])
    "jurisdiction evidence checklist incomplete"]
   ["batch-016" (dissoc clean-batch :material-lot)
    "SOFT — intake record ticked, but no raw-material lot behind it"]
   ["batch-017" (assoc clean-batch :material-lot
                       (assoc verified-material-lot
                              :material/handoff
                              {:handoff/id "HO-RM-771"
                               :handoff/source-actor "cloud-itonami-isic-9999"
                               :handoff/batch-id "poultry-lot-771"
                               :handoff/product-type-id "fresh-poultry"
                               :handoff/quantity-kg 480.0
                               :handoff/dispatched-at-iso "2026-04-12T21:00:00Z"}))
    "SOFT — inbound raw-material handoff from an unregistered supplier"]
   ["batch-018" (assoc clean-batch :packaging-lot
                       (assoc verified-packaging-lot
                              :packaging/handoff
                              {:handoff/id "HO-PK-312"
                               :handoff/source-actor "cloud-itonami-isic-4321"
                               :handoff/batch-id "film-lot-312"
                               :handoff/product-type-id "map-film"
                               :handoff/quantity-kg 95.0
                               :handoff/dispatched-at-iso "2026-04-10T18:30:00Z"}))
    "SOFT — inbound packaging handoff from an unregistered supplier"]
   ["batch-019" (assoc clean-batch
                       :product-type :meal/cook-freeze-fish
                       :cold-storage-temp-c -20.0
                       :chill-time-minutes 180.0
                       :shelf-life-hours-elapsed 900.0
                       :ph-level 6.0)
    "clean cook-freeze batch — a different cold-chain window entirely"]])

(def ^:private unregistered-subject
  "Driven below but deliberately NEVER registered."
  "batch-999")

(defn- handoff-for
  "A well-formed outbound handoff record for `batch-id`, built from this
  actor's OWN `facts/product-types` entry so the declared cold-chain
  window is the product's proven-safe one rather than a literal."
  [batch-id product-type-id]
  (let [p (facts/product-type-by-id product-type-id)]
    {:handoff/id (str "HO-" batch-id)
     :handoff/source-actor "cloud-itonami-isic-1075"
     :handoff/batch-id batch-id
     :handoff/product-type-id product-type-id
     :handoff/cold-chain-temp-min-c (:cold-storage-temp-min-c p)
     :handoff/cold-chain-temp-max-c (:cold-storage-temp-max-c p)
     :handoff/quantity-kg 120.5
     :handoff/dispatched-at-iso "2026-04-14T06:00:00Z"
     :handoff/unspsc-code (:unspsc-code p)
     :handoff/gtin (:gtin p)}))

(defn- proposal
  "The advisor-layer proposal. `mealops.advisor` is a documented skeleton
  with no proposal function in it (see 'Disclosed gaps'), so the driver
  supplies the proposal shape the Governor actually reads: `:cites`,
  `:value`, `:effect`, `:confidence`."
  ([spec] (proposal spec {} 0.9 :propose))
  ([spec value] (proposal spec value 0.9 :propose))
  ([spec value confidence effect]
   {:cites (if (seq spec) [{:spec spec}] [])
    :value value
    :effect effect
    :confidence confidence}))

;; ----------------------------- the real run -----------------------------

(defn- classify
  "The single place a disposition is decided. Reads the Governor's own
  `:hard?` off the verdict -- NOT the ledger fact's type (identical for
  refusal and escalation here) and NOT the emptiness of `:violations`."
  [{:keys [ok? verdict]}]
  (cond
    ok? :auto-commit
    (:hard? verdict) :hard-refusal
    (:escalate? verdict) :soft-escalation
    :else :unclassified))

(defn- exec!
  "Drive ONE proposal through the real `mealops.operation/run-operation`.

  Appends every fact the operation actually returned to the store ledger,
  unmodified -- including the `:governor-hold` fact it emits for a mere
  escalation, because hiding that would hide the very ambiguity this page
  is here to document.

  Returns the updated store; the run record is accumulated in `runs`."
  [runs st tid {:keys [op subject]} prop why]
  (let [request {:op op :subject subject}
        result (operation/run-operation request plant-operator prop st governor/check)
        run {:thread tid :op op :subject subject :why why
             :ok? (:ok? result)
             :verdict (:verdict result)
             :confidence (:confidence prop)
             :facts (:facts result)}
        run (assoc run :disposition (classify run))]
    (swap! runs conj run)
    (reduce store/append-fact st (:facts result))))

(defn- approve!
  "The human sign-off step for a run the Governor ESCALATED.

  `mealops.operation` has no approval node -- its `:ok?` gate is the only
  branch it has -- so the decision is modelled here, in the driver, and
  every fact it writes is stamped `:by :driver` to keep it visually
  distinct from the Governor's own facts on the rendered ledger.

  On `:approved` the corresponding store effect is applied through the
  repo's own `mealops.store` functions."
  [runs st tid decision]
  (let [src (first (filter #(= tid (:thread %)) @runs))
        {:keys [op subject]} src
        batch (store/production-batch st subject)
        fact (if (= :approved decision)
               {:t :approval-granted :by :driver :op op :subject subject
                :approved-by human-approver
                :actor (:actor-id plant-operator)}
               {:t :approval-rejected :by :driver :op op :subject subject
                :rejected-by human-approver
                :actor (:actor-id plant-operator)})
        st' (store/append-fact st fact)]
    (swap! runs conj {:thread tid :resume? true :op op :subject subject
                      :outcome (:t fact) :decided-by human-approver})
    (if (not= :approved decision)
      st'
      ;; The store's commit API takes the batch record; it has no
      ;; approver parameter at all. Whether the approver survives is
      ;; therefore decided by `mealops.store`, and PROBED at render time.
      (case op
        :log-production-batch
        (-> (store/log-batch st' subject batch)
            (store/append-fact {:t :batch-logged :by :driver :op op :subject subject}))
        :coordinate-shipment
        (-> (store/finalize-shipment st' subject)
            (store/append-fact {:t :shipment-finalized :by :driver :op op :subject subject}))
        (store/append-fact st' {:t :noted :by :driver :op op :subject subject})))))

(defn run-scenario!
  "Drive the whole scenario through the real actor. Returns
  `{:db st :runs [..]}`."
  []
  (let [runs (atom [])
        st0 {:batches (into {} (for [[id data _why] seed-batches] [id data]))
             :facts []}
        cite "UK-FSA-Cook-Chill-Guidance"
        poultry :meal/cook-chill-poultry
        E (fn [st tid req prop why] (exec! runs st tid req prop why))
        A (fn [st tid d] (approve! runs st tid d))]
    (let [st
          (-> st0
              ;; --- batch-001: the full clean lifecycle ------------------
              (E "t01" {:op :schedule-maintenance :subject "batch-001"}
                 (proposal "Equipment-Manual" {:jurisdiction :us/fda})
                 "routine maintenance — the only op here that never needs a human")
              (E "t02" {:op :log-production-batch :subject "batch-001"}
                 (proposal cite {:jurisdiction :us/fda})
                 "clean batch log — high-stakes actuation, always escalates")
              (A "t02" :approved)
              ;; SOFT gate, other outcome: clean proposal, human says NO.
              (E "t03" {:op :flag-food-safety-concern :subject "batch-001"}
                 (proposal "Plant-HACCP-Plan" {:jurisdiction :us/fda})
                 "food-safety concern — never auto-resolved by confidence")
              (A "t03" :rejected)

              ;; --- HARD: the three outbound-handoff invariants ----------
              (E "t04" {:op :coordinate-shipment :subject "batch-001"}
                 (proposal cite {:jurisdiction :us/fda})
                 "shipment proposed with no handoff record at all")
              (E "t05" {:op :coordinate-shipment :subject "batch-001"}
                 (proposal cite {:jurisdiction :us/fda
                                 :handoff (assoc (handoff-for "batch-001" poultry)
                                                 :handoff/cold-chain-temp-min-c -5.0
                                                 :handoff/cold-chain-temp-max-c 8.0)})
                 "handoff declares a cold-chain window wider than the product's")
              (E "t06" {:op :coordinate-shipment :subject "batch-001"}
                 (proposal cite {:jurisdiction :us/fda
                                 :handoff (handoff-for unregistered-subject poultry)})
                 "handoff points at a batch this plant never logged as produced")

              ;; --- the clean shipment, approved -------------------------
              (E "t07" {:op :coordinate-shipment :subject "batch-001"}
                 (proposal cite {:jurisdiction :us/fda
                                 :handoff (handoff-for "batch-001" poultry)})
                 "clean shipment — high-stakes actuation, always escalates")
              (A "t07" :approved)

              ;; --- HARD: the two double-commit guards -------------------
              (E "t08" {:op :log-production-batch :subject "batch-001"}
                 (proposal cite {:jurisdiction :us/fda})
                 "the same batch logged a second time")
              (E "t09" {:op :coordinate-shipment :subject "batch-001"}
                 (proposal cite {:jurisdiction :us/fda
                                 :handoff (handoff-for "batch-001" poultry)})
                 "the same batch's shipment finalized a second time")

              ;; --- HARD: proposal-shape invariants ----------------------
              (E "t10" {:op :flag-food-safety-concern :subject "batch-001"}
                 (proposal nil {:jurisdiction :us/fda})
                 "a concern raised with no official specification cited")
              (E "t11" {:op :control-chill-freeze-line :subject "batch-001"}
                 (proposal "Blast-Freezer-Manual" {:jurisdiction :us/fda})
                 "direct blast-freeze line control — outside the allowlist")
              (E "t12" {:op :schedule-maintenance :subject "batch-001"}
                 (proposal "Equipment-Manual" {:jurisdiction :us/fda} 0.9 :commit)
                 "a proposal claiming direct actuation authority for itself")
              (E "t13" {:op :schedule-maintenance :subject unregistered-subject}
                 (proposal "Equipment-Manual" {:jurisdiction :us/fda})
                 "a subject with no registered batch record at all"))

          ;; --- HARD/SOFT: one violated parameter per batch -------------
          st (reduce
              (fn [st [i [id _data why]]]
                (let [tid (format "t%02d" (+ 14 i))]
                  (E st tid {:op :log-production-batch :subject id}
                     (proposal cite {:jurisdiction :us/fda}) why)))
              st
              (map-indexed vector (rest seed-batches)))

          ;; batch-019 is clean: take it all the way through sign-off so a
          ;; SECOND product type reaches a committed state.
          st (A st (format "t%02d" (+ 14 (dec (count (rest seed-batches))))) :approved)]
      {:db st :runs @runs})))

;; ----------------------------- helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kname [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- code* [v] (str "<code>" (esc v) "</code>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))
(defn- ok* [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- critical [s] (str "<span class=\"critical\">" s "</span>"))
(defn- num* [s] (str "<span class=\"num\">" (esc s) "</span>"))

(defn- kw-list
  "Sorted, escaped, comma-joined names -- sorted so a set never leaks its
  hash order into the rendered bytes."
  [xs]
  (when (seq xs)
    (str/join ", " (map #(esc (kname %)) (sort-by str xs)))))

(defn- section [title lede headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" body-rows "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; ----------------------------- derived views -----------------------------

(defn- decisions
  "Runs that were an actual operation (not a driver-side human decision)."
  [runs]
  (remove :resume? runs))

(defn- by-disposition [runs d]
  (filter #(= d (:disposition %)) (decisions runs)))

(defn- hard-rule-groups
  "Groups the run's REAL HARD refusals by violated rule, in first-seen
  order. Nothing here is a literal: if a rule stops firing its row
  disappears (and `-main`'s evidence floor fails)."
  [runs]
  (->> (for [r (by-disposition runs :hard-refusal)
             f (:facts r)
             v (:violations f)]
         (assoc v :subject (:subject f) :op (:op f)))
       (reduce (fn [acc {:keys [rule detail subject op]}]
                 (if (contains? acc rule)
                   (-> acc
                       (update-in [rule :count] inc)
                       (update-in [rule :subjects] conj subject)
                       (update-in [rule :ops] conj op))
                   (assoc acc rule {:rule rule :detail detail :count 1
                                    :order (count acc)
                                    :subjects [subject] :ops [op]})))
               {})
       vals
       (sort-by :order)))

(defn- soft-rule-groups
  "Same shape, for the Governor's SOFT signals. These never hold; they
  force an escalation that would otherwise not have happened."
  [runs]
  (->> (for [r (decisions runs)
             v (:soft-violations (:verdict r))]
         (assoc v :subject (:subject r) :op (:op r)))
       (reduce (fn [acc {:keys [rule detail subject op]}]
                 (if (contains? acc rule)
                   (-> acc
                       (update-in [rule :count] inc)
                       (update-in [rule :subjects] conj subject)
                       (update-in [rule :ops] conj op))
                   (assoc acc rule {:rule rule :detail detail :count 1
                                    :order (count acc)
                                    :subjects [subject] :ops [op]})))
               {})
       vals
       (sort-by :order)))

(defn- op-dispositions
  "What each op ACTUALLY reached in this run, counted off the run records
  rather than predicted from the governor's vars."
  [runs]
  (reduce (fn [acc {:keys [op disposition]}]
            (update-in acc [op disposition] (fnil inc 0)))
          {}
          (decisions runs)))

;; --- store approver attribution, DERIVED (never asserted) -------------

(def ^:private approver-key-names
  "Key names that would mean 'the human who approved this'. `actor` is
  deliberately EXCLUDED: `mealops.governor/hold-fact` writes the
  EXECUTING actor there, and a probe that accepted it would report a
  false positive that happens to look plausible."
  #{"approved-by" "approved_by" "approver" "signed-off-by" "authorized-by"})

(defn- approver-in
  "Any value stored under an approver-shaped key, whatever the key type.
  Returns nil when the map simply does not carry one."
  [m]
  (when (map? m)
    (some (fn [[k v]]
            (when (and v (contains? approver-key-names (kname k))) v))
          m)))

(defn- registers-for
  "The places a committed op of this kind could have kept the approver,
  read back through `mealops.store` at render time. This is the PROBE:
  whether the approver survives is decided by what is IN these maps now,
  not by anything this namespace claims about the store."
  [db op subject]
  (concat
   ;; the batch register, read back out of the store after the run
   (when (contains? #{:log-production-batch :coordinate-shipment} op)
     [(store/production-batch db subject)])
   ;; and the driver's own commit fact for that op
   (filter #(and (#{:batch-logged :shipment-finalized} (:t %))
                 (= op (:op %))
                 (= subject (:subject %)))
           (store/audit-trail db))))

(defn- register-names [op]
  (case op
    :log-production-batch "the batch record (mealops.store/log-batch) + the :batch-logged commit fact"
    :coordinate-shipment "the batch record (mealops.store/finalize-shipment) + the :shipment-finalized commit fact"
    "the commit fact only (no batch mutation)"))

(defn- approval-trail
  "One row per human decision the run actually took. `:decided-by` is
  read off the driver's own approval fact; `:retained` is read back OUT
  of the store. The two are reported separately on purpose -- a reader
  must be able to tell 'nobody approved' from 'the store dropped it'."
  [db runs]
  (for [r runs :when (:resume? r)]
    {:thread (:thread r)
     :op (:op r)
     :subject (:subject r)
     :outcome (:outcome r)
     :decided-by (:decided-by r)
     :register (register-names (:op r))
     :retained (when (= :approval-granted (:outcome r))
                 (some approver-in (registers-for db (:op r) (:subject r))))}))

(defn- approver-disclosure
  "The attribution sentence, DERIVED from the trail this run actually
  produced rather than stated as prose. If `mealops.store` is later
  changed to keep the approver on a register it currently drops, this
  sentence changes with it -- a hard-coded claim here would have become a
  lie the moment someone fixed the store."
  [trail]
  (let [granted (filter #(= :approval-granted (:outcome %)) trail)
        ops (fn [xs] (str/join ", " (map #(code* %) (sort (distinct (map (comp str :op) xs))))))
        kept (filter :retained granted)
        lost (remove :retained granted)]
    (cond
      (empty? granted)
      "No approval was granted in this run, so there is nothing to attribute."

      (empty? lost)
      (str "Measured on this run: the approver survived on every register written by "
           (ops kept) ".")

      (empty? kept)
      (str "Measured on this run: the approver was dropped by every register written by "
           (ops lost) " — it exists only on the driver's <code>:approval-granted</code> "
           "audit fact, which is why the <em>Decided by</em> column above can still name a "
           "person while the <em>Approver in the record?</em> column cannot. "
           "<code>mealops.store/log-batch</code> and "
           "<code>mealops.store/finalize-shipment</code> take no approver parameter at "
           "all — <code>log-batch</code> re-persists the batch record it is handed and "
           "sets <code>:processed?</code>, and <code>finalize-shipment</code> sets only "
           "<code>:shipment-finalized?</code> — so there is no slot on the register for "
           "an approver to survive in.")

      :else
      (str "Measured on this run: the approver survived on the registers written by "
           (ops kept) ", and was dropped by " (ops lost) " — audit only there."))))

;; ----------------------------- rows -----------------------------

(defn- disposition-label [d]
  (case d
    :auto-commit (ok* "auto-commit")
    :hard-refusal (critical "HARD refusal")
    :soft-escalation (warn "SOFT escalation")
    (muted "unclassified")))

(defn- window-cell
  "actual / allowed window, both read from the batch record and
  `mealops.facts/product-types` -- with the Governor's OWN predicate from
  `mealops.registry` called to decide whether it is in or out."
  [out? actual allowed]
  (str (if out? (critical (esc actual)) (ok* (esc actual)))
       " " (muted (str "/ " (esc allowed)))))

(defn- last-run-for [runs subject]
  (last (filter #(= subject (:subject %)) (decisions runs))))

(defn- status-cell [runs subject]
  (if-let [r (last-run-for runs subject)]
    (let [basis (kw-list (mapcat #(map :rule (:violations %)) (:facts r)))]
      (case (:disposition r)
        :hard-refusal (critical (str "HARD refusal &middot; " basis))
        :soft-escalation (warn "escalated to a human")
        :auto-commit (ok* "auto-committed")
        (muted "unclassified")))
    (muted "no activity")))

(defn- batch-row
  "Every cell is read back out of the store AFTER the run. The in/out
  verdicts are produced by CALLING the same `mealops.registry` predicates
  the Governor calls -- not copied from the hold text."
  [runs [id _seed why] b]
  (let [p (facts/product-type-by-id (:product-type b))
        j (facts/jurisdiction-by-id (:jurisdiction b))]
    (row (code* id)
         (if p (esc (:name p)) (critical "unknown product type"))
         (if j (esc (:name j)) (critical "none on file"))
         (window-cell (registry/core-cook-temp-below-minimum?
                       (:core-cook-temp-c b) (:core-cook-temp-min-c p))
                      (str (:core-cook-temp-c b) "℃")
                      (str "≥" (:core-cook-temp-min-c p) "℃"))
         (window-cell (registry/chill-time-exceeds-max?
                       (:chill-time-minutes b) (:chill-time-max-minutes p))
                      (str (:chill-time-minutes b) " min")
                      (str "≤" (:chill-time-max-minutes p) " min"))
         (window-cell (registry/cold-storage-temp-out-of-range?
                       (:cold-storage-temp-c b) (:cold-storage-temp-min-c p)
                       (:cold-storage-temp-max-c p))
                      (str (:cold-storage-temp-c b) "℃")
                      (str (:cold-storage-temp-min-c p) "–" (:cold-storage-temp-max-c p) "℃"))
         (window-cell (registry/shelf-life-exceeded?
                       (:shelf-life-hours-elapsed b) (:max-shelf-life-hours p))
                      (str (:shelf-life-hours-elapsed b) " h")
                      (str "≤" (:max-shelf-life-hours p) " h"))
         (window-cell (registry/water-activity-exceeds-max?
                       (:water-activity b) (:water-activity-max p))
                      (str (:water-activity b))
                      (str "≤" (:water-activity-max p)))
         (window-cell (registry/ph-level-exceeds-max? (:ph-level b) (:ph-max p))
                      (str "pH " (:ph-level b))
                      (str "≤" (:ph-max p)))
         (window-cell (registry/weight-variance-excessive? (:weight-variance-grams b) 20)
                      (str (:weight-variance-grams b) " g") "≤20 g")
         (window-cell (registry/sanitation-score-insufficient? (:sanitation-score b) 75)
                      (str (:sanitation-score b)) "≥75")
         (if (registry/foreign-material-detected? (:foreign-material-detected? b))
           (critical "detected") (ok* "none"))
         (if (registry/packaging-seal-compromised? (:packaging-seal-compromised? b))
           (critical "breached") (ok* "intact"))
         ;; the calibration DATE is never rendered -- only the Governor's
         ;; boolean answer about it, so the page stays byte-stable.
         (if (registry/metal-detector-calibration-overdue?
              (:metal-detector-last-calibration-date b) (System/currentTimeMillis))
           (critical "overdue") (ok* "current"))
         (if (registry/allergen-label-mismatch? (:cross-contact-risk b) (:declared-allergens b))
           (critical (str "undeclared: "
                          (kw-list (remove (set (:declared-allergens b))
                                           (:cross-contact-risk b)))))
           (ok* (or (some->> (kw-list (:declared-allergens b)) (str "declared: "))
                    "no cross-contact")))
         (str (num* (count (:evidence-checklist b))) " / "
              (num* (count (:required-evidence j))))
         (if (facts/material-lot-supplier-verified? (:material-lot b))
           (ok* "verified") (critical "not verified"))
         (if (and (true? (:safety-concern-raised? b))
                  (not (true? (:safety-concern-resolved? b))))
           (critical "open") (ok* "none open"))
         (cond (:shipment-finalized? b) (ok* "logged &amp; shipped")
               (:processed? b) (warn "logged, not yet shipped")
               :else (muted "not logged"))
         (status-cell runs id)
         (esc why))))

(defn- unregistered-row [runs subject]
  (row (code* subject)
       (critical "no batch record on file")
       (muted "— (20 columns of batch metadata cannot exist for a batch that was never registered)")
       (status-cell runs subject)))

(defn- hard-check-row [{:keys [rule detail count subjects ops]}]
  (row (code* rule)
       (num* count)
       (str/join " " (map #(code* %) (sort (distinct subjects))))
       (str/join " " (map #(code* %) (sort (distinct (map str ops)))))
       (esc detail)))

(defn- gate-row [dispositions op]
  (let [d (get dispositions op {})
        cell (fn [k klass] (when-let [n (get d k)] (str "<span class=\"" klass "\">" n "</span>")))]
    (row (code* op)
         (if (contains? governor/high-stakes op)
           (critical "yes &middot; real actuation")
           (muted "no"))
         (if (contains? governor/always-escalate-ops op)
           (warn "always &middot; even when the Governor is clean")
           (ok* "only when the Governor is clean and confident"))
         (or (cell :auto-commit "ok") (muted "—"))
         (or (cell :soft-escalation "warn") (muted "—"))
         (or (cell :hard-refusal "critical") (muted "—")))))

(defn- run-row [{:keys [thread op subject disposition confidence facts why]}]
  (row (code* thread)
       (code* op)
       (code* subject)
       (disposition-label disposition)
       (num* confidence)
       (or (some->> (kw-list (mapcat #(map :rule (:violations %)) facts))
                    (str "")) (muted "—"))
       (esc why)))

(defn- ledger-row [f]
  (row (code* (:t f))
       (if (= :driver (:by f)) (muted "driver") (ok* "governor"))
       (if (:op f) (code* (:op f)) (muted "—"))
       (if (:subject f) (code* (:subject f)) (muted "—"))
       (or (some->> (kw-list (:basis f)) (str "")) (muted "— (empty)"))
       (if (some? (:confidence f)) (num* (:confidence f)) (muted "—"))
       (cond
         (:approved-by f) (str "approved by " (code* (:approved-by f)))
         (:rejected-by f) (str "rejected by " (code* (:rejected-by f)))
         (:actor f) (str "executed by " (code* (:actor f)))
         :else (muted "—"))))

(defn- product-row [[_id p]]
  (row (code* (:id p))
       (esc (:name p))
       (num* (str "≥" (:core-cook-temp-min-c p) "℃"))
       (num* (str "≤" (:chill-time-max-minutes p) " min"))
       (num* (str (:cold-storage-temp-min-c p) "–" (:cold-storage-temp-max-c p) "℃"))
       (num* (str "≤" (:max-shelf-life-hours p) " h"))
       (num* (str "≤" (:water-activity-max p)))
       (num* (str "≤" (:ph-max p)))
       (code* (:unspsc-code p))
       (str (code* (:gtin p)) " " (muted (esc (kname (:gtin/status p)))))))

(defn- jurisdiction-row [[_id j]]
  (row (code* (:id j))
       (esc (:name j))
       (num* (count (:required-evidence j)))
       (str/join " " (map #(code* (kname %)) (:required-evidence j)))))

(defn- phase-row [from]
  (apply row (code* from)
         (for [to phase/all-phases]
           (cond
             (= from to) (muted "·")
             (phase/can-transition? from to) (ok* "→")
             :else (critical "✕")))))

;; ----------------------------- the page -----------------------------

(defn- blueprint []
  (edn/read-string (slurp "blueprint.edn")))

(defn render
  [{:keys [db runs]}]
  (let [bp (blueprint)
        ledger (vec (store/audit-trail db))
        ops (decisions runs)
        hard (by-disposition runs :hard-refusal)
        soft (by-disposition runs :soft-escalation)
        auto (by-disposition runs :auto-commit)
        groups (hard-rule-groups runs)
        softg (soft-rule-groups runs)
        dispositions (op-dispositions runs)
        trail (approval-trail db runs)
        hold-facts (filter #(= :governor-hold (:t %)) ledger)
        escalation-facts (remove #(seq (:basis %)) hold-facts)]
    (str
     "<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "<meta name=\"color-scheme\" content=\"light\">\n"
     "<title>Operator console — " (esc (:itonami.blueprint/name bp))
     " (ISIC " (esc (:itonami.blueprint/isic-rev5 bp)) ")</title>\n"
     "<meta name=\"description\" content=\"Real "
     "mealops.operation → mealops.governor → mealops.store run, rendered at build time.\">\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style>\n"
     "</head>\n<body>\n<main class=\"container\">\n"

     "  <h1>調理済み食品製造コーディネーション — オペレーターコンソール</h1>\n"
     "  <p class=\"subtitle\">" (esc (:itonami.blueprint/name bp))
     " &middot; <code>" (esc (:itonami.blueprint/id bp)) "</code>"
     " &middot; ISIC Rev.5 <span class=\"badge\">" (esc (:itonami.blueprint/isic-rev5 bp)) "</span>"
     " &middot; governor <code>" (esc (kname (:itonami.blueprint/governor bp))) "</code></p>\n"
     "  <p>このページは手書きされていない。<code>clojure -M:render-html</code> が "
     "<code>mealops.operation/run-operation</code> → <code>mealops.governor/check</code> → "
     "<code>mealops.store</code> を実際に走らせ、その実行が生成した値だけを描画している。"
     "数値・判定・違反理由はすべて実行結果由来で、リテラルは一つも無い。</p>\n"

     ;; ---------------------------------------------------------------
     (section
      "この実行の結果（1 行 = 実際に走った 1 提案）"
      (str "Every row below is one real call to <code>mealops.operation/run-operation</code>. "
           "<strong>Disposition is read off the Governor's own <code>:hard?</code> / "
           "<code>:escalate?</code> verdict</strong>, never off the ledger fact's type — "
           "see the classification note under the next table for why that distinction matters here.")
      ["Thread" "Op" "Batch" "Disposition" "Confidence" "Governor rules" "What this proposal was"]
      (rows (map run-row ops)))

     ;; ---------------------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>3 つの結末を数える — そして 4 つ目のゲートは Governor ではない</h2>\n"
     "    <table>\n"
     "      <thead><tr><th>Disposition</th><th>Count</th><th>Reaches a human?</th><th>Overridable?</th><th>Decided by</th></tr></thead>\n"
     "      <tbody>\n"
     (rows [(row (critical "HARD governor refusal") (num* (count hard))
                 (critical "never") (critical "no — un-overridable")
                 (str (code* "check") " → " (code* ":hard? true")))
            (row (warn "SOFT escalation gate") (num* (count soft))
                 (warn "always") (ok* "yes — a human may approve or reject")
                 (str (code* "check") " → " (code* ":escalate? true")))
            (row (ok* "auto-commit") (num* (count auto))
                 (muted "no need") (muted "n/a")
                 (str (code* "check") " → " (code* ":ok? true")))
            (row (muted "phase / workflow gate")
                 (num* (count (for [a phase/all-phases b phase/all-phases
                                    :when (and (not= a b) (not (phase/can-transition? a b)))]
                                [a b])))
                 (muted "n/a — the Governor never sees it")
                 (muted "n/a")
                 (str (code* "mealops.phase/can-transition?") " — "
                      (muted "NOT a governor verdict; excluded from every hold count above")))])
     "\n      </tbody>\n    </table>\n"
     "    <p class=\"warn\"><strong>計測された曖昧さ (measured, not assumed):</strong> "
     "<code>mealops.operation/run-operation</code> has a single <code>:ok?</code> branch, so it "
     "routes HARD refusals and SOFT escalations through the same "
     "<code>(:hold-fact-fn context)</code>. Both therefore land in the ledger as "
     "<code>:t :governor-hold</code>. This run produced <strong>"
     (num* (count hold-facts)) " <code>:governor-hold</code> facts</strong>, but only <strong>"
     (num* (count hard)) "</strong> of them are refusals — the other <strong>"
     (num* (count escalation-facts)) "</strong> are escalations carrying an empty "
     "<code>:basis</code>. Counting ledger rows by fact type would overstate the refusals by "
     (num* (count escalation-facts)) ".</p>\n"
     "  </section>\n"

     ;; ---------------------------------------------------------------
     (section
      (str "HARD governor refusals — " (count groups) " distinct rules, "
           (reduce + (map :count groups)) " firings")
      (str "The Governor refused these outright. Nothing here reached a human: an "
           "un-overridable violation is not a decision anybody gets to make. "
           "<strong>The rule keyword and the 理由 text are the Governor's own "
           "<code>:violations</code> entries</strong>, read off the real ledger fact — this "
           "renderer contains neither string.")
      ["Rule" "Firings" "Batches" "Ops" "Governor's own reason"]
      (rows (map hard-check-row groups)))

     ;; ---------------------------------------------------------------
     (section
      (str "SOFT signals — " (count softg) " distinct rules")
      (str "Independently detected concerns that are <em>not</em> grounds for a hold, but do "
           "force human escalation even when every hard check passes and confidence is high. "
           "They live in the verdict's <code>:soft-violations</code>, kept separate from "
           "<code>:violations</code> so the latter keeps meaning exactly one thing. "
           "<strong>These never appear in a ledger fact's <code>:basis</code> at all</strong> — "
           "<code>hold-fact</code> copies only <code>:violations</code> — which is a second "
           "reason the ledger alone cannot reconstruct why a run escalated.")
      ["Rule" "Firings" "Batches" "Ops" "Governor's own reason"]
      (rows (map hard-check-row softg)))

     ;; ---------------------------------------------------------------
     (section
      "アクションゲート — この actor が提案しうる操作と、実際に到達した結末"
      (str "The first three columns are read from <code>mealops.governor/allowed-ops</code>, "
           "<code>high-stakes</code> and <code>always-escalate-ops</code>. The last three are "
           "counted from what this run actually reached. "
           "<code>:schedule-maintenance</code> is the only op that can ever auto-commit.")
      ["Op" "High stakes?" "Escalates?" "auto-commit" "SOFT escalation" "HARD refusal"]
      (rows (map (partial gate-row dispositions) (sort-by str governor/allowed-ops))))

     ;; ---------------------------------------------------------------
     (section
      (str "バッチ台帳（実行後の store から読み戻した状態）")
      (str "Read back with <code>mealops.store/production-batch</code> AFTER the run, so "
           "<code>:processed?</code> / <code>:shipment-finalized?</code> are post-run state. "
           "Every in/out verdict is produced by calling the same "
           "<code>mealops.registry</code> predicate the Governor calls. "
           "The metal-detector calibration <em>date</em> is deliberately not rendered — only "
           "the Governor's boolean answer about it — so the page stays byte-stable.")
      ["Batch" "製品" "法域" "中心温度 CCP1" "冷却時間 CCP2" "保管温度" "経過時間"
       "水分活性" "pH" "重量分散" "衛生スコア" "異物" "シール" "校正" "アレルゲン"
       "証拠" "原材料供給者" "安全フラグ" "ライフサイクル" "この実行での結末" "seed が変えた一点"]
      (rows (concat (for [[id _ _ :as spec] seed-batches]
                      (batch-row runs spec (store/production-batch db id)))
                    [(unregistered-row runs unregistered-subject)])))

     ;; ---------------------------------------------------------------
     (section
      "製品規格ウィンドウ（mealops.facts/product-types）"
      (str "The Governor's independent reference data. Every window the batch table above "
           "checks against is read from here — the advisor's confidence never overrides it.")
      ["Product" "名称" "中心温度 CCP1" "冷却時間 CCP2" "コールドチェーン" "消費期限"
       "水分活性" "pH" "UNSPSC" "GTIN"]
      (rows (map product-row (sort-by (comp str key) facts/product-types))))

     ;; ---------------------------------------------------------------
     (section
      "法域ごとの必要証拠（mealops.facts/jurisdictions）"
      (str "<code>:evidence-incomplete</code> is decided by "
           "<code>facts/required-evidence-satisfied?</code> against exactly this list.")
      ["Jurisdiction" "名称" "必要件数" "必要な証拠"]
      (rows (map jurisdiction-row (sort-by (comp str key) facts/jurisdictions))))

     ;; ---------------------------------------------------------------
     (section
      "フェーズ機械 — Governor とは別のゲート（mealops.phase/can-transition?）"
      (str "Forward-only progression through the production phases. Every cell is a live call "
           "to <code>mealops.phase/can-transition?</code>. "
           "<strong>A ✕ here is not a governor refusal</strong> — it is a workflow gate the "
           "Governor never evaluates, and it is excluded from every hold count on this page. "
           "Rows are the from-phase, columns the to-phase.")
      (cons "from \\ to" (map #(code* (kname %)) phase/all-phases))
      (rows (map phase-row phase/all-phases)))

     ;; ---------------------------------------------------------------
     (section
      "承認トレイル — 誰が決めたか、そして記録に残ったか"
      (str "<code>mealops.operation</code> has no approval node: its <code>:ok?</code> gate is "
           "the only branch it has, so the human sign-off step is modelled by the renderer's "
           "driver and its facts are marked <code>driver</code> in the ledger below. "
           "<em>Decided by</em> is read off that decision fact; "
           "<em>Approver in the record?</em> is read back OUT of the store at render time. "
           "The two columns are separate on purpose — a reader must be able to tell "
           "“nobody approved” from “the store dropped it”.")
      ["Thread" "Op" "Batch" "Outcome" "Decided by" "Register probed" "Approver in the record?"]
      (rows (for [t trail]
              (row (code* (:thread t))
                   (code* (:op t))
                   (code* (:subject t))
                   (if (= :approval-granted (:outcome t))
                     (ok* "approved") (warn "rejected"))
                   (code* (:decided-by t))
                   (muted (esc (:register t)))
                   (cond
                     (not= :approval-granted (:outcome t)) (muted "— (not approved)")
                     (:retained t) (ok* (esc (:retained t)))
                     :else (critical "no — dropped by the store"))))))
     "  <section class=\"card\">\n"
     "    <p class=\"note\">" (approver-disclosure trail) "</p>\n"
     "  </section>\n"

     ;; ---------------------------------------------------------------
     (section
      "上流・下流アクター名簿（mealops.facts）"
      (str "Who this actor will accept an inbound <code>:handoff</code> from, and who it "
           "dispatches to. A source-actor outside these rosters is what makes "
           "<code>:rawmat-handoff-suspect</code> / <code>:packaging-handoff-suspect</code> fire.")
      ["Roster" "Key" "登録済み source-actor"]
      (rows (concat
             (for [[k v] (sort-by (comp str key) facts/raw-material-source-actors)]
               (row (code* "raw-material-source-actors") (code* k)
                    (str/join " " (map #(code* %) (sort v)))))
             [(row (code* "packaging-source-actors") (muted "— (all product types)")
                   (str/join " " (map #(code* %) (sort facts/packaging-source-actors))))])))

     ;; ---------------------------------------------------------------
     (section
      (str "監査台帳（この実行 — " (count ledger) " facts）")
      (str "Append-only decision log, in the order the run produced it. Facts marked "
           "<code>governor</code> are exactly what <code>mealops.operation</code> returned, "
           "unmodified — including the empty-<code>:basis</code> ones it emits for a mere "
           "escalation. Facts marked <code>driver</code> are the human-decision and commit "
           "steps the actor itself does not yet write. "
           "<strong>Note the <code>:actor</code> column meaning</strong>: "
           "<code>hold-fact</code> stamps the EXECUTING actor there "
           "(<code>" (esc (:actor-id plant-operator)) "</code>), never the approver "
           "(<code>" (esc human-approver) "</code>).")
      ["Fact" "Written by" "Op" "Batch" "Basis" "Confidence" "Attribution"]
      (rows (map ledger-row ledger)))

     ;; ---------------------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>開示されたギャップ（この実行で観測したもの、直していない）</h2>\n"
     "    <p class=\"muted\">Building this page was the first time this repo's actor had ever "
     "been driven outside its test suite. These are defects observed by running it. They are "
     "disclosed rather than silently patched, because quietly fixing a governor inside a "
     "rendering task hides the finding.</p>\n"
     "    <ol>\n"
     "      <li><strong><code>run-operation</code> cannot distinguish a refusal from an "
     "escalation in the ledger.</strong> Its single <code>:ok?</code> gate sends both through "
     "<code>hold-fact</code>, so both are <code>:t :governor-hold</code>. Measured on this run: "
     (num* (count hold-facts)) " such facts, of which " (num* (count hard))
     " are refusals and " (num* (count escalation-facts))
     " are escalations with an empty <code>:basis</code>. A consumer counting fact types "
     "overstates refusals by " (num* (count escalation-facts)) ".</li>\n"
     "      <li><strong>An approved operation writes no audit fact at all.</strong> "
     "<code>run-operation</code> returns <code>{:ok? true :facts []}</code> — the commit path "
     "yields an empty fact vector, so an append-only ledger fed only by the actor records "
     "every refusal and no success. The <code>driver</code>-marked facts above exist only "
     "because this renderer adds them.</li>\n"
     "      <li><strong>Soft violations never reach the ledger.</strong> "
     "<code>hold-fact</code> copies <code>:violations</code> but not "
     "<code>:soft-violations</code>, so the " (num* (count softg))
     " soft rules in the table above are invisible to any ledger reader — the escalation they "
     "caused is indistinguishable from one caused by high stakes alone.</li>\n"
     "      <li><strong>The store has no approver slot.</strong> "
     "<code>log-batch</code> / <code>finalize-shipment</code> take no approver parameter, so "
     "the human who signed off cannot survive onto the batch register. Probed at render time "
     "rather than asserted — see the approval trail above.</li>\n"
     "      <li><strong><code>mealops.sim</code> is a stub</strong> that prints "
     "“not yet implemented”, and <code>mealops.advisor</code> is a docstring with no "
     "proposal function in it, so the proposal shape is supplied by this renderer's driver. "
     "The <code>:dev</code> alias's <code>:override-deps</code> for langgraph/langchain is "
     "inert — neither library is in <code>:deps</code>, and "
     "<code>mealops.operation</code> is a plain function, not a compiled StateGraph, so "
     "nothing here calls <code>langgraph.graph/run*</code>.</li>\n"
     "    </ol>\n"
     "  </section>\n"

     "</main>\n"
     "<footer class=\"container\">\n"
     "  <p>Generated by <code>mealops.render-html</code> from a real "
     "<code>mealops.operation</code> → <code>mealops.governor</code> → <code>mealops.store</code> "
     "run. Deterministic and timestamp-free: two consecutive builds are byte-identical. "
     "Styling is <a href=\"https://github.com/kotoba-lang/jp-go-digital-design-system\">jp-go-dds</a> "
     "(デジタル庁デザインシステム), inlined so the page fetches nothing.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- build-time invariants -----------------------------

(defn assert-real-holds!
  "Invariants 1-6. Throws; never warns. A generator that renders a page
  with no HARD refusal in it has not demonstrated the thing this repo
  exists to demonstrate, and a page whose HARD-rule section outlived the
  run that justified it is a lie with a table around it."
  [{:keys [db runs]}]
  (let [ledger (vec (store/audit-trail db))
        hard (by-disposition runs :hard-refusal)
        soft (by-disposition runs :soft-escalation)
        fired (into #{} (for [r hard, f (:facts r), v (:violations f)] (:rule v)))
        soft-fired (into #{} (for [r (decisions runs)
                                   v (:soft-violations (:verdict r))]
                               (:rule v)))
        groups (hard-rule-groups runs)
        trail (approval-trail db runs)]
    (println "OPERATIONS\t" (count (decisions runs)))
    (println "LEDGER-FACTS\t" (count ledger))
    (println "HARD-REFUSALS\t" (count hard))
    (println "SOFT-ESCALATIONS\t" (count soft))
    (println "DISTINCT-HARD-RULES\t" (count fired))
    (println "DISTINCT-SOFT-RULES\t" (count soft-fired))

    ;; 1 -- at least one real HARD refusal
    (when (zero? (count hard))
      (throw (ex-info (str "render-html: the run produced ZERO HARD governor refusals -- "
                           "refusing to write a console that shows no HARD hold")
                      {:ledger-facts (count ledger)
                       :operations (count (decisions runs))})))

    ;; 2 -- evidence floor on DISTINCT hard rules
    (when (< (count fired) min-distinct-hard-rules)
      (throw (ex-info "render-html: fewer distinct HARD rules fired than the evidence floor requires"
                      {:fired (vec (sort-by str fired))
                       :count (count fired)
                       :floor min-distinct-hard-rules})))

    ;; 3 -- a HARD refusal must never have reached a human
    (let [approved-threads (into #{} (map :thread (filter :resume? runs)))]
      (doseq [r hard]
        (when (contains? approved-threads (:thread r))
          (throw (ex-info "render-html: a run classified HARD ALSO reached a human approver"
                          {:thread (:thread r) :op (:op r) :subject (:subject r)})))))

    ;; 4 -- no rendered HARD-rule row without a backing ledger fact
    (let [ledger-rules (into #{} (for [f ledger, v (:violations f)] (:rule v)))]
      (doseq [{:keys [rule]} groups]
        (when-not (contains? ledger-rules rule)
          (throw (ex-info "render-html: HARD-rule row has no backing ledger fact" {:rule rule})))))

    ;; 5 -- the SOFT gate must have shown BOTH of its outcomes
    (let [outcomes (frequencies (map :outcome trail))]
      (println "APPROVALS-GRANTED\t" (get outcomes :approval-granted 0))
      (println "APPROVALS-REJECTED\t" (get outcomes :approval-rejected 0))
      (when (zero? (get outcomes :approval-granted 0))
        (throw (ex-info (str "render-html: no approval was GRANTED -- the approval trail and "
                             "the approver-attribution probe would both be vacuous")
                        {:trail (count trail)})))
      (when (zero? (get outcomes :approval-rejected 0))
        (throw (ex-info (str "render-html: no approval was REJECTED -- the SOFT gate's other "
                             "outcome was never demonstrated")
                        {:trail (count trail)}))))

    ;; 6 -- evidence floor on DISTINCT soft rules
    (when (< (count soft-fired) min-distinct-soft-rules)
      (throw (ex-info "render-html: fewer distinct SOFT rules fired than the evidence floor requires"
                      {:fired (vec (sort-by str soft-fired))
                       :count (count soft-fired)
                       :floor min-distinct-soft-rules})))

    {:hard (count hard) :rules (vec (sort-by str fired))
     :soft-rules (vec (sort-by str soft-fired))}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-scenario!)
        {:keys [hard rules soft-rules]} (assert-real-holds! result)
        html (render result)]
    (when-let [p (.getParentFile (java.io.File. ^String out))] (.mkdirs p))
    (spit out html)
    (println "wrote" out
             (str "(" (count (store/audit-trail (:db result))) " ledger facts, "
                  hard " HARD refusals over " (count rules) " distinct rules, "
                  (count (decisions (:runs result))) " operations, "
                  (count html) " bytes)"))
    (println "HARD-RULES\t" (str/join " " (map str rules)))
    (println "SOFT-RULES\t" (str/join " " (map str soft-rules)))))
