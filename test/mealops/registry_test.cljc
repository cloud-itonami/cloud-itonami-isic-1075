(ns mealops.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [mealops.registry :as registry]))

;; ──────────────────────── Core Cook Temperature (CCP1) ──────────────────────

(deftest core-cook-temp-below-minimum-test
  (testing "core cook temp at minimum returns false (no violation)"
    (is (false? (registry/core-cook-temp-below-minimum? 70.0 70.0))))

  (testing "core cook temp above minimum returns false"
    (is (false? (registry/core-cook-temp-below-minimum? 85.0 70.0))))

  (testing "core cook temp below minimum returns true (violation)"
    (is (true? (registry/core-cook-temp-below-minimum? 60.0 70.0)))))

;; ──────────────────────── Chill/Freeze-Down Time (CCP2) ──────────────────────

(deftest chill-time-exceeds-max-test
  (testing "chill time within max returns false (no violation)"
    (is (false? (registry/chill-time-exceeds-max? 60.0 90.0))))

  (testing "chill time at max returns false"
    (is (false? (registry/chill-time-exceeds-max? 90.0 90.0))))

  (testing "chill time exceeding max returns true (violation)"
    (is (true? (registry/chill-time-exceeds-max? 150.0 90.0)))))

;; ──────────────────────── Cold-Storage / Cold-Chain Temperature ──────────────────────

(deftest cold-storage-temp-out-of-range-test
  (testing "cold-storage temp within range returns false (no violation)"
    (is (false? (registry/cold-storage-temp-out-of-range? 2.0 0.0 3.0))))

  (testing "cold-storage temp below range returns true (violation)"
    (is (true? (registry/cold-storage-temp-out-of-range? -5.0 0.0 3.0))))

  (testing "cold-storage temp above range returns true (violation)"
    (is (true? (registry/cold-storage-temp-out-of-range? 8.0 0.0 3.0)))))

;; ──────────────────────── Shelf Life ──────────────────────

(deftest shelf-life-exceeded-test
  (testing "elapsed hours within max returns false (no violation)"
    (is (false? (registry/shelf-life-exceeded? 24.0 120.0))))

  (testing "elapsed hours at max returns false"
    (is (false? (registry/shelf-life-exceeded? 120.0 120.0))))

  (testing "elapsed hours exceeding max returns true (violation)"
    (is (true? (registry/shelf-life-exceeded? 200.0 120.0)))))

;; ──────────────────────── Water Activity ──────────────────────

(deftest water-activity-exceeds-max-test
  (testing "water activity within max returns false (no violation)"
    (is (false? (registry/water-activity-exceeds-max? 0.85 0.97))))

  (testing "water activity at max returns false"
    (is (false? (registry/water-activity-exceeds-max? 0.97 0.97))))

  (testing "water activity exceeding max returns true (violation)"
    (is (true? (registry/water-activity-exceeds-max? 0.99 0.97)))))

;; ──────────────────────── pH Level ──────────────────────

(deftest ph-level-exceeds-max-test
  (testing "pH within max returns false (no violation)"
    (is (false? (registry/ph-level-exceeds-max? 4.2 5.0))))

  (testing "pH at max returns false"
    (is (false? (registry/ph-level-exceeds-max? 5.0 5.0))))

  (testing "pH exceeding max returns true (violation)"
    (is (true? (registry/ph-level-exceeds-max? 6.0 5.0)))))

;; ──────────────────────── Metal Detector Calibration ──────────────────────

(deftest metal-detector-calibration-overdue-test
  (testing "recent calibration returns false (no violation)"
    ;; Assume calibrated 6 hours ago (within the 24-hour shift-based interval)
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          six-hours-ago (- now (* 6 60 60 1000))]
      (is (false? (registry/metal-detector-calibration-overdue? six-hours-ago now)))))

  (testing "overdue calibration returns true (violation)"
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          two-days-ago (- now (* 48 60 60 1000))]
      (is (true? (registry/metal-detector-calibration-overdue? two-days-ago now))))))

;; ──────────────────────── Weight Variance ──────────────────────

(deftest weight-variance-excessive-test
  (testing "variance within tolerance returns false (no violation)"
    (is (false? (registry/weight-variance-excessive? 15 20))))

  (testing "variance at tolerance returns false"
    (is (false? (registry/weight-variance-excessive? 20 20))))

  (testing "variance exceeding tolerance returns true (violation)"
    (is (true? (registry/weight-variance-excessive? 21 20)))))

;; ──────────────────────── Allergen Cross-Contact Labeling ──────────────────────

(deftest allergen-label-mismatch-test
  (testing "no cross-contact risk returns false (no risk) regardless of declaration"
    (is (false? (registry/allergen-label-mismatch? #{} #{}))))

  (testing "cross-contact risk fully covered by declaration returns false (no risk)"
    (is (false? (registry/allergen-label-mismatch? #{:milk :gluten} #{:milk :gluten}))))

  (testing "declaring more than the actual risk set is conservative and returns false"
    (is (false? (registry/allergen-label-mismatch? #{:milk} #{:milk :gluten :soy}))))

  (testing "cross-contact risk not fully covered by declaration returns true (risk)"
    (is (true? (registry/allergen-label-mismatch? #{:milk :gluten} #{:milk})))))

;; ──────────────────────── Foreign Material ──────────────────────

(deftest foreign-material-detected-test
  (testing "no detection returns false"
    (is (false? (registry/foreign-material-detected? false)))
    (is (false? (registry/foreign-material-detected? nil))))

  (testing "detection returns true"
    (is (true? (registry/foreign-material-detected? true)))))

;; ──────────────────────── Sanitation Score ──────────────────────

(deftest sanitation-score-insufficient-test
  (testing "score at minimum returns false (no violation)"
    (is (false? (registry/sanitation-score-insufficient? 75 75))))

  (testing "score above minimum returns false"
    (is (false? (registry/sanitation-score-insufficient? 85 75))))

  (testing "score below minimum returns true (violation)"
    (is (true? (registry/sanitation-score-insufficient? 74 75)))))

;; ──────────────────────── Packaging Seal Integrity ──────────────────────

(deftest packaging-seal-compromised-test
  (testing "no compromise returns false"
    (is (false? (registry/packaging-seal-compromised? false)))
    (is (false? (registry/packaging-seal-compromised? nil))))

  (testing "compromised seal returns true"
    (is (true? (registry/packaging-seal-compromised? true)))))
