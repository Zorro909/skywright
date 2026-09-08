"""Immutable registrations and inspectable death proofs owned by one node."""

from __future__ import annotations

import fcntl
import hashlib
import os
import re
import stat
import time
import uuid

from .common import LIMIT, Uncertain, bounded_json, canonical, identifier

STATE_LIMIT = 32768
REGISTRATION_LIMIT = 1000


def validate_registration(record, owner):
    fields = {
        "schema",
        "owner",
        "run_id",
        "attempt_id",
        "registered_at",
        "boot_id",
        "runtime",
        "container_id",
        "container_created_at",
        "container_started_at",
        "image",
        "sandbox_id",
        "pod_uid",
        "process",
        "init",
        "cgroup_root",
        "cgroup_chain",
    }
    if (
        not isinstance(record, dict)
        or set(record) != fields
        or record["schema"] != 1
        or record["owner"] != owner
        or record["runtime"] != "containerd/2.3.1"
    ):
        raise Uncertain()
    for field in ("run_id", "attempt_id", "boot_id", "pod_uid"):
        identifier(record[field])
    for field in ("container_id", "sandbox_id"):
        if (
            not isinstance(record[field], str)
            or re.fullmatch("[0-9a-f]{64}", record[field]) is None
        ):
            raise Uncertain()
    if type(record["registered_at"]) is not int or record["registered_at"] <= 0:
        raise Uncertain()
    for field in ("container_created_at", "container_started_at", "image"):
        if not isinstance(record[field], str) or not record[field]:
            raise Uncertain()
    for field in ("process", "init"):
        process = record[field]
        if not isinstance(process, dict) or set(process) != {
            "pid",
            "start_ticks",
            "pid_namespace",
            "cgroup",
            "container_id",
        }:
            raise Uncertain()
        if (
            type(process["pid"]) is not int
            or process["pid"] <= 0
            or not isinstance(process["start_ticks"], str)
            or not process["start_ticks"].isdigit()
            or re.fullmatch(r"pid:\[[0-9]+\]", process["pid_namespace"]) is None
            or process["container_id"] != record["container_id"]
        ):
            raise Uncertain()
    if (
        record["process"]["pid_namespace"] != record["init"]["pid_namespace"]
        or record["process"]["cgroup"] != record["init"]["cgroup"]
    ):
        raise Uncertain()
    group = record["process"]["cgroup"]
    if (
        not isinstance(group, str)
        or not group.startswith("/")
        or "/../" in group
        or "/./" in group
    ):
        raise Uncertain()
    components = group.split("/")[1:]
    if (
        not components
        or components[-1] != "cri-containerd-" + record["container_id"] + ".scope"
        or any(not component for component in components)
    ):
        raise Uncertain()
    chain = record["cgroup_chain"]
    if not isinstance(chain, list) or len(chain) != len(components):
        raise Uncertain()
    values = [record["cgroup_root"]]
    for component, entry in zip(components, chain, strict=True):
        if not isinstance(entry, list) or len(entry) != 2 or entry[0] != component:
            raise Uncertain()
        values.append(entry[1])
    if any(
        not isinstance(value, list)
        or len(value) != 2
        or any(type(number) is not int or number <= 0 for number in value)
        for value in values
    ):
        raise Uncertain()


def validate_proof(proof, record):
    if (
        not isinstance(proof, dict)
        or set(proof)
        != {
            "schema",
            "run_id",
            "attempt_id",
            "registration_digest",
            "observation",
            "observed_at",
        }
        or proof["schema"] != 1
    ):
        raise Uncertain()
    if (
        proof["run_id"] != record["run_id"]
        or proof["attempt_id"] != record["attempt_id"]
        or proof["registration_digest"] != hashlib.sha256(canonical(record)).hexdigest()
        or type(proof["observed_at"]) is not int
        or proof["observed_at"] < record["registered_at"]
    ):
        raise Uncertain()
    observation = proof["observation"]
    if (
        not isinstance(observation, dict)
        or set(observation) != {"kind", "container_id", "finished_at", "boot_id"}
        or observation["kind"]
        not in {"container-exited-cgroup-empty", "container-exited-cgroup-removed"}
        or observation["container_id"] != record["container_id"]
        or observation["boot_id"] != record["boot_id"]
        or not isinstance(observation["finished_at"], str)
        or not observation["finished_at"]
    ):
        raise Uncertain()


