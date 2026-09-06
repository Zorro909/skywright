# The storage fake intentionally exposes provider-shaped dynamic dictionaries.
# pyright: reportMissingParameterType=false, reportUnknownParameterType=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownVariableType=false

from __future__ import annotations

import hashlib
import json
import uuid

import pytest
from unit.test_run_store import MemoryS3, recorder

from skywright import CheckpointSnapshot, DatasetCursor, ExecutionAttemptRecord
from skywright.recovery import PreviousWriterEvidence, RecoveryAdmissionError
from skywright.run_store import RunStoreReader


def stopped(attempt: ExecutionAttemptRecord) -> PreviousWriterEvidence:
    return PreviousWriterEvidence(
        attempt.run_id, attempt.attempt_id, "stopped", "test-supervisor:reaped"
    )


def admit(memory, tmp_path, *, proof=stopped, maximum=3):
    writer = recorder(memory, tmp_path)
    resolution = writer.prepare_recovery(
        project_version="project@digest",
        ordering_fingerprint="ordering",
        maximum_debt=maximum,
        previous_writer_verifier=proof,
    )
    snapshot = resolution.checkpoint if resolution else None
    attempt = ExecutionAttemptRecord(
        str(uuid.uuid4()),
        "run",
        "project@digest",
        snapshot.step if snapshot else None,
        snapshot.reference if snapshot else None,
        resolution.rejected if resolution else (),
    )
    writer.publish_attempt(attempt)
    return writer, attempt


def publish(writer, step):
    return writer.publish_checkpoint(
        CheckpointSnapshot(
            step,
            {"value": step},
            run_id="run",
            project_version="project@digest",
            dataset_cursor=DatasetCursor(ordering_fingerprint="ordering"),
        )
    )


def test_default_three_recoveries_then_durable_idempotent_exhaustion(tmp_path):
    memory = MemoryS3()
    attempts = []
    writer = None
    for expected in range(4):
        writer, attempt = admit(memory, tmp_path)
        attempts.append(attempt.attempt_id)
        history = writer.recovery_history(project_version="project@digest")
        assert history.debt == expected
        assert len(history.attempts) == expected + 1
    records = [key for key in memory.objects if key.endswith("/record.json")]
    for _ in range(2):
        with pytest.raises(RecoveryAdmissionError, match="RECOVERY_EXHAUSTED"):
            admit(memory, tmp_path)
    assert records == [key for key in memory.objects if key.endswith("/record.json")]
    assert writer is not None
    key = writer.protocol.run_prefix + "recovery/exhaustion.json"
    body = json.loads(memory.objects[key][0])
    assert body["priorAttempts"] == attempts
    assert body["prospectiveDebt"] == 4 and body["maximumDebt"] == 3
    assert len(memory.put_bodies[key]) == 1
    assert not any(key.endswith("/report.json") for key in memory.objects)


def test_durable_progress_decays_debt_and_retention_preserves_identical_history(
    tmp_path,
):
    memory = MemoryS3()
    writer, _ = admit(memory, tmp_path)
    publish(writer, 1)
    for step in range(2, 7):
        writer, _ = admit(memory, tmp_path)
        assert writer.recovery_history(project_version="project@digest").debt == 1
        reference = publish(writer, step)
        assert publish(writer, step) == reference
        assert writer.recovery_history(project_version="project@digest").debt == 0
    before = writer.recovery_history(project_version="project@digest")
    reader = RunStoreReader(writer.target, client=memory, staging_directory=tmp_path)
    reader.prune_checkpoints(retention=1)
    assert len(reader.list_checkpoints()) == 1
    assert writer.recovery_history(project_version="project@digest") == before
    assert len(before.attempts) == 6 and len(before.checkpoints) == 6
    recovered, _ = admit(memory, tmp_path)
    assert recovered.recovery_history(project_version="project@digest").debt == 1


@pytest.mark.parametrize("fault", ["missing", "corrupt"])
def test_missing_or_corrupt_immutable_history_fails_closed(tmp_path, fault):
    memory = MemoryS3()
    writer, _ = admit(memory, tmp_path)
    publish(writer, 1)
    key = next(key for key in memory.objects if "/recovery/events/" in key)
    if fault == "missing":
        del memory.objects[key]
    else:
        body, metadata, media_type = memory.objects[key]
        body = body.replace(b'"admittedDebt":0', b'"admittedDebt":1')
        memory.objects[key] = (
            body,
            {**metadata, "skywright-sha256": hashlib.sha256(body).hexdigest()},
            media_type,
        )
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_HISTORY_INVALID"):
        admit(memory, tmp_path)
    assert not any(key.endswith("/exhaustion.json") for key in memory.objects)


