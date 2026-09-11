(ns run-tests
  (:require [cljs.test :as t]
            [cloud-itonami.pooled-incidence.core-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (js/process.exit 1)))

(t/run-tests 'cloud-itonami.pooled-incidence.core-test)
