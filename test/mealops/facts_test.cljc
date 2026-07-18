(ns mealops.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [mealops.facts :as facts]))

;; ──────────────────────── Product Type Lookups ──────────────────────

(deftest product-type-by-id-test
  (testing "cook-chill poultry product type exists"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (some? p))
      (is (= (:id p) :meal/cook-chill-poultry))
      (is (= (:core-cook-temp-min-c p) 70.0))
      (is (= (:chill-time-max-minutes p) 90.0))))

  (testing "cook-chill beef product type exists"
    (let [p (facts/product-type-by-id :meal/cook-chill-beef)]
      (is (some? p))
      (is (= (:max-shelf-life-hours p) 120.0))
      (is (= (:ph-max p) 5.0))))

  (testing "cook-freeze fish product type exists"
    (let [p (facts/product-type-by-id :meal/cook-freeze-fish)]
      (is (some? p))
      (is (= (:chill-time-max-minutes p) 240.0))
      (is (= (:cold-storage-temp-max-c p) -18.0))
      (is (= (:max-shelf-life-hours p) 4380.0))))

  (testing "cook-chill vegetarian product type exists"
    (let [p (facts/product-type-by-id :meal/cook-chill-vegetarian)]
      (is (some? p))
      (is (= (:max-shelf-life-hours p) 96.0))
      (is (= (:ph-max p) 5.5))))

  (testing "nonexistent product type returns nil"
    (is (nil? (facts/product-type-by-id :meal/nonexistent)))))

;; ──────────────── UNSPSC / GTIN Classification Fields ──────────────

(deftest product-type-unspsc-and-gtin-test
  (testing "chilled (cook-chill) product types use UNSPSC 'Fresh Combination Meals'"
    (doseq [id [:meal/cook-chill-poultry :meal/cook-chill-beef :meal/cook-chill-vegetarian]]
      (let [p (facts/product-type-by-id id)]
        (is (= "50192701" (:unspsc-code p)) (str id " should cite the fresh-combination-meals commodity")))))

  (testing "frozen (cook-freeze) product type uses UNSPSC 'Frozen combination meals'"
    (let [p (facts/product-type-by-id :meal/cook-freeze-fish)]
      (is (= "50192702" (:unspsc-code p)))))

  (testing "every product type carries a distinct placeholder GTIN, explicitly flagged as never issued"
    (let [ids [:meal/cook-chill-poultry :meal/cook-chill-beef :meal/cook-freeze-fish :meal/cook-chill-vegetarian]
          gtins (map #(:gtin (facts/product-type-by-id %)) ids)]
      (is (every? string? gtins))
      (is (= (count gtins) (count (set gtins))) "no two product types share a placeholder GTIN")
      (doseq [id ids]
        (is (= :unissued-blueprint-placeholder (:gtin/status (facts/product-type-by-id id)))
            (str id " GTIN must be flagged as a never-issued placeholder"))))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "JP MHLW jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/mhlw)]
      (is (some? j))
      (is (some #{:cook-lethality-log} (:required-evidence j)))))

  (testing "US FDA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/fda)]
      (is (some? j))
      (is (some #{:allergen-declaration} (:required-evidence j)))))

  (testing "EU EFSA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :eu/efsa)]
      (is (some? j))
      (is (some #{:packaging-seal-check} (:required-evidence j)))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :us/fda)
          evidence [:raw-material-intake-record :cook-lethality-log :chill-time-log :cold-storage-temp-log
                    :water-activity-test :ph-test :allergen-declaration :weight-check :packaging-seal-check]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :us/fda)
          evidence [:raw-material-intake-record :cook-lethality-log]]
      (is (false? (facts/required-evidence-satisfied? j evidence)))))

  (testing "accepts a raw jurisdiction id in place of a resolved map"
    (let [evidence [:raw-material-intake-record :cook-lethality-log :chill-time-log :cold-storage-temp-log
                    :water-activity-test :ph-test :allergen-declaration :weight-check :packaging-seal-check]]
      (is (true? (facts/required-evidence-satisfied? :us/fda evidence))))))

;; ──────────────────────── Processing Safety Predicates ──────────────────────

(deftest core-cook-temp-meets-minimum-test
  (testing "core cook temp at or above minimum passes"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (true? (facts/core-cook-temp-meets-minimum? 70.0 p)))
      (is (true? (facts/core-cook-temp-meets-minimum? 85.0 p)))))

  (testing "core cook temp below minimum fails"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (false? (facts/core-cook-temp-meets-minimum? 60.0 p))))))

(deftest chill-time-within-max-test
  (testing "chill time at or below max passes"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (true? (facts/chill-time-within-max? 90.0 p)))
      (is (true? (facts/chill-time-within-max? 45.0 p)))))

  (testing "chill time above max fails"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (false? (facts/chill-time-within-max? 150.0 p))))))

