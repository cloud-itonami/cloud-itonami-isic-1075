(ns mealops.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [mealops.phase :as phase]))

;; ──────────────────────── Phase Validity ──────────────────────

(deftest valid-phase-test
  (testing "intake is valid"
    (is (true? (phase/valid-phase? :intake))))

  (testing "chill-freeze is valid"
    (is (true? (phase/valid-phase? :chill-freeze))))

  (testing "archived is valid"
    (is (true? (phase/valid-phase? :archived))))

  (testing "invalid phase returns false"
    (is (false? (phase/valid-phase? :invalid)))))

;; ──────────────────────── Phase Transitions ──────────────────────

(deftest can-transition-test
  (testing "intake -> prep is valid (forward progression)"
    (is (true? (phase/can-transition? :intake :prep))))

  (testing "intake -> chill-freeze is valid (skip prep/cook)"
    (is (true? (phase/can-transition? :intake :chill-freeze))))

  (testing "cook -> intake is invalid (backward)"
    (is (false? (phase/can-transition? :cook :intake))))

  (testing "chill-freeze -> archived is valid (forward to end)"
    (is (true? (phase/can-transition? :chill-freeze :archived))))

  (testing "archived -> intake is invalid (backward from end)"
    (is (false? (phase/can-transition? :archived :intake))))

  (testing "same phase is invalid"
    (is (false? (phase/can-transition? :chill-freeze :chill-freeze))))

  (testing "invalid phases return false"
    (is (false? (phase/can-transition? :invalid :chill-freeze)))
    (is (false? (phase/can-transition? :chill-freeze :invalid)))))
