# Vast.ai interruptible preflight

Checked 2026-09-10 for [#281](https://github.com/Zorro909/skywright/issues/281), before any paid call or real credential projection. The owner selected Vast.ai interruptible first, instances strictly below USD 0.15/hour, preferably cheaper, using approximately USD 2.30 existing credit. No top-up is authorized.

The unchanged SkyPilot 0.13.0 adapter sends its provider credential to the remote instance. After reviewing this finding, the owner corrected the earlier API-server-only rule: official SkyPilot adapters may receive and deliver provider credentials wherever their supported path requires them. [ADR 0025](../adr/0025-centralize-managed-credentials-in-vault.md) now records that boundary and the resulting trust in co-located project code. The observed credential delivery is accepted; actual launch-budget enforcement and live qualification remain outstanding under [ADR 0023](../adr/0023-gate-the-initial-target-and-registry-matrix.md).

## Pinned implementation and current upstream

The installed source is `.graalpy/resources/venv/lib/python3.13/site-packages/sky/`. Its `provision/vast/utils.py` is byte-for-byte identical to both the [v0.13.0 release file](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/provision/vast/utils.py) and [upstream master at e74689ad7e111c182078f0786600f7d612270503](https://github.com/skypilot-org/skypilot/blob/e74689ad7e111c182078f0786600f7d612270503/sky/provision/vast/utils.py), checked by fresh HTTPS downloads. All three have SHA-256 `d2b8993b761e9805bcb14e566288455d0a8621d49fdfb66c9baf02e173f9ac17`. No SDK file was edited.

Local source inspection finds:

- `launch()` reads the provider client's API key and inserts it into a shell command writing `~/.vast_api_key`. It prepends that command even with a template or custom startup command.
- It searches again using hardware, country, disk and optional datacenter constraints, then chooses the first returned offer. The query carries no maximum price, explicit offer ID, bid search type or price ordering. A caller-supplied `id` is overwritten.
- For interruptible launches, an explicit `price` is retained; `bid_price` is normalized to `price`. Otherwise the first offer's `min_bid` becomes the bid. No launch-time comparison with `Resources.max_hourly_cost` occurs in this helper.

The separate [Vast cloud implementation](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/clouds/vast.py) passes `max_hourly_cost` into catalogue filtering, but its deployment variables do not carry that ceiling into the launch helper. It also returns `~/.config/vastai/vast_api_key` as a credential file mount. The [Vast cluster template](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/templates/vast-ray.yml.j2) includes credential mounts and uses root SSH access. These are two distinct delivery paths: the normal credential mount and the startup command.

## Configuration options and their limits

| Option | What the inspected implementation permits | Qualification consequence |
| --- | --- | --- |
| `vast.remote_identity: NO_UPLOAD` | Generic deployment code excludes the provider's credential file mounts. | Does not affect `launch()`'s independent startup command. |
| `create_instance_kwargs.onstart_cmd` or `onstart` | Adds commands after SkyPilot's required commands. | Deleting the file afterward still delivers the key to the remote instance and puts it in startup text. |
| Template, image, user, environment or Docker arguments | Customizes instance creation. | The key-bearing startup command is still constructed; changing the image does not establish an isolation boundary. |
| `create_instance_kwargs.price` / `bid_price` | Sets the submitted interruptible bid. | Useful future control, but does not bound disk, traffic, total spend or the selected offer's other rates. |
| `create_instance_kwargs.id` | Overwritten by the first fresh search result. | Cannot pin a previously approved offer this way. |
| `cancel_unavail` | Passed through to instance creation. | Vast documents failure when the offer cannot start immediately; this is an availability option, not a price or credential guarantee. |

The [configuration reference](https://docs.skypilot.ai/en/latest/reference/config.html#vast-create-instance-kwargs) documents startup-command composition, templates, bid, disk and other creation arguments. The installed [configuration schema](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/utils/schemas.py) allows `NO_UPLOAD` for Vast, and [deployment code](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/backends/backend_utils.py) implements credential-mount exclusion. No matching switch exists in the inspected Vast launch helper. Provider-side creation behavior is documented in [Creating Instances with the API](https://docs.vast.ai/api-reference/creating-instances-with-api).

Vast supports [scoped API keys and parameter constraints](https://docs.vast.ai/api-reference/permissions). A provisioning key should eventually exclude billing and key administration where possible. Restricted permissions reduce the authority exposed through the owner-accepted adapter delivery. Root project code in the same Vast container can read the copied provider key; the amended contract explicitly accepts that shared trust. A provider-enforced bid constraint is a possible additional safeguard; its exact creation endpoint, parameter handling and rejection behavior have not been qualified. No such policy is claimed to be deployed.

The original API-server-only rule had no supported mitigation in the inspected adapter. Its credential-copy-only demotion and the proposed upstream suppression prerequisite were withdrawn after the owner's 2026-09-10 correction. No upstream patch is required solely to permit this delivery. Manual rentals or an external provisioning service feeding an SSH pool would still be different integrations with new lifecycle, pricing and recovery obligations; none is introduced here.

## Reproduction results

The [diagnostic](issue-281-vast-preflight.py) passed against the installed SkyPilot 0.13.0 helper on 2026-09-10. It replaces only the provider client boundary and uses a synthetic key and offer list. A Python audit hook rejects socket connections, DNS resolution, subprocess launches and `os.system` calls. Run it from the repository root after the pinned integration-test environment has been prepared:

```bash
uv run --no-project --python backend/target/skypilot-api-server-venv/bin/python \
  docs/research/issue-281-vast-preflight.py
```

| Recorded case | Result |
| --- | --- |
| Default interruptible launch | Selected synthetic offer `101` and submitted its USD 0.20/hour minimum bid, despite a second offer `202` with a USD 0.03/hour minimum. |
| Explicit offer `202`, bid USD 0.04/hour and custom startup `true` | Replaced the offer ID with `101`, retained the explicit bid, and appended the custom startup after the synthetic key write. |
| On-demand launch | Submitted no bid and still included the synthetic provider key in the remote startup command. |
| All three creation requests | Included the synthetic provider key in startup text writing `~/.vast_api_key`. |
| Credential mount declaration | Returned `~/.config/vastai/vast_api_key` as both remote and local path. |

The reported source hash matches the installed, release and upstream files above. These numbers are deliberately synthetic; the test does not show that Vast would accept the low bid on that offer. It does not execute a remote shell, copy a credential, call a live provider or test `NO_UPLOAD` end to end. The file-mount exclusion finding comes from source inspection.

## Budget and interruption considerations

Vast's [search API](https://docs.vast.ai/api-reference/search/search-offers) distinguishes on-demand and bid searches, exposes `min_bid`, and accepts storage allocation for pricing. The [creation API](https://docs.vast.ai/api-reference/creating-instances-with-api) requires an explicit `price` for interruptible rentals; omitting it requests on-demand. Future admission must check a fresh actual offer and submit an explicit bid strictly below the owner's limit, with room for the allocated disk charge. A catalogue ceiling alone does not establish that guarantee.

[Billing](https://docs.vast.ai/guides/reference/billing) separates active compute, allocated storage and traffic in both directions. Storage remains billable while an instance is stopped and online; offline instances have different billing treatment. Therefore, budget planning must include image and Dataset downloads, checkpoint uploads, idle retained disks and final deletion. Advertised hourly compute is not a total-cost cap. The reported USD 2.30 is not a verified balance. Existing auto-billing settings must be checked before renting because [pricing guidance](https://docs.vast.ai/guides/instances/pricing) describes automatic replenishment when a payment method is saved; this preflight changes no billing settings.

[Interruptible instances](https://docs.vast.ai/guides/instances/choosing/instance-types) can pause when outbid or when on-demand capacity takes priority, then resume when priority returns. Recovery qualification must account for a previous attempt resuming, prove previous-writer exclusion, and ensure cleanup reaches both old and replacement rentals. Relaunch may incur another image/Dataset download and overlapping retained storage. This is an inference from provider pause/resume semantics and Skywright's recovery contract; no real interruption has been tested here.

## Next dependency

The owner-approved SkyPilot credential boundary now permits the observed delivery in both modes. ADR 0023 restores the original intended on-demand/spot scope and distinguishes scope from passed qualification gates. The owner selected interruptible first; #283 now owns its implementation and live qualification rather than an upstream credential-suppression fix. It must record actual credential projections and remote copies, and verify interruption, previous-writer proof, recovery, pricing and cleanup. Interruptible launch and recovery must preserve an explicit bid and validate the actual offer's disk and traffic rates before spending. This decision does not authorize local SDK patches or a different provider.

Later account work also needs the provider SDK dependencies, a Vault-backed provider binding, verified available credit and billing settings, fresh suitable offers, private-image pull credentials, reachable Dataset/Run Store endpoints, and an NVIDIA-compatible qualification image. No real account, offer or storage access was tested. No account secret is needed to complete this investigation.

Real private-image execution, GPU work, interruption, writer proof, Managed Jobs recovery, usage capture and terminal cleanup remain required by #57 and ADR 0023. Synthetic launch arguments cannot satisfy those gates or demonstrate that a GPU below the requested price is currently available.