(deftest cold-storage-temp-in-range-test
  (testing "cold-storage temp within window passes"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (true? (facts/cold-storage-temp-in-range? 2.0 p)))))

  (testing "cold-storage temp below window fails"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (false? (facts/cold-storage-temp-in-range? -5.0 p)))))

  (testing "cold-storage temp above window fails"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (false? (facts/cold-storage-temp-in-range? 8.0 p))))))

(deftest shelf-life-within-max-test
  (testing "elapsed hours at or below max passes"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (true? (facts/shelf-life-within-max? 120.0 p)))
      (is (true? (facts/shelf-life-within-max? 24.0 p)))))

  (testing "elapsed hours above max fails"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (false? (facts/shelf-life-within-max? 200.0 p))))))

(deftest water-activity-within-max-test
  (testing "water activity at or below max passes"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (true? (facts/water-activity-within-max? 0.97 p)))
      (is (true? (facts/water-activity-within-max? 0.85 p)))))

  (testing "water activity above max fails"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (false? (facts/water-activity-within-max? 0.99 p))))))

(deftest ph-level-within-max-test
  (testing "pH at or below max passes"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (true? (facts/ph-level-within-max? 5.0 p)))
      (is (true? (facts/ph-level-within-max? 4.2 p)))))

  (testing "pH above max fails"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (false? (facts/ph-level-within-max? 6.0 p))))))

;; ──────────────── Cross-Actor Handoff Window (isic-1075 <-> jsic-4721) ────────────────

(deftest handoff-window-within-product-safety-margin-test
  (testing "handoff window equal to the product's safety margin passes"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (true? (facts/handoff-window-within-product-safety-margin? 0.0 3.0 p)))))

  (testing "handoff window strictly inside the product's safety margin passes"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (true? (facts/handoff-window-within-product-safety-margin? 1.0 2.0 p)))))

  (testing "handoff window wider than the product's safety margin fails"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (false? (facts/handoff-window-within-product-safety-margin? -5.0 5.0 p)))))

  (testing "handoff window shifted outside the product's safety margin fails"
    (let [p (facts/product-type-by-id :meal/cook-freeze-fish)]
      (is (false? (facts/handoff-window-within-product-safety-margin? -18.0 -10.0 p)))))

  (testing "inverted handoff window (min > max) fails"
    (let [p (facts/product-type-by-id :meal/cook-chill-poultry)]
      (is (false? (facts/handoff-window-within-product-safety-margin? 3.0 0.0 p)))))

  (testing "nil product fails"
    (is (false? (facts/handoff-window-within-product-safety-margin? 0.0 3.0 nil)))))

;; ──────────────── Raw-Material Lot / Supplier Verification ────────────────

(deftest material-lot-supplier-verified-test
  (testing "explicit true passes"
    (is (true? (facts/material-lot-supplier-verified?
                {:material/lot-id "lot-1" :material/supplier-verified? true}))))

  (testing "explicit false fails"
    (is (false? (facts/material-lot-supplier-verified?
                 {:material/lot-id "lot-1" :material/supplier-verified? false}))))

  (testing "missing key fails (never silently trusted)"
    (is (false? (facts/material-lot-supplier-verified? {:material/lot-id "lot-1"}))))

  (testing "nil material-lot fails"
    (is (false? (facts/material-lot-supplier-verified? nil)))))

;; ──────────── Inbound Cross-Actor Handoff (this actor as RECEIVER) ────────────

