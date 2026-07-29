(ns cloud-itonami.pooled-incidence.core
  "Pooled incidence registry for a rare adverse event observed across the
  MEMBERS of an association, and the refusals that make the pooling
  honest.

  ## Why this exists

  `com-junkawasaki/root` ADR-2607284000 computed the following about a
  corporate vishing case. The victim's own incident report explained the
  weak risk posture as `過去に大きな事故がなかったことによりリスク認識が
  弱かった` -- there had been no large incidents before, so risk was not
  felt keenly. That reasoning reads zero observed events as a low rate,
  and it is a measurement error, not a judgement call.

  Read correctly, 25 company-years with zero events is consistent with an
  annual rate as high as **11.29%** at 95% confidence. Run the inverse:
  claiming a rate at or below 1% needs **299 incident-free company-years**.
  No single firm accumulates 299 company-years of its own history. The
  measurement a firm needs to justify its own controls is one that can
  only exist one layer up, where many members' exposure adds together --
  which is why this library sits in the `cloud-itonami` association-fact
  family rather than inside any single actor.

  ## What this library is

  A pure function library over observations. It files nothing, fetches
  nothing, stores nothing, and holds no decision authority -- same posture
  as its sibling `cloud-itonami-regulatory-tracker`. All statistical truth
  is delegated to `kotoba-lang/dynamics`
  (`dynamics.core/upper-bound-rate-from-zero-events`); this namespace owns
  the record shape, the admission discipline, and the refusals, and
  deliberately does not reimplement the mathematics.

  ## The three refusals

  Pooling is only meaningful when the things being added are the same
  kind of thing. `pool` refuses rather than producing a number when:

  - **nothing is admissible** -- every observation failed admission
  - **exposure units are mixed** -- company-years and drill trials are
    both exposure and are not addable
  - **the event definition is not identical across members** -- this is
    the load-bearing one. `過去に大きな事故がなかった` is exactly a
    definition that shifts per member and per year; pooling under a
    definition each member wrote for itself produces a confident number
    that means nothing.

  A refused pool reports `:exposure` and `:events` as `nil`, never `0`.
  Zero is a measurement. Refusal is the absence of one, and a caller must
  not be able to read one as the other.

  ## What the pooled bound does and does not say

  It is a statement about THE POOL. Pooling assumes the members are
  exchangeable with respect to the event, and real firms are not: they
  differ in size, controls, and exposure to the specific attack. A pooled
  upper bound is therefore evidence about the population an association
  covers, and is NOT a per-member rate -- no member may cite it as its own.
  This library reports `:members` on every result so that a reader can
  always see how many distinct members a claim rests on."
  (:require [clojure.string :as str]
            [dynamics.core :as dyn]))

;; ---------------------------------------------------------------------------
;; Observation shape
;; ---------------------------------------------------------------------------
;;
;; {:member-id         <opaque -- never interpreted here>
;;  :exposure          <positive number -- how much was observed>
;;  :exposure-unit     <keyword, e.g. :company-year, :drill-trial>
;;  :events            <non-negative integer -- how many occurred>
;;  :observed-from     <"YYYY-MM-DD">
;;  :observed-to       <"YYYY-MM-DD">
;;  :event-definition  <non-blank string -- WHAT COUNTS as an event>
;;  :reported-by       <non-blank string -- who supplied this>}

(def observation-keys
  "Every field an observation must carry to be admissible. None is ever
  defaulted or synthesized -- an observation missing any of them is
  rejected, not repaired."
  [:member-id :exposure :exposure-unit :events
   :observed-from :observed-to :event-definition :reported-by])

