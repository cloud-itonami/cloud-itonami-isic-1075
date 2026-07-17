# cloud-itonami-isic-1075: Prepared Meals and Dishes Manufacturing Coordination Actor

**ISIC Rev. 5 1075** — Manufacture of Prepared Meals and Dishes

A distributed actor for autonomous, compliant coordination of prepared-meal/ready-dish plant operations: raw-material intake → prep/portioning → cook (pasteurization/lethality step) → rapid chilling (cook-chill) or blast-freezing (cook-freeze) → packaging (vacuum/modified-atmosphere sealing) → metal-detector/X-ray and seal-integrity inspection → HACCP critical-control-point compliance → finished-product logistics. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not equipment control.** Cook-line, chill/freeze-line, and packaging-line operation and food-safety certification authority remain exclusive to licensed prepared-meal-plant staff and regulators.

## Scope

This actor coordinates **plant-operations workflow** for prepared-meals/ready-dish manufacturing (cook-chill ready meals, cook-freeze ready meals, retort/shelf-stable variants):

- Production batch logging (meal-prep/cook/chill-freeze batch, output-quality data)
- Equipment maintenance scheduling (cook lines, blast chillers/freezers, packaging/sealing lines, metal detectors/X-ray inspection)
- Food-safety concern escalation (HACCP critical-limit deviation, allergen cross-contact, cold-chain break)
- Finished-product shipment coordination

**Out of scope:**
- Direct cook-line/chill-freeze-line/packaging-line equipment control (plant staff exclusive)
- Food-safety certification authority (human inspector/regulator only)
- Regulatory interpretation (proposals cite jurisdiction specifications; the Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the advisor's confidence for anything safety- or compliance-relevant, and it always wins over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`) — includes any proposal that would touch cook-line/chill-freeze-line/packaging-line control or food-safety certification
  - Proposal asserting an `:effect` other than `:propose` (`:effect-not-propose`)
  - Plant/batch record not independently verified/registered before any proposal is made against it (`:batch-not-registered`) — applies to every proposal op, not only shipment coordination
  - No jurisdiction citation (`:no-spec-basis`) — can't verify requirements without one
  - Evidence checklist incomplete (`:evidence-incomplete`)
  - Core (thermal-centre) cook temperature below the product's minimum lethality temperature — CCP1 (`:core-cook-temp-below-minimum`)
  - Chill/freeze-down time exceeds the product's maximum window — CCP2 (`:chill-time-exceeds-max`)
  - Cold-storage/transport temperature out of the product's safe cold-chain window (`:cold-storage-temp-out-of-range`)
  - Shelf life exceeded — elapsed time since production beyond the product's maximum shelf-life hours (`:shelf-life-exceeded`)
  - Water activity exceeds the product's maximum allowable level (`:water-activity-exceeds-max`)
  - pH exceeds the product's maximum allowable level — under-acidified reduced-oxygen-packaged product (`:ph-level-exceeds-max`)
  - Foreign material detected on the batch's own inspection — metal/glass/dense-plastic fragments (`:foreign-material-detected`)
  - Metal-detector/X-ray calibration overdue (`:metal-detector-calibration-overdue`)
  - Finished-product weight variance excessive (`:weight-variance-excessive`)
  - Allergen cross-contact mismatch — a cross-contact risk (milk/egg/tree-nuts/peanuts/soy/gluten/crustaceans/fish/etc.) not fully covered by the declared-allergens label (`:allergen-label-mismatch`)
  - Plant sanitation/cross-contamination-control score insufficient (`:sanitation-score-insufficient`)
  - Packaging seal (vacuum/MAP) compromised (`:packaging-seal-compromised`)
  - Unresolved food-safety flag (`:food-safety-flag-unresolved`)
  - Batch already processed / shipment already finalized (double-commit guards)
  - `:coordinate-shipment` with no `:handoff` record (`:handoff-missing`) — see "Cross-Actor Handoff" below
  - Handoff's declared cold-chain-temp-min-c/max-c window exceeds the product's own proven-safe cold-storage safety margin (`:handoff-cold-chain-window-exceeds-product-safety-margin`)
  - Handoff's `:handoff/batch-id` does not point to a batch this actor has actually completed `:log-production-batch` for (`:handoff-batch-not-registered`)
- **Escalate** (human sign-off always required):
  - `:log-production-batch` / `:coordinate-shipment` — real actuation events, always require plant-operator sign-off even when the Governor is otherwise clean
  - `:flag-food-safety-concern` — a food-safety concern (HACCP critical-limit deviation, allergen cross-contact, cold-chain break) is never auto-resolved by advisor confidence alone
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
  - Raw-material lot present on the evidence checklist but its supplier is not explicitly declared verified (`:supplier-not-verified`) — a traceability concern, not a hard hold; see "Raw-Material Traceability" below

### Cross-Actor Handoff (isic-1075 → jsic-4721)

`:coordinate-shipment` proposals that hand a finished batch off to a downstream cold-chain 3PL actor (e.g. `cloud-itonami-jsic-4721`) carry a `:handoff` record under the proposal's `:value` — a small wire shape (documented in superproject ADR-2607177600) both actors independently validate as pure predicates, with no shared code and no shared store:

```clojure
{:handoff/id "..."
 :handoff/source-actor "cloud-itonami-isic-1075"
 :handoff/batch-id "..."
 :handoff/product-type-id :meal/cook-chill-poultry
 :handoff/cold-chain-temp-min-c 0.0
 :handoff/cold-chain-temp-max-c 3.0
 :handoff/quantity-kg 120.5
 :handoff/dispatched-at-iso "..."}
```

This actor's half of the contract is `mealops.facts/handoff-window-within-product-safety-margin?` — a subset check against this actor's own `product-types` registry (see `mealops.governor`'s `handoff-*-violations` functions).

### Raw-Material Traceability

`:raw-material-intake-record` (the evidence-checklist item) is now backed by an explicit `:material-lot` record on the batch (`mealops.facts/material-lot-keys`: `:material/lot-id` / `:material/supplier-name` / `:material/supplier-verified?` / `:material/received-at-iso`). A batch whose checklist declares the intake record present but whose `material-lot`'s supplier is not explicitly verified (`mealops.facts/material-lot-supplier-verified?`) is not hard-held — it always escalates to a human instead, same treatment as a food-safety concern.
- **Commit** (advisor proposal approved; Governor clean; not a mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current allowlist that is effectively `:schedule-maintenance` when clean

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four operation types, all `:effect :propose`:

- **`:log-production-batch`** — Log meal-prep/cook/chill-freeze batch, output-quality data into production records (always requires human sign-off)
- **`:schedule-maintenance`** — Propose cooking/packaging-equipment maintenance for cook lines, blast chillers/freezers, packaging/sealing lines, metal detectors (routine, low risk)
- **`:flag-food-safety-concern`** — Surface a food-safety concern (e.g. HACCP critical-limit deviation, allergen cross-contact, cold-chain break); always escalates
- **`:coordinate-shipment`** — Coordinate outbound prepared-meal shipment (always requires human sign-off)

Any proposal for an operation outside this allowlist — most importantly anything that would amount to direct cook-line/chill-freeze-line/packaging-line control, or food-safety certification — is refused unconditionally by the Governor (`:op-not-allowed`), regardless of advisor confidence.

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