class Custody:
    def __init__(self, directory, node_uid):
        self.directory = directory
        metadata = directory.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != 0
            or metadata.st_mode & 0o077
        ):
            raise Uncertain()
        self.lock = (directory / "lock").open("a")
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.owner = bounded_json(directory / "authority.json", LIMIT)
            if (
                set(self.owner) != {"schema", "node_uid", "authority_id"}
                or self.owner.get("schema") != 1
                or self.owner.get("node_uid") != node_uid
            ):
                raise Uncertain()
            identifier(self.owner["authority_id"])
            self.registrations = {}
            self.proofs = {}
            for path in directory.glob("registration-*.json"):
                if len(self.registrations) >= REGISTRATION_LIMIT:
                    raise Uncertain()
                record = bounded_json(path, STATE_LIMIT)
                validate_registration(record, self.owner)
                key = self.key(record["run_id"], record["attempt_id"])
                if (
                    path.name != "registration-" + key + ".json"
                    or record["owner"] != self.owner
                ):
                    raise Uncertain()
                self.registrations[key] = record
            for path in directory.glob("proof-*.json"):
                proof = bounded_json(path, STATE_LIMIT)
                key = self.key(proof["run_id"], proof["attempt_id"])
                if (
                    path.name != "proof-" + key + ".json"
                    or key not in self.registrations
                    or proof["registration_digest"]
                    != hashlib.sha256(canonical(self.registrations[key])).hexdigest()
                ):
                    raise Uncertain()
                validate_proof(proof, self.registrations[key])
                self.proofs[key] = proof

        except BaseException:
            self.lock.close()
            raise

    def close(self):
        self.lock.close()

    @staticmethod
    def key(run_id, attempt_id):
        return identifier(run_id) + "-" + identifier(attempt_id)

    def put(self, name, value):
        if len(canonical(value)) > STATE_LIMIT:
            raise Uncertain()
        destination = self.directory / name
        if destination.exists():
            if bounded_json(destination, STATE_LIMIT) != value:
                raise Uncertain()
            return
        temporary = self.directory / ("pending-" + str(uuid.uuid4()))
        try:
            with temporary.open("xb") as output:
                os.chmod(temporary, 0o600)
                output.write(canonical(value))
                output.flush()
                os.fsync(output.fileno())
            os.link(temporary, destination)
            descriptor = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        finally:
            temporary.unlink(missing_ok=True)

    def register(self, record):
        validate_registration(record, self.owner)
        key = self.key(record["run_id"], record["attempt_id"])
        existing = self.registrations.get(key)
        if existing is not None:
            # A retry must originate from the same live process and container.
            if any(
                existing[field] != record[field]
                for field in record
                if field != "registered_at"
            ):
                raise Uncertain()
            return
        if len(self.registrations) >= REGISTRATION_LIMIT or any(
            value["container_id"] == record["container_id"]
            and value["boot_id"] == record["boot_id"]
            for value in self.registrations.values()
        ):
            raise Uncertain()
        self.put("registration-" + key + ".json", record)
        self.registrations[key] = record

    def prove(self, key, observation):
        record = self.registrations[key]
        proof = {
            "schema": 1,
            "run_id": record["run_id"],
            "attempt_id": record["attempt_id"],
            "registration_digest": hashlib.sha256(canonical(record)).hexdigest(),
            "observation": observation,
            "observed_at": time.time_ns(),
        }
        validate_proof(proof, record)
        self.put("proof-" + key + ".json", proof)
        self.proofs[key] = proof

    def response(self, key):
        proof = self.proofs[key]
        return {
            "status": "stopped",
            "run_id": proof["run_id"],
            "attempt_id": proof["attempt_id"],
            "reference": "local-writer-proof:"
            + self.owner["authority_id"]
            + ":sha256:"
            + hashlib.sha256(canonical(proof)).hexdigest(),
        }
