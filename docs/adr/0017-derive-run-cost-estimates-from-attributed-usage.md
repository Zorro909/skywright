---
status: accepted
---

# Derive run cost estimates from attributed usage and frozen rates

Skywright reports a **Run Cost Estimate**, not provider-invoice spend. It derives that estimate from attributable Metered Usage and immutable Applied Rates because provider billing is neither uniformly available nor reliably attributable to a Run, while SkyPilot's catalog is an estimate rather than a billing authority. The estimate includes every recovery Execution Attempt in the same Run; a terminal Run's checkpoint-seeded clone owns a separate estimate.

## Quote before execution

`K1` is deliberately narrower than the running estimate. A **Cost Quote** considers only configured **Eligible GPU Offerings** whose GPU model, count and purchase mode satisfy the Run Submission's requested target capabilities. Eligibility does not claim immediate live capacity. The quote preserves its candidate and pricing inputs and presents their minimum-to-maximum price in the deployment's Reporting Currency per hour, continuous 24-hour day and continuous 168-hour week. Storage, transfer and auxiliary-resource cost do not enter this pre-run quote.

Every target and Metered Resource family names an explicit **Price Source**. A provider rate surface or SkyPilot's catalog may supply it when trustworthy enough for that target; an operator-maintained schedule is always available and is selected deliberately rather than as a silent fallback. Every Eligible GPU Offering needs a price before the Cost Quote is complete and the cloud Run can start. Other usage or price gaps discovered after launch make the running estimate incomplete without stopping the Run.

## Attributed usage and pricing

The running estimate includes as many directly attributable metered resources as can be observed: compute-allocation time, durable-storage byte-time, directional ingress and egress bytes, request counts, transfers and dedicated auxiliary resources. Shared backend and controller infrastructure, account-wide tiers and free allowances, discounts, credits and taxes are excluded because they cannot be attributed honestly to one Run. Operator schedules express effective attributable rates rather than arbitrary billing programs.

Rates compose from compute-time, byte-time storage, directional byte transfer, request-count and fixed-operation components, with minimum quantities and billing-quantum rounding. The component closest to consumption supplies usage: retained SkyPilot facts for compute allocations, Run Store operations or storage observations for occupancy, Transfer Worker records for transfer, and an auxiliary resource's launcher for its lifetime.

An Applied Rate freezes its value, native currency, unit, billing rules, source, freshness and effective time for the usage interval to which it applies. A source or operator price change starts a new interval and never revalues history. Conversion into the deployment-wide Reporting Currency is frozen and sourced in the same way; a component that cannot be converted is visible but excluded from the reporting-currency aggregate as unpriced.

## Durable source and derivation

The database is the durable source for the estimate's ingredients, partitioned by provenance as ADR 0005 requires. External observations enter source-specific retained-fact storage; operator schedules, Applied Rates and usage measured by Skywright-owned components remain in Skywright-originated storage. The immutable Cost Quote belongs to the Run Definition. No accumulated or final monetary total is stored, and no cost fact is copied into the Run Store: totals are derived on every read from durable usage and rates.

An open usage interval accrues through the read time; a closed interval retains its end. Each **Cost Component** is ongoing while its resource accrues and completed when usage closes, independently of Run Lifecycle State. A terminal Run may therefore have completed compute alongside ongoing cloud-storage cost after failed repatriation. Missing usage, pricing or currency conversion makes the estimate explicitly incomplete and identifies the uncovered component; it never makes that component free. An unexpected gap discovered after launch does not stop the Run.

`U7` aggregates each Run exactly once and separates ongoing from completed components. A checkpoint lineage may be an alternative grouping of its member Runs, but never an additional amount added to them.

## Consequences

The estimate is reproducible and portable across providers, but it is not an invoice reconciliation and must never be presented as actual spend. Price-source adapters and operator schedules become maintained inputs whose freshness is visible. Enforcement of `K3` against an ongoing or incomplete estimate is a downstream decision; this ADR supplies the value model but does not choose the stopping policy.