def test_uncertain_previous_writer_cannot_publish_attempt_or_exhaustion(tmp_path):
    memory = MemoryS3()
    writer, _ = admit(memory, tmp_path)
    before = set(memory.objects)
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_WRITER_UNCERTAIN"):
        recorder(memory, tmp_path).prepare_recovery(
            project_version="project@digest", ordering_fingerprint="ordering"
        )
    assert set(memory.objects) == before
    # The refused recovery did not take ownership or prevent the live writer progressing.
    publish(writer, 1)
    assert writer.recovery_history(project_version="project@digest").debt == 0


def test_competing_initial_admissions_publish_only_one_attempt_record(tmp_path):
    memory = MemoryS3()
    writers = [recorder(memory, tmp_path), recorder(memory, tmp_path)]
    for writer in writers:
        writer.prepare_recovery(
            project_version="project@digest", ordering_fingerprint="ordering"
        )
    first = ExecutionAttemptRecord(str(uuid.uuid4()), "run", "project@digest", None)
    second = ExecutionAttemptRecord(str(uuid.uuid4()), "run", "project@digest", None)
    writers[0].publish_attempt(first)
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_ADMISSION_CONFLICT"):
        writers[1].publish_attempt(second)
    assert (
        writers[1].protocol.attempt_record_key(second.attempt_id) not in memory.objects
    )
    assert writers[0].recovery_history(project_version="project@digest").attempts == (
        first,
    )


def test_checkpoint_upload_failure_does_not_create_publication_evidence(
    tmp_path, monkeypatch
):
    memory = MemoryS3()
    writer, _ = admit(memory, tmp_path)
    before = writer.recovery_history(project_version="project@digest")
    original = memory.put_object

    def put(**request):
        if request["Metadata"]["skywright-kind"] == "checkpoint":
            raise TimeoutError("upload lost")
        return original(**request)

    monkeypatch.setattr(memory, "put_object", put)
    with pytest.raises(TimeoutError):
        publish(writer, 1)
    assert writer.recovery_history(project_version="project@digest") == before
    recovered, attempt = admit(memory, tmp_path)
    assert attempt.seed_checkpoint_reference is None
    assert recovered.recovery_history(project_version="project@digest").debt == 1


def test_missing_latest_payload_falls_back_without_rewriting_debt(tmp_path):
    memory = MemoryS3()
    writer, _ = admit(memory, tmp_path)
    publish(writer, 1)
    latest = publish(writer, 2)
    reader = RunStoreReader(writer.target, client=memory)
    memory.delete_object(Key=reader.list_checkpoints()[-1].key)
    recovered, attempt = admit(memory, tmp_path)
    assert attempt.seed_checkpoint_step == 1
    assert attempt.rejected_corrupt_checkpoints[0].reference == latest
    assert attempt.rejected_corrupt_checkpoints[0].code == "RUN_STORE_MISSING_OBJECT"
    assert recovered.recovery_history(project_version="project@digest").debt == 1


def test_legacy_history_is_not_silently_treated_as_a_new_run(tmp_path):
    memory = MemoryS3()
    writer = recorder(memory, tmp_path)
    writer.publish_attempt(
        ExecutionAttemptRecord(str(uuid.uuid4()), "run", "project@digest", None)
    )
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_HISTORY_INVALID"):
        admit(memory, tmp_path)


def test_progress_during_writer_verification_cannot_create_stale_exhaustion(tmp_path):
    memory = MemoryS3()
    writer, _ = admit(memory, tmp_path, maximum=1)
    writer, _ = admit(memory, tmp_path, maximum=1)

    def finish_then_stop(attempt):
        publish(writer, 1)
        return stopped(attempt)

    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_HISTORY_CHANGED"):
        admit(memory, tmp_path, maximum=1, proof=finish_then_stop)
    assert not any(key.endswith("/exhaustion.json") for key in memory.objects)
    recovered, _ = admit(memory, tmp_path, maximum=1)
    assert (
        recovered.recovery_history(
            project_version="project@digest", maximum_debt=1
        ).debt
        == 1
    )


@pytest.mark.parametrize("maximum", [0, -1, True, 1.5, 2**31])
def test_invalid_recovery_policy_cannot_create_history(tmp_path, maximum):
    memory = MemoryS3()
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_POLICY_INVALID"):
        admit(memory, tmp_path, maximum=maximum)
    assert not memory.objects


