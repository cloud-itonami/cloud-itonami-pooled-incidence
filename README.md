# cloud-itonami/cloud-itonami-pooled-incidence

Portable `.cljc` technical commons for **pooling observations of a rare
adverse event across the members of an association**, and for refusing
to answer when the pool cannot honestly support the question.

Same class as its sibling
[`cloud-itonami-regulatory-tracker`](https://github.com/cloud-itonami/cloud-itonami-regulatory-tracker):
a plain function library with no independent decision authority — no
advisor, no governor, no StateGraph, no ledger, no I/O. All statistical
truth is delegated to
[`kotoba-lang/dynamics`](https://github.com/kotoba-lang/dynamics); this
repo owns the record shape, the admission discipline, and the refusals.

## Why this exists

`com-junkawasaki/root` ADR-2607284000 modelled a corporate vishing case
in which ¥1.179bn left a company inside a 23.3-hour window. The victim's
own incident report explained the weak posture as
「過去に大きな事故がなかったことによりリスク認識が弱かった」 — there had
been no large incidents before, so risk was not felt keenly.

That reads zero observed events as a low rate, which is a measurement
error rather than a judgement call. Read correctly:

| | |
|---|---|
| 25 company-years, zero events | consistent with an annual rate of **11.29%** at 95% confidence |
| claiming ≤1% at 95% confidence | needs **299 incident-free company-years** |

No single firm accumulates 299 company-years of its own history. The
measurement a firm needs in order to justify its own controls is one that
can only exist one layer up, where many members' exposure adds together.
That is what this library is for, and why it lives in the `cloud-itonami`
association-fact family rather than inside any single actor.

```clojure
(require '[cloud-itonami.pooled-incidence.core :as pi])

;; one firm, 25 years, nothing ever happened
(pi/claim-verdict (pi/pool [one-firm]) 0.01)
;=> {:verdict :insufficient-exposure :members 1
;    :upper-bound-rate 0.1129 :exposure-needed 299 :exposure-deficit 274 ...}

;; twelve firms with the same history
(pi/claim-verdict (pi/pool twelve-firms) 0.01)
;=> {:verdict :supported :members 12 :exposure 300 :upper-bound-rate 0.0099 ...}
```

The answer to an underpowered pool is **how much more exposure is
needed**, never a number that looks like a measurement.

## The three refusals

Pooling is only meaningful when the things being added are the same kind
of thing. `pool` refuses rather than producing a number when:

- **`:no-admissible-observations`** — every observation failed admission
- **`:mixed-exposure-units`** — company-years and drill trials are both
  exposure and are not addable
- **`:heterogeneous-event-definition`** — members counted different
  things as an event

The third is the load-bearing one. 「過去に大きな事故がなかった」 is
precisely a definition that shifts per member and per year; pooling under
a definition each member wrote for itself produces a confident number
that means nothing. Note that this refusal fires even when *every
individual observation is perfectly well-formed* — the defect is in the
pool, not in any member's record.

**A refused pool reports `:exposure` and `:events` as `nil`, never `0`.**
Zero events is a measurement. A refusal is the absence of one, and a
caller that cannot tell them apart will eventually publish the second when
it had the first.

Inadmissible observations land in `:rejected` with their violations
rather than being silently skipped, so a shrinking pool is visible rather
than merely smaller.

## Admission discipline

Every field is required and none is ever defaulted or repaired:

```clojure
{:member-id        "member-a"          ; opaque, never interpreted here
 :exposure         25                  ; finite positive
 :exposure-unit    :company-year       ; keyword; :drill-trial etc. also fine
 :events           0                   ; non-negative whole number
 :observed-from    "2001-01-01"
 :observed-to      "2026-01-01"
 :event-definition "unauthorized outbound transfer induced by impersonation"
 :reported-by      "association secretariat"}
```

`:event-definition` is required for the same reason `:reported-by` is: an
observation whose meaning is unstated cannot be pooled with one whose
meaning is stated, and the two become indistinguishable once summed.

## What the pooled bound does not say

It is a statement about **the pool**. Pooling assumes members are
exchangeable with respect to the event, and real firms are not — they
differ in size, controls, and exposure to the specific attack. A pooled
upper bound is evidence about the population an association covers, and
is **not** a per-member rate. No member may cite it as its own.

Every result carries `:members` so a reader can always see how many
distinct members a claim rests on. The library does not impose a minimum
member count — inventing that threshold would be making up a rule — but
it makes a pool of one visible as a pool of one.

## Known gap, named rather than filled

With **zero** observed events, `rate` returns a genuine upper bound,
delegated to `dynamics.core/upper-bound-rate-from-zero-events`.

With events observed, it returns the observed rate and
`:upper-bound :not-implemented-for-nonzero-events`. A one-sided bound for
k>0 needs an inverse chi-square (or equivalent) that is not implemented
here, and approximating it would put a number in the same field meaning
something weaker than the k=0 case. `claim-verdict` correspondingly
answers `:indeterminate` — not weak support — when an observed rate falls
below target but cannot be bounded.

## Running

```bash
# nbb (the workspace's mandated script host)
nbb --classpath "src:test:../../kotoba-lang/dynamics/src" test/run_tests.cljs

# JVM, for consumers that reach this library through deps.edn
clojure -M:test
clojure -M:lint
```

## License

AGPL-3.0-or-later, matching the `cloud-itonami-*` convention.
