---
status: accepted
---

# Bound infrastructure recovery with progress-decayed debt

SkyPilot has no documented overall cap on infrastructure recovery and cannot reliably distinguish preemption from a failure that destroys the node. Skywright therefore gives every Run Definition a positive, finite maximum **Recovery Debt**, defaulting to three and overridable per Run. This is execution policy outside Run Configuration: the initial Execution Attempt carries no debt, every recovery adds one, and every newly published Durable Safe Point removes one down to a floor of zero. A long Run is not punished for its lifetime recovery count, while repeated recoveries that outpace durable progress eventually make it terminal.

## Runtime startup gate

Every recovered training process evaluates its prospective Recovery Debt from the immutable Execution Attempt and Durable Safe Point history in the Run Store before it creates an Execution Attempt Record or invokes Training Project code. Debt up to and including the configured maximum is admitted. A recovery that would raise the default debt from three to four is refused.

When refusing a recovery, the gate atomically publishes one immutable, run-level **Recovery Exhaustion Record** containing the configured maximum, refused prospective debt, latest Durable Safe Point, prior Execution Attempts used in the calculation, and exhaustion time. It then exits nonzero while the cluster is healthy. ADR 0005 keeps SkyPilot's user-error restarts disabled, so that exit ends SkyPilot's infrastructure-recovery loop without backend polling or cancellation. An already-present identical record is idempotent; a conflict fails closed.

The gate also fails closed before opening an Execution Attempt when it cannot read or validate the complete history, or when it cannot publish evidence that must be durable. It never permits project code to run from unverified state merely because exhaustion could not be proven. If publication of the exhaustion record itself fails, the process still exits nonzero and the eventual retained SkyPilot terminal fact supplies the lifecycle evidence; Skywright does not manufacture an exhaustion record it failed to persist.

## Lifecycle and continuation

A Recovery Exhaustion Record directly derives the Run Lifecycle State as `failed`, even before SkyPilot reflects the process exit. It records a run-level policy decision, not why an earlier process disappeared. The refused process never becomes an Execution Attempt and writes no Execution Termination Report, so no Execution Termination Cause is invented for it.

Exhaustion is terminal. Continuing the training effort uses ADR 0008's checkpoint-seeded clone: a new Run Record and Run Store, seeded from an existing Durable Safe Point, with Recovery Debt starting at zero. There is no unlimited override, zero-recovery mode, wall-clock window, accumulated-cost limit, or lifetime-attempt cap in this policy.
