---
status: accepted
---

# Bind one Run Context to each training process

Each training process belongs to exactly one Run Context for its entire lifetime. The first construction attempt irrevocably claims the process before any training-runtime state changes; failure does not release that claim, and every later attempt is a Training Contract violation. SkyPilot already gives each Execution Attempt a fresh pod and container, while direct execution must likewise begin in a fresh process or notebook kernel. This deliberately rejects both concurrent and sequential Run Context reuse because PyTorch and Python expose ambient state that cannot be isolated or reset completely, and a convention would violate `B4` by allowing silent reproducibility failures.

## Process boundary

A library-owned **Training Process Boundary** constructs the sole Run Context, invokes the Training Project's entry point, finalizes the Execution Termination Report, and terminates with a **Training Process Outcome**. It owns the outer process lifecycle, not the project-owned training loop. The context is passed into that entry point rather than constructed by ordinary Training Project code.

The Run Context permanently owns the process's training-runtime setup: Python, NumPy, and PyTorch CPU and accelerator RNGs; deterministic numerical settings; library workers, samplers, and writers; and cooperative interruption handling. It does not restore that state. Accelerator access remains explicit through the context rather than a mutated default device, root logging remains host-owned, and thread pools, allocator behavior, accelerator-runtime configuration, and relevant environment variables are fixed at process startup by the Environment Profile.

## Outcomes and signals

Training Process Outcomes are orchestration controls rather than diagnoses. Completion emits success. A cooperative interruption emits the dedicated recoverable outcome configured in SkyPilot's `recover_on_exit_codes` only after its last Safe Point has become durable and its Execution Termination Report has been published. Cancellation emits a terminal non-recoverable outcome, while contract violations, Training Project failures, and Skywright failures emit terminal failure. The report retains the exact Execution Termination Cause; an abrupt process or infrastructure loss may emit no trustworthy Skywright outcome, leaving SkyPilot to act on cluster health.

An explicit Cancellation Request always wins. Otherwise the first `SIGINT` or `SIGTERM` creates an Interruption Request and proves no cause, especially not preemption. The handler merely records the request; the Run Context honors it at the next Safe Point. A repeated signal or expired shutdown grace forces immediate termination and cannot emit the recoverable outcome because safe finalization did not complete.

## Consequences

Warnings and returning an existing context are forbidden because both permit the caller to continue with the wrong run identity and ambient state. A failed initialization, repeated direct invocation, or repeated notebook cell requires a fresh process. Numeric exit-code values remain an implementation choice, but only the safely finalized interruption code may be configured to request recovery.

## Managed project entry point

For #231, the owner selected a fixed project module convention on 2026-09-06. Every managed Training Project Image supplies `skywright_project.train(context)`. The library invokes that callable only after validating the accepted Run Definition, its pinned artifacts and recovery admission. The image digest pins the implementation; the project definition and version manifest do not add a configurable entry-point field. Direct embedding retains its private injection seams.