def test_policy_is_pinned_for_the_lifetime_of_the_run(tmp_path):
    memory = MemoryS3()
    admit(memory, tmp_path, maximum=1)
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_HISTORY_INVALID"):
        admit(memory, tmp_path, maximum=2)


def test_conflicting_exhaustion_evidence_fails_closed(tmp_path):
    memory = MemoryS3()
    writer, _ = admit(memory, tmp_path, maximum=1)
    admit(memory, tmp_path, maximum=1)
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_EXHAUSTED"):
        admit(memory, tmp_path, maximum=1)
    key = writer.protocol.run_prefix + "recovery/exhaustion.json"
    body, metadata, media_type = memory.objects[key]
    body = body.replace(b'"prospectiveDebt":2', b'"prospectiveDebt":9')
    memory.objects[key] = (
        body,
        {**metadata, "skywright-sha256": hashlib.sha256(body).hexdigest()},
        media_type,
    )
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_HISTORY_INVALID"):
        admit(memory, tmp_path, maximum=1)
    assert len(memory.put_bodies[key]) == 1


def test_lost_conditional_head_response_reconciles_only_exact_committed_bytes(
    tmp_path, monkeypatch
):
    memory = MemoryS3()
    original = memory.put_object

    def put(**request):
        result = original(**request)
        if request["Key"].endswith("/recovery/head.json"):
            raise TimeoutError("response lost after acceptance")
        return result

    monkeypatch.setattr(memory, "put_object", put)
    writer, _ = admit(memory, tmp_path)
    publish(writer, 1)
    assert (
        writer.recovery_history(project_version="project@digest").checkpoints[0].step
        == 1
    )


def test_missing_admitted_attempt_record_stops_recovery(tmp_path, monkeypatch):
    memory = MemoryS3()
    original = memory.put_object

    def put(**request):
        if request["Key"].endswith("/record.json"):
            raise TimeoutError("attempt publication failed")
        return original(**request)

    monkeypatch.setattr(memory, "put_object", put)
    with pytest.raises(TimeoutError):
        admit(memory, tmp_path)
    monkeypatch.setattr(memory, "put_object", original)
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_HISTORY_INVALID"):
        admit(memory, tmp_path)
    assert not any(key.endswith("/exhaustion.json") for key in memory.objects)


def test_terminal_report_cannot_be_recovered_even_with_stopped_writer_proof(tmp_path):
    from skywright import ExecutionTerminationCause, ExecutionTerminationReport

    memory = MemoryS3()
    writer, attempt = admit(memory, tmp_path)
    reference = publish(writer, 1)
    writer.publish_report(
        ExecutionTerminationReport(
            1,
            attempt.attempt_id,
            "run",
            "project@digest",
            ExecutionTerminationCause.COMPLETED,
            1,
            1,
            reference,
            {},
        )
    )
    before = set(memory.objects)
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_RUN_TERMINAL"):
        admit(memory, tmp_path)
    assert set(memory.objects) == before


def test_interruption_report_requires_matching_durable_publication_evidence(tmp_path):
    from skywright import ExecutionTerminationCause, ExecutionTerminationReport

    memory = MemoryS3()
    writer, attempt = admit(memory, tmp_path)
    reference = publish(writer, 1)
    writer.publish_report(
        ExecutionTerminationReport(
            1,
            attempt.attempt_id,
            "run",
            "project@digest",
            ExecutionTerminationCause.INTERRUPTED,
            2,
            2,
            reference,
            {},
        )
    )
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_HISTORY_INVALID"):
        admit(memory, tmp_path)


def test_incomplete_writer_proof_cannot_admit_or_exhaust(tmp_path):
    memory = MemoryS3()
    admit(memory, tmp_path)
    before = set(memory.objects)

    def wrong(previous):
        return PreviousWriterEvidence(
            previous.run_id, str(uuid.uuid4()), "stopped", "fixture:other-process"
        )

    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_WRITER_UNCERTAIN"):
        admit(memory, tmp_path, proof=wrong)
    assert set(memory.objects) == before


def test_library_state_validation_is_complete_and_does_not_mutate_rngs():
    import importlib
    import random

    import numpy as np

    torch = importlib.import_module("torch")

    from skywright._training_state import (
        capture_runtime_state,
        validate_recovery_runtime_state,
    )

    state = {**capture_runtime_state(), "training_determinism": {"seed": 7}}
    python_before = random.getstate()
    numpy_before = np.random.get_state()
    torch_before = torch.get_rng_state().clone()
    validate_recovery_runtime_state(state)
    assert random.getstate() == python_before
    np.testing.assert_equal(np.random.get_state(), numpy_before)
    assert torch.equal(torch.get_rng_state(), torch_before)
    for name in (
        "python_random",
        "numpy_random",
        "torch_cpu_random",
        "training_determinism",
    ):
        with pytest.raises(RecoveryAdmissionError, match="RECOVERY_STATE_INCOMPATIBLE"):
            validate_recovery_runtime_state(
                {key: value for key, value in state.items() if key != name}
            )
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_STATE_INCOMPATIBLE"):
        validate_recovery_runtime_state({**state, "torch_cpu_random": b"invalid"})


