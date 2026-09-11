(ns cloud-itonami.pooled-incidence.core-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [cloud-itonami.pooled-incidence.core :as pi]))

(defn obs
  "A well-formed observation, overridable per test."
  [& {:as overrides}]
  (merge {:member-id "member-a"
          :exposure 25
          :exposure-unit :company-year
          :events 0
          :observed-from "2001-01-01"
          :observed-to "2026-01-01"
          :event-definition "unauthorized outbound transfer of corporate funds induced by impersonation"
          :reported-by "association secretariat"}
         overrides))

(defn members
  "n members each contributing `each` exposure with `events` events."
  [n each events]
  (mapv #(obs :member-id (str "member-" %) :exposure each :events events) (range n)))

;; ---------------------------------------------------------------------------
;; admission
;; ---------------------------------------------------------------------------

(deftest well-formed-observation-is-admissible
  (is (= [] (pi/observation-violations (obs))))
  (is (pi/admissible? (obs))))

(deftest each-field-has-its-own-rule
  (testing "no field is defaulted or repaired -- each absence is named"
    (is (= [:member-id-missing] (mapv :rule (pi/observation-violations (obs :member-id nil)))))
    (is (= [:exposure-invalid] (mapv :rule (pi/observation-violations (obs :exposure 0)))))
    (is (= [:exposure-invalid] (mapv :rule (pi/observation-violations (obs :exposure -3)))))
    (is (= [:exposure-invalid] (mapv :rule (pi/observation-violations (obs :exposure "25")))))
    (is (= [:exposure-invalid] (mapv :rule (pi/observation-violations (obs :exposure ##Inf)))))
    (is (= [:exposure-unit-invalid] (mapv :rule (pi/observation-violations (obs :exposure-unit "company-year")))))
    (is (= [:events-invalid] (mapv :rule (pi/observation-violations (obs :events -1)))))
    (is (= [:events-invalid] (mapv :rule (pi/observation-violations (obs :events 1.5)))))
    (is (= [:events-invalid] (mapv :rule (pi/observation-violations (obs :events nil)))))
    (is (= [:observation-period-missing] (mapv :rule (pi/observation-violations (obs :observed-to "")))))
    (is (= [:event-definition-missing] (mapv :rule (pi/observation-violations (obs :event-definition "  ")))))
    (is (= [:reporter-missing] (mapv :rule (pi/observation-violations (obs :reported-by nil)))))))

;; ---------------------------------------------------------------------------
;; the three refusals
;; ---------------------------------------------------------------------------

(deftest refused-pool-reports-nil-never-zero
  ;; The single most important property in this namespace. A caller that
  ;; cannot distinguish "we refused" from "we measured zero" will
  ;; eventually publish the second when it had the first.
  (doseq [[label observations]
          [["nothing admissible" [(obs :reported-by nil)]]
           ["mixed units" [(obs) (obs :member-id "b" :exposure-unit :drill-trial)]]
           ["heterogeneous definitions"
            [(obs) (obs :member-id "b" :event-definition "大きな事故")]]]]
    (let [p (pi/pool observations)]
      (is (some? (:refusal p)) label)
      (is (nil? (:exposure p)) (str label " -- exposure must be nil, not 0"))
      (is (nil? (:events p)) (str label " -- events must be nil, not 0"))
      (is (= :refused (:kind (pi/rate p))) label)
      (is (= :refused (:verdict (pi/claim-verdict p 0.01))) label))))

(deftest refusal-rules-are-distinguishable
  (is (= :no-admissible-observations (:rule (:refusal (pi/pool [])))))
  (is (= :no-admissible-observations (:rule (:refusal (pi/pool [(obs :member-id nil)])))))
  (is (= :mixed-exposure-units
         (:rule (:refusal (pi/pool [(obs) (obs :member-id "b" :exposure-unit :drill-trial)])))))
  (is (= :heterogeneous-event-definition
         (:rule (:refusal (pi/pool [(obs) (obs :member-id "b" :event-definition "something else")]))))))

(deftest heterogeneous-definitions-are-refused-even-when-every-observation-is-admissible
  ;; Each member's own observation is perfectly well-formed. The pool is
  ;; still meaningless, and that is exactly the failure mode
  ;; 「過去に大きな事故がなかった」 names: a definition each member wrote
  ;; for itself.
  (let [observations [(obs :member-id "a" :event-definition "大きな事故")
                      (obs :member-id "b" :event-definition "any unauthorized transfer")]]
    (is (every? pi/admissible? observations))
    (is (= :heterogeneous-event-definition (:rule (:refusal (pi/pool observations)))))))

(deftest rejected-observations-are-visible-not-silently-dropped
  (let [p (pi/pool [(obs :member-id "a") (obs :member-id "b" :reported-by nil) (obs :member-id "c")])]
    (is (nil? (:refusal p)))
    (is (= 2 (:members p)) "only admissible members are counted")
    (is (= 1 (count (:rejected p))))
    (is (= [:reporter-missing] (mapv :rule (:violations (first (:rejected p))))))
    (is (= 50 (:exposure p)) "the rejected member's exposure is not counted")))

;; ---------------------------------------------------------------------------
;; the ADR's own numbers
;; ---------------------------------------------------------------------------

(deftest single-firm-25-company-years-reproduces-the-adr
  ;; ADR-2607284000 (d): 25 company-years with zero events is consistent
  ;; with an annual rate of 11.29%.
  (let [p (pi/pool [(obs :exposure 25 :events 0)])
        r (pi/rate p)]
    (is (= :upper-bound (:kind r)))
    (is (= 1 (:members r)))
    (is (< 0.1128 (:rate r) 0.1130) (str "expected ~0.1129, got " (:rate r)))))

(deftest exposure-floor-reproduces-299
  ;; The inverse problem: 1% at 95% confidence needs 299 incident-free
  ;; company-years.
  (is (= 299 (pi/exposure-floor 0.01)))
  (is (= 299 (pi/exposure-floor 0.01 :confidence 0.95)))
  (testing "the floor moves the way it must"
    (is (> (pi/exposure-floor 0.001) (pi/exposure-floor 0.01))
        "a tighter claim needs more exposure")
    (is (> (pi/exposure-floor 0.01 :confidence 0.99) (pi/exposure-floor 0.01 :confidence 0.95))
        "more confidence needs more exposure")))

(deftest one-firm-cannot-support-the-claim-and-is-told-how-far-short-it-is
  (let [v (pi/claim-verdict (pi/pool [(obs :exposure 25 :events 0)]) 0.01)]
    (is (= :insufficient-exposure (:verdict v)))
    (is (= 1 (:members v)))
    (is (= 299 (:exposure-needed v)))
    (is (= 274 (:exposure-deficit v)) "the answer is 'how much more', not just 'no'")))

(deftest the-pool-is-what-makes-the-claim-reachable
  ;; 12 members with 25 company-years each = 300, over the floor. This is
  ;; the structural point: the measurement exists one layer up or nowhere.
  (let [p (pi/pool (members 12 25 0))
        v (pi/claim-verdict p 0.01)]
    (is (= 300 (:exposure p)))
    (is (= 12 (:members p)))
    (is (= :supported (:verdict v)))
    (is (<= (:upper-bound-rate v) 0.01)))
  (testing "one member short and it is still refused"
    (let [v (pi/claim-verdict (pi/pool (members 11 25 0)) 0.01)]
      (is (= :insufficient-exposure (:verdict v)))
      (is (= 24 (:exposure-deficit v))))))

(deftest members-are-always-reported-so-a-pool-of-one-is-never-invisible
  ;; A single member with 299 company-years clears the arithmetic floor.
  ;; The library does not forbid that -- inventing a minimum member count
  ;; would be making up a rule -- but :members is on every result so a
  ;; reader can see what the claim actually rests on.
  (let [v (pi/claim-verdict (pi/pool [(obs :exposure 299 :events 0)]) 0.01)]
    (is (= :supported (:verdict v)))
    (is (= 1 (:members v)) "arithmetically sufficient, structurally one firm -- visible either way")))

;; ---------------------------------------------------------------------------
;; non-zero events
;; ---------------------------------------------------------------------------

(deftest observed-events-give-an-observed-rate-and-a-named-gap
  (let [r (pi/rate (pi/pool (members 10 25 1)))]
    (is (= :observed-rate (:kind r)))
    (is (= 10 (:events r)))
    (is (= 250 (:exposure r)))
    (is (== 0.04 (:rate r)))
    (is (= :not-implemented-for-nonzero-events (:upper-bound r))
        "the gap is named, not filled with a weaker number in the same field")))

(deftest a-rate-above-target-is-a-definite-finding
  (let [v (pi/claim-verdict (pi/pool (members 10 25 1)) 0.01)]
    (is (= :refuted (:verdict v)))
    (is (== 0.04 (:observed-rate v)))))

(deftest a-rate-below-target-is-indeterminate-not-weak-support
  (let [v (pi/claim-verdict (pi/pool (members 10 100 1)) 0.05)]
    (is (= :indeterminate (:verdict v)))
    (is (= :no-interval-method-for-nonzero-events (:reason v)))
    (is (== 0.01 (:observed-rate v)))
    (is (not (contains? v :upper-bound-rate))
        "nothing may present an unbounded observation as if it were bounded")))

;; ---------------------------------------------------------------------------
;; unit-agnosticism
;; ---------------------------------------------------------------------------

(deftest exposure-need-not-be-company-years
  ;; Drill trials are exposure too: N attempts, K failures. Same shape,
  ;; different unit -- which is why the unit is carried and why mixing is
  ;; refused rather than coerced.
  (let [trials (mapv #(obs :member-id (str "m" %) :exposure 40 :exposure-unit :drill-trial
                           :events 0 :event-definition "participant transferred funds without an independent callback")
                     (range 8))
        p (pi/pool trials)]
    (is (nil? (:refusal p)))
    (is (= :drill-trial (:exposure-unit p)))
    (is (= 320 (:exposure p)))
    (is (= :supported (:verdict (pi/claim-verdict p 0.01))))))
