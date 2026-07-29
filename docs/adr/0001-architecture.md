# ADR-0001 — pool at the association layer, and refuse rather than estimate

- Status: accepted
- Date: 2026-07-29
- Upstream: `com-junkawasaki/root` ADR-2607284000 (corporate vishing fraud —
  system dynamics and interventions)

## Context

ADR-2607284000 computed, among other things, that a firm cannot measure
its own exposure to a rare adverse event. Zero incidents in 25
company-years is consistent with an annual rate of 11.29% at 95%
confidence; claiming ≤1% needs 299 incident-free company-years. The
victim firm's own report gave 「過去に大きな事故がなかったことによりリスク
認識が弱かった」 as the reason its controls were thin — reading zero as
low, which the arithmetic does not support.

The ADR named the pooled registry as a ranked-but-unbuilt intervention
and observed that the measurement can only exist at a layer that
aggregates members, which is why the `cloud-itonami` association-fact
family is the structural answer.

## Decision

A plain `.cljc` function library, no actor, no I/O, no ledger — the
`cloud-itonami-regulatory-tracker` posture.

**Statistics are delegated, not reimplemented.** The zero-event bound is
`dynamics.core/upper-bound-rate-from-zero-events`. Per the workspace rule
in `com-junkawasaki/root` CLAUDE.md (ADR-2607203000), scoring truth lives
in `kotoba-lang/dynamics` and consumers must not grow a second copy.
`exposure-floor` is the algebraic inverse of that same function, so the
two cannot disagree.

**Three refusals**, all at the pool rather than the observation:
`:no-admissible-observations`, `:mixed-exposure-units`,
`:heterogeneous-event-definition`. The third fires even when every
individual observation is well-formed, because the defect is in the
addition, not in any member's record.

**A refused pool reports `nil`, not `0`.** This is the property the rest
of the design hangs off. If refusal and measured-zero share a
representation, the difference survives exactly as long as the caller
remembers to check a separate flag.

**`:event-definition` is a required admission field.** It is the
machine-readable form of the failure the upstream ADR found: a pool
assembled under per-member definitions of "incident" produces a confident
number about nothing.

**Exposure is unit-tagged and unit-agnostic.** Company-years and drill
trials are the same shape — N of something, K events — so one library
serves both. Mixing them is refused rather than coerced, which is the
only reason tagging is worth the field.

## Consequences

`claim-verdict` answers an underpowered pool with `:exposure-needed` and
`:exposure-deficit` rather than a bare `:no`. The useful output of "you
cannot claim this" is "here is what it would take", and that number is
the one that makes the case for pooling without anyone having to argue
it.

`:members` appears on every result. The library deliberately does not
impose a minimum member count — that threshold would be invented, and a
single member with 299 company-years does clear the arithmetic — but a
pool of one is always visibly a pool of one.

### Known gaps

**No interval for k>0.** With events observed, `rate` returns the
observed rate and `:upper-bound :not-implemented-for-nonzero-events`, and
`claim-verdict` returns `:indeterminate` rather than weak support. A
one-sided Poisson/binomial bound needs an inverse chi-square that is not
implemented here; approximating it would put a number in the same field
meaning something materially weaker than the k=0 case. Filling this gap
is the obvious next increment, and it should arrive as a real method
rather than as a normal approximation quietly applied to small counts.

**Exchangeability is assumed and cannot be checked here.** A pooled bound
describes the population an association covers, not any member of it.
This is stated in the namespace docstring and the README, and it is a
documentation guarantee rather than an enforced one — the library has no
way to detect that a member is unlike its peers.

**Nothing in here validates that reported observations are true.**
`:reported-by` records who claimed, exactly as
`cloud-itonami-regulatory-tracker`'s `:filed-by` does. Self-report
discipline at the association layer is a governance problem this library
supports but does not solve.
