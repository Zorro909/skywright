# Checkpoint publisher shutdown qualification

[Issue #247](https://github.com/Zorro909/skywright/issues/247) tracks a Training
Process that printed the expected cancellation result and then aborted during
Python shutdown. Qualification on 2026-09-09 identified a publisher lifetime race.

## Cause and evidence

The publisher cleared its active snapshot and notified the waiting Training
Process before its thread finished unwinding the cancellation exception. The
exception's traceback still owned checkpoint frames and tensors. The Training
Process treated an empty active slot as completed cleanup, published its report,
and returned while the daemon publisher was still releasing those objects.

The original test at `4ca50bb` reproduced the exact failure in 3 of 500 subprocesses
with eight concurrent workers on CPython 3.12.11 and PyTorch 2.12.0+cpu. Each failed
process printed the expected JSON, then exited with signal 6 and
`terminate called without an active exception`. Two preceding batches of 100
repetitions, one at `4ca50bb` and one at `c613bfd`, passed. These clean batches did
not establish safety.

The original September 5 core and a new September 9 reproduction core both show
the main thread in `Py_FinalizeEx`. The aborting publisher's stack, from its
exception cleanup toward the abort, includes:

```text
BaseException_dealloc
  tb_dealloc -> frame_dealloc -> dict_dealloc
  THPVariable_dealloc -> THPVariable_clear
  PyEval_RestoreThread -> take_gil -> PyThread_exit_thread
  pthread_exit -> _Unwind_ForcedUnwind -> std::terminate -> abort
```

GDB reports missing anonymous file-backed mappings and a possible executable
mismatch when loading these systemd cores. The same symbolic stack in the fresh
core, the controlled application regression, and the independent native
reproduction below provide corroborating evidence; the diagnosis does not rely
on unavailable local-variable symbols or a line-level interpretation of the old
core. Raw cores remain private local diagnostics.

PyTorch releases the GIL while destroying tensor data and reacquires it at the end
of that scope. See [PyTorch 2.12.0's tensor cleanup](https://github.com/pytorch/pytorch/blob/v2.12.0/torch/csrc/autograd/python_variable.cpp#L3506-L3536).
CPython 3.12 documents that acquiring the GIL through `PyEval_RestoreThread` while
finalizing terminates the calling thread. The forced unwind crossing native C++
cleanup accounts for the observed `std::terminate` stack. See the
[CPython 3.12 API contract](https://docs.python.org/3.12/c-api/init.html#c.PyEval_RestoreThread)
and [CPython's native thread finalization issue](https://github.com/python/cpython/issues/87135).

## Minimized native reproduction

This standalone CPU program removes the Run Store, cancellation policy, checkpoint
capture, and Skywright itself. It leaves only a daemon thread releasing real
tensors while the main thread exits. Save it as a temporary script and run it in
the locked SDK `ml-test` environment on Python 3.12. It deliberately reproduces a
native abort; it is diagnostic code, not a normal test-suite assertion.

```python
import sys
import threading

import torch

ready = threading.Event()


def cleanup():
    tensors = [torch.empty(1) for _ in range(10000)]
    ready.set()
    del tensors


worker = threading.Thread(target=cleanup, daemon=True)
worker.start()
assert ready.wait(10)
if "--join" in sys.argv:
    worker.join(10)
    assert not worker.is_alive()
print("finished", flush=True)
```

With four concurrent subprocesses, the unjoined version reproduced the exact
SIGABRT and stderr in 19 of 20 runs. Adding only `--join` passed 20 of 20. This
provides a high-rate native reproduction without depending on the narrow
cancellation scheduling window.

## Application regression and fix

`test_cancellation_waits_for_publisher_traceback_cleanup` exercises the real
`run_training_process` boundary. A recorder-local object owns a PyTorch tensor and
survives in the cancellation traceback. Its destructor holds cleanup at that
boundary using events. Both cases failed before the fix:

- Cleanup released within grace must finish before the recorder resumes and the
  cancellation report is published.
- Cleanup held beyond grace must return a `TimeoutError` diagnostic, suppress the
  report, and leave recorder cancellation active. The test releases and joins the
  thread afterward so its intentional stall does not contaminate test shutdown.

`test_terminal_checkpoint_waits_for_earlier_publisher_thread_cleanup` holds a
successful cadence publisher's thread-local destructor while the next cadence
publication runs. Terminal completion must wait for the earlier thread too.

The coordinator retains live publisher thread handles and joins them outside its
condition lock. Publication and thread exit consume the same existing shutdown
deadline. Dead handles are discarded during scheduling and shutdown instead of
accumulating with Run history. Daemon status remains necessary for the existing
bounded failure path when a native call never returns. This implements
[ADR 0014](../adr/0014-separate-safe-points-from-execution-termination.md)'s cleanup barrier;
it does not change acceptance criteria or introduce a new grace period.

The unchanged original cancellation test, run against the fixed application,
passed 500 of 500 subprocesses with eight concurrent workers on Python 3.12.11.
The controlled regression establishes the missing lifetime barrier; the clean
stress batch is supporting evidence rather than proof that native runtimes cannot
fail.

## Python coverage and limits

The cancellation and shutdown cases are exercised with the locked CPU PyTorch
stack across supported CPython versions. Each row combines the eight cancellation
and deadline cases with the earlier-publisher cleanup regression. Every version
used PyTorch 2.12.0+cpu.

| CPython | Focused cases |
|---|---|
| 3.10.18 | 9 passed |
| 3.11.13 | 9 passed |
| 3.12.11 | 9 passed |
| 3.13.6 | 9 passed |
| 3.14.6 | 9 passed |

Run these checks from the repository root with the desired interpreter:

```bash
uv run --project sdk --locked --python 3.12 --group ml-test pytest \
  sdk/tests/unit/test_training_process.py \
  -k 'cancellation or shutdown_deadline or earlier_publisher_thread_cleanup'
```

The complete SDK contributor checks and installed-artifact CI results are recorded
in the PR. The historical test was already failing in CI on Python 3.12.14; the
local native-abort measurements above specifically use 3.12.11.

This qualification does not cover CUDA, ROCm, free-threaded Python, or arbitrary
project-created daemon threads. It establishes that cooperative Skywright
checkpoint cleanup finishes before a successful termination report. A publisher
that exceeds grace still cannot be forcibly stopped safely inside Python; the
attempt remains unfinalized and managed cancellation retains its external forced
termination path. No native abort is caught, ignored, or converted to success.

CPython 3.13.8 changed the affected finalization APIs to hang a calling thread
instead of terminating it. A clean exit on a newer interpreter therefore does
not by itself prove correct cleanup. See the
[CPython 3.13 API change](https://docs.python.org/3.13/c-api/init.html#c.PyEval_RestoreThread).
The application tests assert cleanup ordering and bounded failure independently
of that interpreter-specific symptom.
