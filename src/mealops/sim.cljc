(ns mealops.sim
  "Simulation driver for testing the prepared-meals/ready-dish (cook-chill
  and cook-freeze) manufacturing operations actor end-to-end.

  For CLI: clojure -M:dev:run

  Example flow:
    1. Start with empty store
    2. Create a batch in :intake phase
    3. Propose a batch -> :chill-freeze transition with processing parameters
    4. Governor validates parameters against facts
    5. If valid, audit fact is committed
    6. CLI prints audit trail")

(defn -main [& _args]
  (println "MealOps simulation: not yet implemented.")
  (println "TODO: integrate langgraph-clj StateGraph when available."))
