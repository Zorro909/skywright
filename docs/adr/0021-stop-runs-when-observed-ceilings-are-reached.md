---
status: accepted
---

# Stop Runs when observed ceilings are reached

`K3` is implemented with two independent, optional execution-policy fields in the immutable Run Definition: a **Runtime Ceiling** duration and a **Cost Ceiling** amount in the deployment's Reporting Currency. Neither has a default, and a Run may carry either, both, or neither. A checkpoint-seeded clone is a new Run with fresh accounting and explicitly resolved ceilings; a UI may copy the predecessor's values only as editable submission inputs. Direct Training Project execution has no Run Definition and therefore no ceilings.

## Observed exposure, not a hard cap

Runtime is the union of compute-allocation intervals attributable to the Run, including provisioning and setup after allocation and every recovered Execution Attempt. Queueing, gaps with no allocation, storage lifetime, and repatriation do not count. Overlapping intervals are counted once rather than converted into resource-hours.

Cost is the available Run Cost Estimate from ADR 0017, including Applied Rate minimums and billing-quantum rounding already incurred by its Metered Usage. It is never provider-invoice spend. Missing usage, pricing, or currency conversion continues to make the estimate explicitly incomplete; for now that makes a Cost Ceiling visibly unenforceable but neither stops the Run nor substitutes zero for the gap.

The backend initiates a stop on its first observation that accumulated runtime or the available Run Cost Estimate is at least the configured ceiling. It does not reserve headroom for observation cadence, source lag, checkpoint publication, shutdown grace, resource release, or a future billing quantum. Both ceilings are therefore stop triggers, not guaranteed maxima, and the architecture promises no overshoot bound. A user who cannot tolerate exposure above a value must choose a lower trigger. Strict completeness, anticipatory reserves, and hard-cap semantics are deliberately deferred.

## One evaluator, durable decision

The backend is the sole ceiling evaluator because only it can join compute-allocation history, all recovery attempts, Cost Components, and orchestrator state. Before taking control action it atomically persists one immutable, idempotent **Ceiling Stop Decision** with the resolved ceilings, every condition met in that evaluation, observed exposure, estimate completeness, source freshness, and decision time. If runtime and cost reach their ceilings together, the one decision records both rather than choosing an arbitrary winner.

A Run Store **Policy Stop Request** projects that decision to the Training Process Boundary. It carries the decision identity but is not another authority and performs no calculation. Backend restart reconciliation resumes delivery from the durable decision. A recovered process's startup gate only refuses a new Execution Attempt when this decision or its projection already exists; it never evaluates exposure independently. If the backend has not yet observed a reached ceiling, recovery may begin and a later Policy Stop Request follows the ordinary stop path.

## Terminal policy stop

The Run Context honors a Policy Stop Request at its next Safe Point, synchronously publishes that Step as a Durable Safe Point, writes an Execution Termination Report with cause `policy_stopped` referencing the Ceiling Stop Decision, and emits a terminal non-recoverable Training Process Outcome. This deliberately differs from an Interruption Request, whose outcome asks SkyPilot to recover, and from a user-directed Cancellation Request, which creates no cancellation checkpoint. The resulting Run Lifecycle State is `cancelled`; continuation uses a checkpoint-seeded clone.

The backend starts bounded shutdown grace when it delivers the request. If cooperative finalization does not finish in time, it forcibly cancels the SkyPilot job. That path may have no Execution Termination Report, so retained orchestrator cancellation evidence and the Ceiling Stop Decision explain the terminal result without inventing a process-known cause.

Existing termination precedence remains intact. Finalization failures override nominal outcomes; contract violations and Training Project failures retain their causes; explicit user cancellation beats a policy stop; and valid completion beats a request that arrived too late. A Ceiling Stop Decision does not override a Run already completed before the decision took effect, and its disposition records that no stop was effected.