def test_recovery_reader_has_one_bounded_provider_retry_loop(tmp_path, monkeypatch):
    memory = MemoryS3()
    writer, _ = admit(memory, tmp_path)
    publish(writer, 1)
    original = memory.get_object
    calls = []

    def get(**request):
        if "/checkpoints/" in request["Key"]:
            calls.append(request["Key"])
            raise TimeoutError("checkpoint GET unavailable")
        return original(**request)

    def no_delay(_: float) -> None:
        return None

    monkeypatch.setattr(memory, "get_object", get)
    monkeypatch.setattr("skywright._run_store.implementation.time.sleep", no_delay)
    with pytest.raises(TimeoutError):
        admit(memory, tmp_path)
    assert len(calls) == 8


def test_clone_recovery_preserves_external_seed_until_own_checkpoint(tmp_path):
    from dataclasses import replace

    from skywright.run_store import RunStoreRecorder

    memory = MemoryS3()
    source, _ = admit(memory, tmp_path)
    reference = publish(source, 1)
    seed = RunStoreReader(source.target, client=memory).read_exact(reference)
    target = replace(source.target, run_id="clone")
    for expected_debt in (0, 1):
        clone = RunStoreRecorder(target, client=memory)
        resolution = clone.prepare_recovery(
            project_version="project@digest",
            ordering_fingerprint="ordering",
            source_run_id="run",
            seed_checkpoint=seed,
            previous_writer_verifier=stopped,
        )
        assert resolution is not None and resolution.checkpoint.reference == reference
        assert resolution.source_run_id == "run"
        attempt = ExecutionAttemptRecord(
            str(uuid.uuid4()), "clone", "project@digest", 1, reference
        )
        clone.publish_attempt(attempt)
        assert (
            clone.recovery_history(project_version="project@digest").debt
            == expected_debt
        )
    own_reference = clone.publish_checkpoint(
        CheckpointSnapshot(
            2,
            {"value": 2},
            run_id="clone",
            project_version="project@digest",
            dataset_cursor=DatasetCursor(ordering_fingerprint="ordering"),
        )
    )
    recovered = RunStoreRecorder(target, client=memory)
    resolution = recovered.prepare_recovery(
        project_version="project@digest",
        ordering_fingerprint="ordering",
        source_run_id="run",
        seed_checkpoint="missing_source_module:must_not_load",
        previous_writer_verifier=stopped,
        ordering_reset=True,
    )
    assert resolution is not None and resolution.checkpoint.reference == own_reference
    assert resolution.source_run_id is None and not resolution.ordering_reset
    history = recovered.recovery_history(project_version="project@digest")
    assert history.seed is not None and history.seed.reference == reference


@pytest.mark.parametrize("change", ["omitted", "reset"])
def test_clone_recovery_cannot_omit_or_change_pinned_seed(tmp_path, change):
    from dataclasses import replace

    from skywright.run_store import RunStoreRecorder

    memory = MemoryS3()
    source, _ = admit(memory, tmp_path)
    reference = publish(source, 1)
    seed = RunStoreReader(source.target, client=memory).read_exact(reference)
    target = replace(source.target, run_id="clone")
    clone = RunStoreRecorder(target, client=memory)
    clone.prepare_recovery(
        project_version="project@digest",
        ordering_fingerprint="ordering",
        source_run_id="run",
        seed_checkpoint=seed,
    )
    clone.publish_attempt(
        ExecutionAttemptRecord(
            str(uuid.uuid4()), "clone", "project@digest", 1, reference
        )
    )
    before = set(memory.objects)
    recovered = RunStoreRecorder(target, client=memory)
    with pytest.raises(RecoveryAdmissionError, match="RECOVERY_SEED_OVERRIDE"):
        recovered.prepare_recovery(
            project_version="project@digest",
            ordering_fingerprint="ordering",
            source_run_id=None if change == "omitted" else "run",
            seed_checkpoint=None if change == "omitted" else seed,
            ordering_reset=change == "reset",
            previous_writer_verifier=stopped,
        )
    assert set(memory.objects) == before