(def ^:private well-formed-handoff
  {:handoff/id "h-1"
   :handoff/source-actor "cloud-itonami-isic-1010"
   :handoff/batch-id "meat-batch-1"
   :handoff/product-type-id "fresh-poultry"
   :handoff/quantity-kg 500.0
   :handoff/dispatched-at-iso "2026-07-17T00:00:00Z"})

(deftest handoff-record-well-formed-test
  (testing "complete handoff passes"
    (is (true? (facts/handoff-record-well-formed? well-formed-handoff))))

  (testing "missing :handoff/quantity-kg fails"
    (is (false? (facts/handoff-record-well-formed? (dissoc well-formed-handoff :handoff/quantity-kg)))))

  (testing "non-positive quantity fails"
    (is (false? (facts/handoff-record-well-formed? (assoc well-formed-handoff :handoff/quantity-kg 0))))
    (is (false? (facts/handoff-record-well-formed? (assoc well-formed-handoff :handoff/quantity-kg -5.0)))))

  (testing "blank source-actor fails"
    (is (false? (facts/handoff-record-well-formed? (assoc well-formed-handoff :handoff/source-actor "")))))

  (testing "missing :handoff/dispatched-at-iso fails"
    (is (false? (facts/handoff-record-well-formed? (dissoc well-formed-handoff :handoff/dispatched-at-iso)))))

  (testing "nil handoff fails"
    (is (false? (facts/handoff-record-well-formed? nil))))

  (testing "non-map handoff fails"
    (is (false? (facts/handoff-record-well-formed? "not-a-map")))))

(deftest material-handoff-source-actor-known-test
  (testing "isic-1010 is the known supplier for poultry and beef"
    (is (true? (facts/material-handoff-source-actor-known? :meal/cook-chill-poultry "cloud-itonami-isic-1010")))
    (is (true? (facts/material-handoff-source-actor-known? :meal/cook-chill-beef "cloud-itonami-isic-1010"))))

  (testing "isic-1020 is the known supplier for fish"
    (is (true? (facts/material-handoff-source-actor-known? :meal/cook-freeze-fish "cloud-itonami-isic-1020"))))

  (testing "isic-1030 is the known supplier for vegetarian"
    (is (true? (facts/material-handoff-source-actor-known? :meal/cook-chill-vegetarian "cloud-itonami-isic-1030"))))

  (testing "a mismatched source-actor for the category fails"
    (is (false? (facts/material-handoff-source-actor-known? :meal/cook-chill-poultry "cloud-itonami-isic-1020"))))

  (testing "an unregistered product-type-id fails"
    (is (false? (facts/material-handoff-source-actor-known? :meal/nonexistent "cloud-itonami-isic-1010"))))

  (testing "nil source-actor fails"
    (is (false? (facts/material-handoff-source-actor-known? :meal/cook-chill-poultry nil)))))

;; ──────────────── Packaging-Material Lot / Supplier Verification ────────────────

(deftest packaging-lot-supplier-verified-test
  (testing "explicit true passes"
    (is (true? (facts/packaging-lot-supplier-verified?
                {:packaging/lot-id "plot-1" :packaging/supplier-verified? true}))))

  (testing "explicit false fails"
    (is (false? (facts/packaging-lot-supplier-verified?
                 {:packaging/lot-id "plot-1" :packaging/supplier-verified? false}))))

  (testing "missing key fails (never silently trusted)"
    (is (false? (facts/packaging-lot-supplier-verified? {:packaging/lot-id "plot-1"}))))

  (testing "nil packaging-lot fails"
    (is (false? (facts/packaging-lot-supplier-verified? nil)))))

(deftest packaging-handoff-source-actor-known-test
  (testing "isic-1702 (corrugated cases) and isic-2220 (packaging film) are both known"
    (is (true? (facts/packaging-handoff-source-actor-known? "cloud-itonami-isic-1702")))
    (is (true? (facts/packaging-handoff-source-actor-known? "cloud-itonami-isic-2220"))))

  (testing "an unregistered actor fails"
    (is (false? (facts/packaging-handoff-source-actor-known? "cloud-itonami-isic-9999"))))

  (testing "nil source-actor fails"
    (is (false? (facts/packaging-handoff-source-actor-known? nil)))))