(defn- finite-number? [v]
  (and (number? v) (= v v) (> v ##-Inf) (< v ##Inf)))

(defn- non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(defn- whole-number? [v]
  (and (finite-number? v)
       (== v #?(:clj (Math/floor (double v)) :cljs (js/Math.floor v)))))

(defn observation-violations
  "Admission discipline for a single observation. Returns a vector of
  `{:rule .. :detail ..}`, empty when the observation is admissible.

  `:event-definition` is required for the same reason `:reported-by` is:
  an observation whose meaning is unstated cannot be pooled with one whose
  meaning is stated, and the two are indistinguishable once summed."
  [obs]
  (let [{:keys [member-id exposure exposure-unit events
                observed-from observed-to event-definition reported-by]} obs]
    (into []
          (concat
           (when (nil? member-id)
             [{:rule :member-id-missing
               :detail "an observation must name the member it came from"}])
           (when-not (and (finite-number? exposure) (pos? exposure))
             [{:rule :exposure-invalid
               :detail (str ":exposure must be a finite positive number, got " (pr-str exposure))}])
           (when-not (keyword? exposure-unit)
             [{:rule :exposure-unit-invalid
               :detail (str ":exposure-unit must be a keyword naming what was counted, got "
                            (pr-str exposure-unit))}])
           (when-not (and (whole-number? events) (not (neg? events)))
             [{:rule :events-invalid
               :detail (str ":events must be a non-negative whole number, got " (pr-str events))}])
           (when-not (and (non-blank-string? observed-from) (non-blank-string? observed-to))
             [{:rule :observation-period-missing
               :detail "both :observed-from and :observed-to are required -- exposure without a period cannot be audited"}])
           (when-not (non-blank-string? event-definition)
             [{:rule :event-definition-missing
               :detail (str ":event-definition is required and must state WHAT COUNTS as an event."
                            " Zero events under an unstated definition is not a measurement.")}])
           (when-not (non-blank-string? reported-by)
             [{:rule :reporter-missing
               :detail ":reported-by is required -- never defaulted or auto-generated"}])))))

(defn admissible?
  "Does `obs` carry every field, well-formed? Sugar over
  `observation-violations`."
  [obs]
  (empty? (observation-violations obs)))

;; ---------------------------------------------------------------------------
;; Pooling
;; ---------------------------------------------------------------------------

(defn pool
  "Sum admissible observations into a pool, or refuse.

  Returns:

    {:exposure          <number or nil when refused>
     :events            <number or nil when refused>
     :members           <count of DISTINCT :member-id values admitted>
     :exposure-unit     <the single unit, or nil>
     :event-definition  <the single definition, or nil>
     :admitted          [<observation> ...]
     :rejected          [{:observation .. :violations [..]} ...]
     :refusal           nil | {:rule .. :detail ..}}

  `:exposure` and `:events` are `nil` -- NOT `0` -- whenever `:refusal` is
  set. Zero events is a measurement; a refusal is the absence of one, and
  a caller that cannot tell them apart will eventually report the second
  as the first.

  Inadmissible observations are dropped into `:rejected` with their
  violations rather than silently skipped, so a shrinking pool is visible
  rather than merely smaller."
  [observations]
  (let [{admitted true rejected false} (group-by admissible? (vec observations))
        rejected (mapv (fn [o] {:observation o :violations (observation-violations o)}) rejected)
        units (set (map :exposure-unit admitted))
        definitions (set (map :event-definition admitted))
        refusal (cond
                  (empty? admitted)
                  {:rule :no-admissible-observations
                   :detail (str "none of the " (count rejected) " supplied observations passed admission")}

                  (> (count units) 1)
                  {:rule :mixed-exposure-units
                   :detail (str "cannot add exposure measured in different units: " (pr-str units))}

                  (> (count definitions) 1)
                  {:rule :heterogeneous-event-definition
                   :detail (str "members counted different things as an event: " (pr-str definitions)
                                ". Pooling under per-member definitions produces a confident number"
                                " that means nothing.")})]
    (cond-> {:members (count (distinct (map :member-id admitted)))
             :exposure-unit (when (= 1 (count units)) (first units))
             :event-definition (when (= 1 (count definitions)) (first definitions))
             :admitted (vec admitted)
             :rejected rejected
             :refusal refusal}
      (nil? refusal) (assoc :exposure (reduce + (map :exposure admitted))
                            :events (reduce + (map :events admitted)))
      (some? refusal) (assoc :exposure nil :events nil))))

;; ---------------------------------------------------------------------------
;; Rates and bounds
;; ---------------------------------------------------------------------------

(defn- ln [x]
  #?(:clj (Math/log x) :cljs (js/Math.log x)))

(defn- ceil [x]
  #?(:clj (long (Math/ceil x)) :cljs (js/Math.ceil x)))

(defn exposure-floor
  "How much exposure a pool needs before zero observed events can support
  a claim of 'the rate is at most `target-rate`' at `confidence`.

  The inverse of `dynamics.core/upper-bound-rate-from-zero-events`:
  n = ln(1-confidence) / ln(1-target-rate), rounded up.

  At the ADR's parameters -- 1% annual rate, 95% confidence -- this is
  299 company-years, which is the number no single firm can reach."
  [target-rate & {:keys [confidence] :or {confidence 0.95}}]
  {:pre [(< 0 target-rate 1) (< 0 confidence 1)]}
  (ceil (/ (ln (- 1 confidence)) (ln (- 1 target-rate)))))

(defn rate
  "What the pool actually supports about the underlying rate.

  Three shapes, distinguished by `:kind`:

    {:kind :refused      :refusal {..}}
      -- the pool refused; there is nothing to compute over.

    {:kind :upper-bound  :rate r :confidence c :exposure .. :members ..}
      -- ZERO events observed. `r` is the (1-c) upper bound from
         `dynamics.core/upper-bound-rate-from-zero-events`: how large the
         rate could plausibly be and still produce no events over this
         much exposure. NOT a point estimate; a point estimate needs at
         least one observed event.

    {:kind :observed-rate :rate r :upper-bound :not-implemented-for-nonzero-events ..}
      -- events were observed. `r` is events/exposure, the observed rate.
         This library deliberately does NOT return a confidence interval
         here: a one-sided bound for k>0 needs an inverse chi-square (or
         equivalent) that is not implemented, and approximating it would
         put a number in the same field that means something weaker than
         the k=0 case. The gap is named rather than filled."
  [pooled & {:keys [confidence] :or {confidence 0.95}}]
  (let [{:keys [exposure events members refusal exposure-unit event-definition]} pooled]
    (if refusal
      {:kind :refused :refusal refusal :members members}
      (let [base {:exposure exposure :events events :members members
                  :exposure-unit exposure-unit :event-definition event-definition}]
        (if (zero? events)
          (assoc base
                 :kind :upper-bound
                 :confidence confidence
                 :rate (dyn/upper-bound-rate-from-zero-events exposure :confidence confidence))
          (assoc base
                 :kind :observed-rate
                 :rate (/ events (double exposure))
                 :upper-bound :not-implemented-for-nonzero-events))))))

(defn claim-verdict
  "Can this pool support the claim 'the rate is at most `target-rate`'?

  Returns `{:verdict .. :members .. ...}` where `:verdict` is one of:

    `:refused`               the pool refused; no claim can be assessed
    `:supported`             zero events, and the upper bound at
                             `confidence` is at or below `target-rate`
    `:refuted`               events were observed at a rate ABOVE
                             `target-rate` -- a definite finding
    `:insufficient-exposure`  zero events, but the bound is still above
                             `target-rate`. Carries `:exposure-needed` and
                             `:exposure-deficit` so the answer is 'how much
                             more', not just 'no'.
    `:indeterminate`          events were observed at or below
                             `target-rate`, but bounding that requires the
                             interval method this library does not
                             implement (see `rate`). Reported as unknown
                             rather than as weak support.

  Nothing here returns a bare number for an underpowered pool. That is
  the whole point: the failure this library exists to prevent is a real
  measurement being manufactured out of insufficient exposure."
  [pooled target-rate & {:keys [confidence] :or {confidence 0.95}}]
  (let [r (rate pooled :confidence confidence)
        common {:members (:members pooled)
                :exposure (:exposure pooled)
                :events (:events pooled)
                :exposure-unit (:exposure-unit pooled)
                :target-rate target-rate
                :confidence confidence}]
    (case (:kind r)
      :refused {:verdict :refused :refusal (:refusal r) :members (:members pooled)}
      :upper-bound
      (if (<= (:rate r) target-rate)
        (assoc common :verdict :supported :upper-bound-rate (:rate r))
        (let [needed (exposure-floor target-rate :confidence confidence)]
          (assoc common
                 :verdict :insufficient-exposure
                 :upper-bound-rate (:rate r)
                 :exposure-needed needed
                 :exposure-deficit (- needed (:exposure pooled)))))
      :observed-rate
      (if (> (:rate r) target-rate)
        (assoc common :verdict :refuted :observed-rate (:rate r))
        (assoc common
               :verdict :indeterminate
               :observed-rate (:rate r)
               :reason :no-interval-method-for-nonzero-events)))))
