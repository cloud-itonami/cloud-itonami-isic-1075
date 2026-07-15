(ns mealops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [mealops.governor :as governor]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private six-hours-ago (- now-ms (* 6 60 60 1000)))
(def ^:private two-days-ago (- now-ms (* 48 60 60 1000)))

(def ^:private clean-batch
  {:product-type :meal/cook-chill-poultry
   :jurisdiction :us/fda
   :core-cook-temp-c 75.0
   :chill-time-minutes 60.0
   :cold-storage-temp-c 2.0
   :shelf-life-hours-elapsed 24.0
   :water-activity 0.85
   :ph-level 4.5
   :foreign-material-detected? false
   :metal-detector-last-calibration-date six-hours-ago
   :weight-variance-grams 10
   :declared-allergens #{}
   :cross-contact-risk #{}
   :sanitation-score 85
   :packaging-seal-compromised? false
   :evidence-checklist [:raw-material-intake-record :cook-lethality-log :chill-time-log :cold-storage-temp-log
                        :water-activity-test :ph-test :allergen-declaration :weight-check :packaging-seal-check]})

;; ──────────────────────── Batch Registration (generalized) ──────────────────────

(deftest batch-not-registered-violation-test
  (testing "log-production-batch against an unregistered batch is a hard violation"
    (let [req {:op :log-production-batch :subject "batch-ghost"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop {})]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :batch-not-registered) (:violations result)))))

  (testing "schedule-maintenance against an unregistered batch is also a hard violation"
    (let [req {:op :schedule-maintenance :subject "batch-ghost"}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop {})]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :batch-not-registered) (:violations result)))))

  (testing "a registered batch does not trigger this rule"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :batch-not-registered) (:violations result)))))))

;; ──────────────────────── Hard Violations ──────────────────────

(deftest spec-basis-violation-test
  (testing "proposal with no jurisdiction citation is a hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [] :value {:jurisdiction nil}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :no-spec-basis) (:violations result)))))

  (testing "proposal with proper citation passes spec basis check"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Core Cook Temperature Violations (CCP1) ──────────────────────

(deftest core-cook-temp-violation-test
  (testing "batch with core cook temp below the product's minimum triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :core-cook-temp-c 60.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :core-cook-temp-below-minimum) (:violations result)))))

  (testing "batch with core cook temp at/above minimum passes"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Chill/Freeze-Down Time Violations (CCP2) ──────────────────────

(deftest chill-time-violation-test
  (testing "batch with chill time exceeding the product's maximum triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :chill-time-minutes 150.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :chill-time-exceeds-max) (:violations result))))))

;; ──────────────────────── Cold-Storage / Cold-Chain Violations ──────────────────────

(deftest cold-storage-temp-violation-test
  (testing "batch with cold-storage temp out of the product's window triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :cold-storage-temp-c 8.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cold-storage-temp-out-of-range) (:violations result)))))

  (testing "cook-freeze fish product uses a much lower cold-storage window than cook-chill"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch
                                            :product-type :meal/cook-freeze-fish
                                            :chill-time-minutes 200.0
                                            :shelf-life-hours-elapsed 1000.0
                                            :ph-level 6.0
                                            :cold-storage-temp-c 2.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cold-storage-temp-out-of-range) (:violations result))))))

;; ──────────────────────── Shelf Life Violations ──────────────────────

(deftest shelf-life-violation-test
  (testing "batch with elapsed time exceeding the product's shelf life triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :shelf-life-hours-elapsed 200.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :shelf-life-exceeded) (:violations result))))))

;; ──────────────────────── Water Activity Violations ──────────────────────

(deftest water-activity-violation-test
  (testing "batch with water activity exceeding the product's maximum triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :water-activity 0.99)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :water-activity-exceeds-max) (:violations result))))))

;; ──────────────────────── pH Level Violations ──────────────────────

(deftest ph-level-violation-test
  (testing "batch with pH exceeding the product's maximum (under-acidified) triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :ph-level 6.5)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :ph-level-exceeds-max) (:violations result))))))

;; ──────────────────────── Foreign Material Violations ──────────────────────

(deftest foreign-material-violation-test
  (testing "batch with detected foreign material triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :foreign-material-detected? true)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :foreign-material-detected) (:violations result))))))

;; ──────────────────────── Metal Detector Calibration Violations ──────────────────────

(deftest metal-detector-calibration-violation-test
  (testing "batch with overdue metal-detector calibration triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :metal-detector-last-calibration-date two-days-ago)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :metal-detector-calibration-overdue) (:violations result))))))

;; ──────────────────────── Weight Variance Violations ──────────────────────

(deftest weight-variance-violation-test
  (testing "batch with excessive weight variance triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :weight-variance-grams 30)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :weight-variance-excessive) (:violations result))))))

;; ──────────────────────── Allergen Cross-Contact Violations ──────────────────────

(deftest allergen-label-mismatch-violation-test
  (testing "cross-contact risk without a matching declaration triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :cross-contact-risk #{:milk :gluten} :declared-allergens #{})}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :allergen-label-mismatch) (:violations result)))))

  (testing "cross-contact risk WITH a complete declaration passes"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch :cross-contact-risk #{:milk :gluten} :declared-allergens #{:milk :gluten})}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :allergen-label-mismatch) (:violations result)))))))

;; ──────────────────────── Sanitation Score Violations ──────────────────────

(deftest sanitation-score-violation-test
  (testing "batch with insufficient sanitation score triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :sanitation-score 60)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :sanitation-score-insufficient) (:violations result))))))

;; ──────────────────────── Packaging Seal Violations ──────────────────────

(deftest packaging-seal-violation-test
  (testing "batch with a compromised packaging seal triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :packaging-seal-compromised? true)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :packaging-seal-compromised) (:violations result))))))

;; ──────────────────────── Food-Safety Flag Violations ──────────────────────

(deftest food-safety-flag-unresolved-violation-test
  (testing "batch with an unresolved food-safety flag triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch
                                            :safety-concern-raised? true
                                            :safety-concern-resolved? false)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :food-safety-flag-unresolved) (:violations result)))))

  (testing "batch with a resolved food-safety flag does not trigger this rule"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch
                                            :safety-concern-raised? true
                                            :safety-concern-resolved? true)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :food-safety-flag-unresolved) (:violations result)))))))

;; ──────────────────────── Escalation (Low Confidence) ──────────────────────

(deftest low-confidence-escalation-test
  (testing "low confidence proposal escalates even when hard checks pass"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [{:spec "Equipment-Manual"}] :value {:jurisdiction :us/fda} :confidence 0.5}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High Stakes Escalation ──────────────────────

(deftest high-stakes-escalation-test
  (testing "log-production-batch escalates even when all checks pass"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.95}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── Already Processed Violation ──────────────────────

(deftest already-processed-violation-test
  (testing "batch already processed triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :meal/cook-chill-poultry
                            :processed? true}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "UK-FSA-Cook-Chill-Guidance"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-processed) (:violations result))))))

;; ──────────────────────── Already Shipment Finalized Violation ──────────────────────

(deftest already-shipment-finalized-violation-test
  (testing "batch with a shipment already finalized triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :meal/cook-chill-poultry
                            :shipment-finalized? true}}}
          req {:op :coordinate-shipment :subject batch-id}
          prop {:cites [{:spec "Shipment-Manual"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-shipment-finalized) (:violations result))))))
