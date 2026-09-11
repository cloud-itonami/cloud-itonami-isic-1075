(ns mealops.phase
  "Phase machine: the states a prepared-meal/ready-dish (cook-chill or
  cook-freeze) production batch transits through.

  State machine:
    :intake -> :prep -> :cook -> :chill-freeze -> :package -> :inspect ->
    :audit -> :archived

  `:intake` is raw-material receiving; `:prep` is portioning/assembly of
  raw ingredients into the dish; `:cook` is the pasteurization/lethality
  cook step (CCP1) -- never directly controlled by this actor, cook-line
  control remains exclusive to plant staff; `:chill-freeze` is the rapid
  chilling (cook-chill) or blast-freezing (cook-freeze) down through the
  microbial \"danger zone\" to safe cold-storage temperature (CCP2) --
  never directly controlled by this actor; `:package` is vacuum/modified-
  atmosphere (MAP) sealing of the finished dish -- never directly
  controlled by this actor; `:inspect` is metal-detector/X-ray and
  seal-integrity inspection; `:audit` is compliance audit; `:archived` is
  the terminal state.

  Each transition can accept a proposal and yield an audit fact.")

(def all-phases
  "All valid phases in the prepared-meal/ready-dish production
  workflow."
  [:intake :prep :cook :chill-freeze :package :inspect :audit :archived])

(def phase-sequence
  "Ordered phases representing normal batch progression."
  [:intake :prep :cook :chill-freeze :package :inspect :audit :archived])

(defn valid-phase?
  "Check if a phase is valid."
  [phase]
  (contains? (set all-phases) phase))

(defn- index-of
  "Portable (Clojure/ClojureScript) index lookup -- `.indexOf` is a
  JVM-only `java.util.List` method that ClojureScript's PersistentVector
  does not implement, so it is avoided here even though `phase-sequence`
  is a plain vector. Returns -1 when `x` is not found, matching
  `java.util.List/indexOf`'s contract."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll)) -1))

(defn can-transition?
  "Check if a transition from one phase to another is valid
  (must be forward-only in the sequence, no backtracking). Always returns a
  boolean (never nil), including when either phase is invalid."
  [from-phase to-phase]
  (boolean
   (and (valid-phase? from-phase) (valid-phase? to-phase)
        (let [from-idx (index-of phase-sequence from-phase)
              to-idx (index-of phase-sequence to-phase)]
          (and (>= from-idx 0) (>= to-idx 0) (< from-idx to-idx))))))
