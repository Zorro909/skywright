#!/usr/bin/env python3
"""Reproduce Vast launch behavior without network access or real credentials.

Run with a Python environment containing the pinned skypilot==0.13.0. Only the
provider client boundary is replaced. SkyPilot's installed source is unchanged.
This is a diagnostic of adapter behavior, not a live provider qualification.
"""

import hashlib
import importlib.metadata
import inspect
import json
from pathlib import Path
import sys
from types import SimpleNamespace
from unittest.mock import patch


def prohibit_network(event, _args):
    if event in {"socket.connect", "socket.getaddrinfo", "subprocess.Popen", "os.system"}:
        raise RuntimeError(f"Offline diagnostic prohibits {event}")


sys.addaudithook(prohibit_network)

version = importlib.metadata.version("skypilot")
if version != "0.13.0":
    raise RuntimeError(f"Expected pinned SkyPilot 0.13.0; found {version}")

from sky.clouds.vast import Vast  # noqa: E402
from sky.provision.vast import utils  # noqa: E402


class RecordingProvider:
    """Synthetic Vast API responses, including an expensive first offer."""

    def __init__(self):
        self.client = SimpleNamespace(api_key="synthetic-not-a-vast-credential")
        self.queries = []
        self.creates = []
        self.offers = [
            {"id": 101, "min_bid": 0.20, "dph_total": 0.40},
            {"id": 202, "min_bid": 0.03, "dph_total": 0.06},
        ]

    def search_offers(self, **kwargs):
        self.queries.append(kwargs)
        return self.offers

    def create_instance(self, **kwargs):
        self.creates.append(kwargs)
        return {"new_contract": 9001}

    def show_instance(self, **kwargs):
        if kwargs != {"id": 9001}:
            raise AssertionError("Unexpected synthetic instance lookup")
        return {"id": 9001}


def run_case(overrides):
    provider = RecordingProvider()
    with patch.object(utils.vast, "vast", return_value=provider):
        instance = utils.launch(
            name="skywright-offline-diagnostic",
            instance_type="1x-RTX_3060-16384",
            region="US",
            disk_size=20,
            image_name="example.invalid/synthetic@sha256:" + "0" * 64,
            ports=None,
            preemptible=True,
            secure_only=True,
            create_instance_kwargs=overrides,
        )
    if instance != 9001 or len(provider.creates) != 1:
        raise AssertionError("Unexpected launch flow")
    creation = provider.creates[0]
    command = creation["onstart_cmd"]
    return {
        "search_arguments": provider.queries,
        "selected_offer": creation["id"],
        "submitted_bid_usd_per_hour": creation["price"],
        "requested_disk_gb": creation["disk"],
        "provider_key_in_startup_command": provider.client.api_key in command,
        "writes_remote_provider_key": "> ~/.vast_api_key" in command,
        "custom_startup_appended": command.endswith(";true"),
    }


def require(condition, message):
    if not condition:
        raise AssertionError(message)


default = run_case(None)
configured = run_case({"id": 202, "price": 0.04, "onstart_cmd": "true"})
mounts = Vast().get_credential_file_mounts()

require(default["provider_key_in_startup_command"], "Key interpolation did not reproduce")
require(default["writes_remote_provider_key"], "Remote key write did not reproduce")
require(default["selected_offer"] == 101, "First-offer selection did not reproduce")
require(default["submitted_bid_usd_per_hour"] > 0.15, "Unbounded default bid did not reproduce")
require(configured["selected_offer"] == 101, "Requested offer ID was not overwritten")
require(configured["submitted_bid_usd_per_hour"] == 0.04, "Explicit bid was not preserved")
require(configured["provider_key_in_startup_command"], "Custom startup removed key interpolation")
require(configured["custom_startup_appended"], "Custom startup was not appended")
require("~/.config/vastai/vast_api_key" in mounts, "Credential-file projection did not reproduce")

source = Path(inspect.getfile(utils))
print(json.dumps({
    "skypilot_version": version,
    "launch_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "network_and_subprocesses": "prohibited",
    "provider_boundary": "synthetic recording client",
    "default_interruptible": default,
    "explicit_bid_and_offer_override": configured,
    "remote_credential_mounts": mounts,
    "result": "credential isolation and default-price risks reproduced",
}, indent=2))
