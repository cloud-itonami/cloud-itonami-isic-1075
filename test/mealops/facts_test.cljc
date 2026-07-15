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
