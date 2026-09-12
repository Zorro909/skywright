# Issue 283: Vast catalog and actual offer selection

Research date: September 13, 2026. The actual first-offer observation below was
made by the protected operator helper at 22:34:24 UTC on September 12. Research
used public source and distribution artifacts, plus local no-network probes.
No rental was launched.

The released SkyPilot 0.13.0/Vast integration cannot currently select the
assessed affordable CUDA 13 offer through its supported configuration. The
Vast SDK silently discards SkyPilot's resource and location search filters,
then the adapter selects the first surviving globally ranked offer. The
observed first offer requires a USD 5.00 bid, exceeding the owner's strict
USD 0.15/hour ceiling. An explicit numeric bid remains enforceable, but does
not repair resource selection or make that offer affordable. Keep paid
admission closed for this separate compatibility failure.

## Reproduced failure through both real libraries

SkyPilot builds a string beginning:

```text
chunked=true georegion=true geolocation="CN" disk_space>=40
num_gpus=1 gpu_name="RTX 3060" cpu_ram>="64.0"
```

The Vast SDK's `preprocess_search_query` parses directive-bearing strings with
a `Word(alphanums + "_")` value grammar. A quoted location stops parsing after
the two directives. Parsing does not require consuming the entire input, so
the function reconstructs an empty query. The subsequent general query parser
never sees the requested filters. This is not a list-versus-string caller
mismatch.
[SkyPilot launch query](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/provision/vast/utils.py),
[official SDK preprocessor](https://github.com/vast-ai/vast-cli/blob/1c6f8b61d3929a7ae423f89a3a0a53e4e9be02bc/vastai/utils.py)

The exact released SDK sends this search:

```json
{
  "verified": {"eq": true},
  "external": {"eq": false},
  "rentable": {"eq": true},
  "order": [["score", "desc"]],
  "type": "on-demand",
  "allocated_storage": 5.0
}
```

The offline reproducer retains the actual `utils.launch`, SDK `search_offers`,
preprocessing, postprocessing and `create_instance` methods. Only HTTP
transport is replaced. Python audit hooks prohibit socket connections, DNS
lookups and subprocesses; the provider client receives an explicit synthetic
credential. Requesting an RTX 3060 discards a small RTX 3060 fixture and selects
a B200 fixture instead. The create body still contains `price: 0.04`, `disk: 40`
and `cancel_unavail: true`, proving that bid transport and offer selection are
independent findings.

Reproduction:

```sh
uv run --no-project --python 3.12 --with skypilot==0.13.0 --with vastai==1.7.0 \
  python docs/research/issue-283-vast-selection-preflight.py
```

This executed successfully with real `skypilot==0.13.0` and `vastai==1.7.0`.
The earlier bid-only probe mocked `search_offers`, so it established bid
preservation but could not establish correct resource selection.

## Version comparison and upstream status

| Distribution or source | Finding |
| --- | --- |
| `vastai==1.7.0` | Full offline request reproduces discarded filters; exposes `client.api_key` and preserves explicit bid |
| All 38 published `vastai` 1.x releases, from 1.0.0 through 1.7.0 | Their extracted preprocessor source is byte-identical, SHA256 prefix `6ac38efc2de0`; tested representative functions reconstruct the empty query |
| Twelve stable `vastai-sdk` versions inspected from 0.2.5 through 0.6.0 | Legacy `VastAI` implementations lack `self.client`; prior runtime checks on 0.2.5, 0.2.6 and 0.6.0 fail SkyPilot's `client.api_key` requirement |
| SkyPilot `v0.13.1rc1` | The complete Vast `launch` function is byte-identical to 0.13.0; upgrading to this prerelease does not fix selection |

The two SkyPilot launch functions have SHA256
`2d64019e71b63b84b9ea4a0728d30ad8866c2a1876288c14a59768d574d2c3f9`.
Per-version wheel hashes and inspection results are recorded in
`.scratch/issue283/sdk-compatibility-artifacts.json`. No compatible published
version was found that combines the required client interface with correct
parsing of the unchanged SkyPilot query.
[PyPI Vast releases](https://pypi.org/project/vastai/#history),
[PyPI legacy SDK releases](https://pypi.org/project/vastai-sdk/#history),
[prerelease launch source](https://github.com/skypilot-org/skypilot/blob/v0.13.1rc1/sky/provision/vast/utils.py)

The same GPU mismatch is reported upstream in issue 9733. SkyPilot PR 10304
changes the generated query, and Vast SDK PR 502 changes the faulty
preprocessor. Both were still open when checked; neither is a released fix.
Using their branch code or a monkeypatch would change the currently authorized
unchanged-release boundary. No upstream issue, comment or PR was submitted by
this research.
[GPU mismatch report](https://github.com/skypilot-org/skypilot/issues/9733),
[SkyPilot proposed fix](https://github.com/skypilot-org/skypilot/pull/10304),
[Vast proposed parser fix](https://github.com/vast-ai/vast-cli/pull/502)

## Why the catalog cannot repair this

Vast's `chunked` postprocessing requires at least 65536 MiB CPU memory and 32
`cpu_cores`. It then replaces accepted values with those thresholds and resets
`min_bid` to zero. Every affordable offer in
`.scratch/issue283/cuda-bid-offers.json` has less than 65536 MiB CPU memory and
is excluded by this postprocessing. The zero catalog `SpotPrice` is therefore
not evidence of a free or affordable interruptible rental. Preserve the
original offer's bid and fees before SDK postprocessing for cost checks.
[Chunked result handling](https://github.com/vast-ai/vast-cli/blob/1c6f8b61d3929a7ae423f89a3a0a53e4e9be02bc/vastai/utils.py)

The public schema-v8 catalog fetched during research contains 64 rows. Its only
`RTX3060` accelerator row is SKU `1x-RTX_3060_Ti-32-65536`, region
`Ontario, CA, NA`, with 8192 MiB GPU memory, regular price 0.16 and spot price
0.00. This is a 3060 Ti, not the assessed 12 GiB RTX 3060. The generator removes
the `Ti` suffix in the normalized accelerator name; use the full SKU and GPU
memory. Region validation uses complete catalog region strings. An arbitrary
`CN` or `KR` region is not a substitute for a catalog row.
[Official catalog](https://raw.githubusercontent.com/skypilot-org/skypilot-catalog/master/catalogs/v8/vast/vms.csv),
[catalog generation](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/catalog/data_fetchers/fetch_vast.py),
[Vast catalog lookup](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/catalog/vast_catalog.py)

The SDK appends a continent code when `georegion` is active. Consequently,
`region[-2:]` in SkyPilot can be a continent such as `AS`, not country `KR`.
Even a parser-only fix would not make that a country-exact selection. The
assessed ask constraint remains useful for exact resource identity.

Vast's catalog reader does not set a periodic refresh interval. An absent cache
downloads the hosted catalog; an existing cache remains until deliberately
refreshed. The official `sky.catalog.data_fetchers.fetch_vast` generator can
produce a new CSV from a read-only provider search, but still uses the same
chunking and normalization. Refreshing or supplying an accurately generated
catalog cannot change the later launch query, its dropped filters, or the
first-offer selection. Do not fabricate a cheap catalog row as a launch fix.
[Catalog refresh behavior](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/catalog/common.py)

## Actual first-offer check and remaining path

The protected helper sent the exact SDK-default search above with the enrolled
key, retained original pricing, and applied the same CPU eligibility thresholds.
It received 64 offers, of which 53 survived. The first was B200 ask `33945599`
in Oregon, with `min_bid: 5.00`, `dph_total: 5.313802083333333`, and CUDA 13.0.
The next was H200 with minimum bid about 3.947; then a two-GPU H100 offer with
minimum bid 2.244 and CUDA 12.2. The recorded credit remained
USD 2.3402639111259873. Evidence:
`.scratch/issue283/adapter-first-offers.json`. No create request was sent.

There is no supported search-kwargs hook in this adapter. The caller's
`create_instance_kwargs` are processed after the search; the selected offer
overwrites any caller-supplied `id`. `price` controls the create bid, not search
ordering. `use_spot` does not switch this search to bid offers. Neither
`max_hourly_cost` nor a catalog SKU inserts filters into the final request.
`datacenter_only` appends more text after the unreadable quote, so it is also
discarded. No supported option removes those quotes or disables chunking.
[Actual ordering and kwargs handling](https://github.com/skypilot-org/skypilot/blob/v0.13.0/sky/provision/vast/utils.py)

In principle, an exact-ask key and explicit bid could admit a currently suitable
first offer despite the ineffective filters, provided the requested resources
match that offer and all fresh cost checks pass. The actual first-offer check
rules out that path at this observation. A constrained key cannot reorder the
search or make the adapter try the next allowed ask. Denial is safe rejection,
not successful provisioning. The pending no-spend key diagnostic can still
establish a useful future guard independently.

For the assessed cheap RTX 3060 offers, the concrete limitation is upstream
selection compatibility, including chunking, rather than an absent CUDA-capable
marketplace offer. Require an official compatible integration fix and repeat
the full-adapter boundary test before enabling this launch path. CUDA capability
also requires the GPU architecture to be supported by the runtime: a provider
`cuda_max_good >= 13` flag alone does not qualify the GTX TITAN X in the cheap
offer list, because CUDA 13 removed Maxwell compilation and library support.
[NVIDIA CUDA 13 architecture support](https://docs.nvidia.com/cuda/archive/13.0.3/pdf/CUDA_Toolkit_Release_Notes.pdf)
