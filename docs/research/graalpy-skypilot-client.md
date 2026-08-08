# Can GraalPy load and drive SkyPilot's client SDK?

Facts for [ADR 0009](../adr/0009-drive-skypilot-through-its-python-sdk.md), which
fixed the bridge as SkyPilot's Python SDK running in-process under GraalPy in JVM
mode, with an out-of-process Python service as the named fallback. This note
settles what the primary sources say. It does **not** return a verdict — that
belongs to the prototype this research blocks.

Native-image is out of scope throughout.

## Answer

Nothing in GraalPy's documentation forbids the arrangement, and nothing in it
promises the arrangement works either. Four findings are decision-relevant, and
three of them are negative.

1. **There is no client half.** SkyPilot 0.13.0 ships one distribution with one
   `install_requires`. Importing `sky.client.sdk` executes `sky/__init__.py`,
   which eagerly imports `sky.backends`, `sky.clouds` and `sky.batch`; that pulls
   375 `sky.*` modules including 127 under `sky.provision`, 22 under
   `sky.server`, and third-party FastAPI, SQLAlchemy, Alembic, psycopg2, casbin,
   Paramiko, NumPy and psutil. The minimal install is `pip install skypilot`
   with **no extra**, and the base install already contains every dependency of
   the `server` extra except `grpcio` and `protobuf`. The client cannot be
   imported without the server and provisioning code.

2. **29 installed distributions ship compiled extension modules, and 36 native
   modules are actually loaded by the import.** GraalPy supports the CPython C
   API but not its ABI, so every one of them must be **rebuilt from source by
   GraalPy's own patched `pip`** — PyPI's `manylinux` wheels are unusable.
   GraalPy publishes prebuilt wheels for exactly 13 packages, of which only
   `numpy`, `psutil`, `httptools`, `uvloop` and `watchfiles` are in SkyPilot's
   tree, all at versions older than SkyPilot 0.13.0 resolves to, and **none
   built for GraalPy newer than 25.0**.

3. **Three packages on the import path are marked incompatible in GraalPy's own
   published compatibility data**: `cffi` (required by both `cryptography` and
   `PyNaCl`, which Paramiko requires), `frozenlist` (required by `aiohttp`), and
   `watchfiles` (pulled by `uvicorn[standard]`). GraalPy's note for that status
   code reads "We have been unable to build, install, or run tests for this
   package." A further nine, including `aiohttp`, `SQLAlchemy`, `orjson`,
   `greenlet`, `psutil`, `multidict` and `yarl`, are recorded as installing but
   untested. `ijson`, `propcache` and `rpds-py` are absent from the dataset
   entirely. This is the single most likely thing to sink the arrangement and
   it can only be settled by attempting the install.

4. **ADR 0009's concurrency model is workable but has two documented
   constraints.** A GraalPy Context does permit multiple Java threads, serialized
   by a per-Context GIL, and GraalPy releases that GIL around blocking socket and
   SSL reads, so a followed log stream would not starve other threads *within* a
   Context. But running long-held work on a *second* Context, as ADR 0009
   requires, means running C extensions in multiple contexts: that is
   Linux-only, requires the **experimental** `python.IsolateNativeModules=true`
   on every Context in the process, and GraalPy says "many C extensions still
   have issues in this mode." Separately, GraalPy 25.1+ documents that **Python
   native extensions are not compatible with Java virtual threads** — a Spring
   Boot application must dispatch GraalPy calls to a *platform*-thread executor.

Version alignment is the one question with a clean answer: the SkyPilot client
version can be pinned exactly, either as `<package>skypilot==0.13.0</package>`
plus a committed `graalpy.lock`, or via a `requirementsFile`.

## Evidence

### 1. The dependency tree

**SkyPilot 0.13.0 is one distribution, not a client and a server.**
`setup.py` calls `setuptools.find_packages()` and declares a single
`install_requires`, with extras keyed by cloud plus `remote`, `server` and
`all`. There is no `client` extra. (✓ VERIFIED —
[`setup.py`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/setup_files/setup.py#L155-L175),
[`extras_require`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/setup_files/dependencies.py#L309-L316))

**The documented client install is the bare package.** SkyPilot's API-server
guide tells a client machine to run `pip install -U skypilot` and then
`sky api login`. It also recommends "a Python 3.9 or 3.10 environment for the
SkyPilot client"; GraalPy 25.x implements Python 3.12.8 (see §3). (✓ VERIFIED —
[Connecting to an API server](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/docs/source/reference/api-server/api-server.rst#L76-L88))

**`install_requires` is already almost the whole server dependency set.** The
base requirement list contains `fastapi`, `uvicorn[standard]`, `sqlalchemy`,
`alembic`, `psycopg2-binary`, `asyncpg`, `aiosqlite`, `greenlet`, `casbin`,
`sqlalchemy_adapter`, `passlib`, `bcrypt==4.0.1`, `pyjwt`, `prometheus_client`,
`paramiko`, `gitpython`, `pandas`, `psutil`, `cryptography`, `orjson`, `ijson`
and `aiohttp`. The `server` extra adds only `grpcio` and `protobuf` on top of
what the base install already has. Choosing "no extra" therefore avoids exactly
two dependencies. (✓ VERIFIED —
[`install_requires`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/setup_files/dependencies.py#L13-L102),
[`server_dependencies`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/setup_files/dependencies.py#L118-L132))

**SkyPilot itself says the client imports server code.** Above `casbin` and
`sqlalchemy_adapter` in `install_requires` sits the comment: "TODO(hailong):
These three dependencies should be removed after we make the client-side
actually not importing them." (✓ VERIFIED —
[dependencies.py](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/setup_files/dependencies.py#L86-L89))

**Importing the client SDK executes the whole package.** `sky/__init__.py`
imports `sky.backends`, `sky.batch` and `sky.clouds` before re-exporting the SDK
functions, so `import sky.client.sdk` cannot avoid them. (✓ VERIFIED —
[`sky/__init__.py`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/__init__.py#L93-L142))

**Measured import graph.** `pip install skypilot==0.13.0` (no extras) into a
clean CPython 3.12.11 virtual environment resolves 86 distributions occupying
329 MB of `site-packages`. `import sky.client.sdk` then loads 1811 modules, of
which 375 are `sky.*`:

| Package group | Modules loaded |
|---|---|
| `sky.provision.*` | 127 |
| `sky.clouds.*` | 34 |
| `sky.serve.*` | 32 |
| `sky.server.*` | 22 |
| `sky.jobs.*` | 12 |
| `sky.backends.*` | 8 |
| `sky.users.*` | 4 |

(✓ VERIFIED by execution; ✗ UNCERTAIN that a GraalPy run would resolve the same
distribution versions — see §5.)

The chains that matter, each traced by a recording `MetaPathFinder`:

- `fastapi` ← `sky.server.plugins` ← `sky.server.plugin_utils` ←
  `sky.utils.controller_utils` ← `sky.serve.serve_utils` ← … ← `sky.jobs` ←
  `sky.backends.cloud_vm_ray_backend` ← `sky.backends` ← `sky`
- `cryptography` ← `paramiko.transport` ← `paramiko` ←
  `sky.provision.slurm.utils` ← `sky.provision.slurm` ← `sky.provision` ←
  `sky.clouds.aws` ← `sky.clouds` ← `sky.check` ← `sky.data.storage` ← … ← `sky`
- `sqlalchemy` ← `sky.skypilot_config` ← `sky.usage.usage_lib` ←
  `sky.backends.backend` ← `sky.backends` ← `sky`
- `alembic` ← `sky.utils.db.migration_utils` ← `sky.global_user_state` ←
  `sky.skylet.job_lib` ← … ← `sky`
- `casbin` + `sqlalchemy_adapter` ← `sky.users.permission` ←
  `sky.workspaces.core` ← `sky.backends.backend_utils` ← … ← `sky`
- `numpy` ← `sky.optimizer` ← `sky.backends.cloud_vm_ray_backend` ← `sky`
- `psutil` ← `sky.skylet.subprocess_daemon` ← `sky.utils.subprocess_utils` ← … ←
  `sky`
- `ijson` ← `sky.provision.kubernetes.utils` ← … ← `sky.clouds.aws` ← `sky`

(✓ VERIFIED by execution against the installed 0.13.0 wheel; the source-level
`from` statements are visible in
[`sky/client/sdk.py`](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/client/sdk.py#L29-L65).)

**Not imported despite being installed:** `pandas`, `pulp`, `networkx`,
`setproctitle`, `uvicorn` (and therefore `uvloop`, `httptools`, `watchfiles`).
They must still be *built and installed* for the environment to resolve, which
is what matters under GraalPy. (✓ VERIFIED by execution.)

**Conclusion for the ADR:** "the client half" is not a thing that can be
installed or imported separately at 0.13.0. Any GraalPy environment for the
SkyPilot client is a GraalPy environment for essentially all of SkyPilot.

### 2. Native extension modules and GraalPy's documented status

**GraalPy's rule.** "GraalPy provides experimental support for this API… The
support extends only to the API, not the binary interface (ABI), so extensions
built for CPython are not binary compatible with GraalPy. Packages that use the
native API must be built and installed with GraalPy, and the prebuilt wheels for
CPython from pypi.org cannot be used. For best results, it is crucial that you
only use the `pip` command that comes preinstalled in GraalPy virtual
environments… Please do not update `pip` or use alternative tools such as
`uv`." (✓ VERIFIED —
[Native-Extensions.md](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Native-Extensions.md))

**What must be rebuilt.** In the CPython reference install, 29 distributions
ship `.so` files:

`aiohttp`, `asyncpg`, `bcrypt`, `cffi`, `charset_normalizer`, `cryptography`,
`frozenlist`, `greenlet`, `httptools`, `ijson`, `markupsafe`, `multidict`,
`numpy`, `orjson`, `pandas`, `pendulum`, `propcache`, `psutil`,
`psycopg2_binary`, `pydantic_core`, `pynacl`, `pyyaml`, `rpds_py`,
`setproctitle`, `sqlalchemy`, `uvloop`, `watchfiles`, `websockets`, `yarl`.

36 native modules from 21 of those distributions are actually loaded by
`import sky.client.sdk`, spanning every implementation technology named in the
ticket: Rust/pyo3 (`pydantic_core._pydantic_core`,
`cryptography.hazmat.bindings._rust`, `orjson.orjson`, `rpds.rpds`,
`bcrypt._bcrypt`), Cython/C (`aiohttp._http_parser`, `yarl._quoting_c`,
`multidict._multidict`, `frozenlist._frozenlist`, `propcache._helpers_c`,
`sqlalchemy.cyextension.*`, `psycopg2._psycopg`, `psutil._psutil_linux`,
`ijson.backends._yajl2`, `markupsafe._speedups`, `yaml._yaml`,
`greenlet._greenlet`, `numpy._core._multiarray_umath`), mypyc
(`charset_normalizer.*`), and libffi/libsodium (`_cffi_backend`,
`nacl._sodium`). gRPC and protobuf are **not** on this list — they are the two
things the server extra adds. (✓ VERIFIED by execution.)

**GraalPy's published per-package status.** graalpy.org's compatibility page
loads a CSV of `name,version,test_status,notes` and renders it with
`check_compatibility_helpers.js`. The status codes decode as: `0` Compatible
(>90% of the package's tests pass), `1` Currently Untested, `2` Currently
Incompatible, `3` Not Supported. When the notes field is `0.00`, status `1`
renders as "The package installs, but the test suite was not set up for
GraalPy" and status `2` renders as "We have been unable to build, install, or
run tests for this package." The latest published dataset is `v250`
(GraalPy 25.0). (✓ VERIFIED —
[dataset](https://graalpy.org/module_results/python-module-testing-v250.csv),
[decoding rules](https://graalpy.org/assets/js/check_compatibility_helpers.js),
[legend and status switch](https://graalpy.org/python-developers/compatibility/))

| Distribution | Version SkyPilot 0.13.0 resolves | GraalPy row (v250) | Status |
|---|---|---|---|
| `cffi` | 2.1.1 | `cffi,1.17.1,2,0.00` | **Incompatible — unable to build/install** |
| `frozenlist` | 1.8.0 | `frozenlist,1.6.0,2,0.00` | **Incompatible — unable to build/install** |
| `watchfiles` | 1.2.0 | `watchfiles,1.0.5,2,0.00` | **Incompatible — unable to build/install** |
| `aiohttp` | 3.14.3 | `aiohttp,3.11.18,1,0.00` | Untested |
| `SQLAlchemy` | 2.0.51 | `SQLAlchemy,2.0.40,1,0.00` | Untested |
| `orjson` | 3.11.9 | `orjson,3.10.18,1,0.00` | Untested |
| `greenlet` | 3.5.4 | `greenlet,3.2.1,1,0.00` | Untested |
| `psutil` | 7.2.2 | `psutil,7.0.0,1,0.00` | Untested |
| `multidict` | 6.7.1 | `multidict,6.4.3,1,0.00` | Untested |
| `yarl` | 1.24.5 | `yarl,1.20.0,1,0.00` | Untested |
| `uvloop` | 0.22.1 | `uvloop,0.21.0,1,0.00` | Untested |
| `websockets` | 17.0.1 | `websockets,15.0.1,1,0.00` | Untested |
| `asyncpg` | 0.31.0 | `asyncpg,0.30.0,1,0.77` | Untested, 0.77% passing |
| `PyNaCl` | 1.6.2 | `PyNaCl,1.5.0,1,54.08` | Untested, 54.08% passing |
| `pendulum` | 3.2.0 | `pendulum,3.1.0,1,34.26` | Untested, 34.26% passing |
| `pydantic_core` | 2.46.4 | `pydantic_core,2.39.0,0,100.00` | Compatible at 2.39.0 |
| `cryptography` | 50.0.0 | `cryptography,44.0.2,0,100.00` | Compatible at 44.0.2 |
| `PyYAML` | 6.0.3 | `PyYAML,6.0.2,0,100.00` | Compatible at 6.0.2 |
| `bcrypt` | 4.0.1 (SkyPilot pins `==`) | `bcrypt,4.3.0,0,100.00` | Compatible at 4.3.0 |
| `numpy` | 2.5.1 | `numpy,2.2.4,0,99.53` | Compatible at 2.2.4 |
| `psycopg2-binary` | 2.9.12 | `psycopg2-binary,2.9.10,0,98.13` | Compatible at 2.9.10 |
| `MarkupSafe` | 3.0.3 | `MarkupSafe,3.0.2,0,98.72` | Compatible at 3.0.2 |
| `pandas` | 3.0.5 | `pandas,2.2.3,0,93.17` | Compatible at 2.2.3 |
| `setproctitle` | 1.3.7 | `setproctitle,1.3.5,0,82.14` | Compatible at 1.3.5 |
| `httptools` | 0.8.0 | `httptools,0.6.4,0,100.00` | Compatible at 0.6.4 |
| `charset-normalizer` | 3.4.9 | `charset-normalizer,3.4.1,0,80.00` | Compatible at 3.4.1 |
| `ijson` | 3.5.1 | — | **Not in the dataset** |
| `propcache` | 0.5.2 | — | **Not in the dataset** |
| `rpds-py` | 2026.6.3 | — | **Not in the dataset** |

(✓ VERIFIED — rows read from the CSV linked above; resolved versions measured
from the CPython reference install.)

**Note the version skew in every row.** GraalPy's data is for the version
GraalPy tested, which in every single case is older than what SkyPilot 0.13.0
resolves today. A "Compatible" mark is evidence about a version the build will
not install unless it is pinned there.

**`cffi` deserves particular attention.** `cryptography>=2.0` and `PyNaCl` both
require `cffi>=2.0.0` when the interpreter is not PyPy, and Paramiko requires
both — so `cffi` is squarely on the client import path. GraalPy's dataset marks
`cffi` 1.17.1 status `2`, yet GraalPy also ships patches for `cffi == 1.15.1`
and `cffi >= 1.16.0` and a `cffi.sh` wheel-build script. The signals contradict
each other and only an attempted install resolves it. (✓ VERIFIED —
[`cffi.rules`](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/lib-graalpython/patches/metadata.toml#L38-L46),
[wheelbuilder scripts](https://github.com/oracle/graalpython/tree/196ff1a0e59d668babb49435e6fdfc5b2d388817/scripts/wheelbuilder/linux),
[PyNaCl `requires_dist`](https://pypi.org/pypi/pynacl/1.6.2/json),
[cryptography `requires_dist`](https://pypi.org/pypi/cryptography/50.0.0/json),
[paramiko `requires_dist`](https://pypi.org/pypi/paramiko/5.0.0/json);
✗ UNCERTAIN which signal wins.)

**pyo3 is broadly fine, and GraalPy says so.** The `rpds_py` patch entry carries
the note "GraalPy patch is not needed anymore in recent version since PyO3 now
supports GraalPy", and the `bcrypt` entry says "With pyo3 changes upstreamed,
this patch is not needed in recent versions." Both patches are marked
`install-priority = 0`. This is the strongest positive finding in this section:
the Rust/pyo3 layer — `pydantic_core`, `cryptography`, `orjson`, `rpds-py`,
`bcrypt` — is not the expected blocker. It does mean the build needs a Rust
toolchain. (✓ VERIFIED —
[`rpds_py.rules`](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/lib-graalpython/patches/metadata.toml#L869-L875),
[`bcrypt.rules`](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/lib-graalpython/patches/metadata.toml#L10-L15))

**GraalPy's prebuilt wheel repository covers 13 packages.** The extra index
GraalPy's `pip` is preconfigured with lists `contourpy`, `httptools`, `jiter`,
`kiwisolver`, `numpy`, `oracledb`, `polyleven`, `psutil`, `pydantic-core`,
`ujson`, `uvloop`, `watchfiles`, `xxhash`. Wheel filenames carry a GraalPy ABI
tag, e.g. `numpy-2.2.4-graalpy312-graalpy250_312_native-manylinux_2_27_x86_64…`.
The newest tag present anywhere in the index is `graalpy250_312` — **there are
no prebuilt wheels for GraalPy 25.1 or 25.2**, and `pydantic-core` has wheels
only at 2.10.1 for `graalpy241`/`graalpy242`. (✓ VERIFIED —
[GraalPy wheel repository](https://www.graalvm.org/python/wheels/), index read
2026-08-08.)

| Wheel available | Versions | GraalPy tags |
|---|---|---|
| `numpy` | 1.26.4, 2.2.4 | 241/242 (py311), 250 (py312) |
| `psutil` | 5.9.8 | 241/242 (py311), 250 (py312) |
| `httptools` | 0.6.1 | 241/242 (py311), 250 (py312) |
| `uvloop` | 0.19.0 | 241/242 (py311), 250 (py312) |
| `watchfiles` | 0.21.0 | 241/242 (py311), 250 (py312) |
| `pydantic-core` | 2.10.1 | 241/242 (py311) only |

The practical reading: on GraalPy 25.x, **essentially every native dependency of
SkyPilot compiles from source at build time**, because the wheel repository
either does not carry the package or does not carry a wheel for that GraalPy
version. The build therefore needs a C/C++ toolchain, a Rust toolchain, Cython,
and network access to PyPI.

**Platform tiers matter for the deployment image.** GraalPy's Tier 1 (C
extensions fully tested) is `amd64-linux-glibc` and `aarch64-linux-glibc` on a
current GraalVM. `amd64-linux-musl-graal-latest` is **Tier 4** — "Only basic
pure Python functionality is tested; platform-specific features and extensions
are not prioritized." An Alpine-based backend image is outside tested territory
for native extensions. (✓ VERIFIED —
[Test-Tiers.md](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Test-Tiers.md))

### 3. Embedding under JVM mode

**Python version.** GraalPy 25.x implements Python 3.12.8. This is a published
table, and it matches `PythonLanguage.MAJOR/MINOR/MICRO` in the 25.2 source.
SkyPilot 0.13.0 classifies as supporting 3.9–3.13, so 3.12 is in range, though
SkyPilot's own client guidance recommends 3.9 or 3.10. (✓ VERIFIED —
[Version-Compatibility.md](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Version-Compatibility.md),
[PythonLanguage.java#L176-L178](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/com.oracle.graal.python/src/com/oracle/graal/python/PythonLanguage.java#L176-L178),
[SkyPilot classifiers](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/setup_files/setup.py#L176-L186))

**Maven artifacts.** Embedding on the JVM needs `org.graalvm.polyglot:polyglot`
plus a language artifact — `org.graalvm.polyglot:python` (Oracle GraalVM) or
`org.graalvm.polyglot:python-community` — and, to use the plugin-managed
virtual environment, `org.graalvm.python:python-embedding`. Package installation
is driven by `org.graalvm.python:graalpy-maven-plugin`. Published versions of
all four on Maven Central at the time of writing run `24.2.2, 25.0.0, 25.0.1,
25.0.2, 25.0.3, 25.0.4, 25.1.3, 25.2.4`; the latest is **25.2.4**. Note the gap
against §2: the newest GraalPy with published compatibility data or prebuilt
wheels is **25.0**. (✓ VERIFIED —
[maven-metadata for org.graalvm.polyglot:python](https://repo1.maven.org/maven2/org/graalvm/polyglot/python/maven-metadata.xml),
[for graalpy-maven-plugin](https://repo1.maven.org/maven2/org/graalvm/python/graalpy-maven-plugin/maven-metadata.xml),
[Embedding-Getting-Started.md](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Embedding-Getting-Started.md),
[Troubleshooting.md — missing language dependencies](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Troubleshooting.md#L187-L203))

**Artifact weight, measured from Maven Central at 25.2.4:**

| Artifact | Size |
|---|---|
| `org.graalvm.python:python-language` | 88.7 MiB |
| `org.graalvm.truffle:truffle-api` | 16.2 MiB |
| `org.graalvm.python:python-resources` | 13.8 MiB |
| `org.graalvm.truffle:truffle-runtime` | 0.9 MiB |
| `org.graalvm.polyglot:polyglot` | 0.5 MiB |
| `org.graalvm.python:python-embedding` | 0.05 MiB |
| **Runtime subtotal** | **≈120 MiB** |

On top of that sits the Python virtual environment. The CPython reference
install of `skypilot==0.13.0` is **329 MB** of `site-packages`; a GraalPy venv
will be of the same order. ADR 0009's "its image is larger" is therefore roughly
half a gigabyte of added deployable. (✓ VERIFIED — HTTP `Content-Length` from
`repo1.maven.org`, 2026-08-08; `du -sh` on the reference venv.)

**How third-party packages get in.** The Maven plugin creates and fully manages
a virtual environment under `${root}/venv`, either embedded in the JAR as Java
resources behind GraalPy's Virtual Filesystem (default resource path
`org.graalvm.python.vfs`, mounted at `/graalpy_vfs`) or written to an
`externalDirectory` outside the JAR. Java code then builds the Context through
`GraalPyResources.createContext()` / `contextBuilder(...)`, which preconfigures
it to run as if from that venv. "Plugin completely manages `venv/` — any manual
changes will be overridden during builds." (✓ VERIFIED —
[Embedding-Build-Tools.md](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Embedding-Build-Tools.md))

**Native components inside the Virtual Filesystem.** Files of type `.so`,
`.dylib`, `.pyd`, `.dll` (and `.ttf`) are automatically extracted to a temporary
directory on the real filesystem on first access, because the OS loader cannot
read them out of a JAR. (✓ VERIFIED — Embedding-Build-Tools.md, "Extracting
files from Virtual Filesystem")

**The JAR becomes platform-specific.** "Python packages may have native
components that are specific to the build system." GraalPy's documented remedy
for multi-platform distribution is to build on each target platform and
hand-merge the JARs, concatenating `vfs/fileslist.txt`. GraalPy's troubleshooting
guide names the failure mode with a SkyPilot-relevant example:
`ImportError: cannot import name 'exceptions' from
'cryptography.hazmat.bindings._rust' (unknown location)` → "Rebuild your project
on the target operating system before running it." For a Linux-container
deployment this is a non-issue provided the build runs on the deployment
platform. (✓ VERIFIED —
[README.md — cross-platform JARs](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/README.md),
[Troubleshooting.md#L116-L131](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Troubleshooting.md#L116-L131))

**Two Context options are load-bearing and both weaken the sandbox.**

- `allowNativeAccess(true)` is mandatory for native extensions, and GraalPy
  states plainly what that costs: "Native code is entirely unrestricted and can
  circumvent any security protections Truffle or the JVM may provide… while
  Python code may be denied access to the host file system, thread or subprocess
  creation, and more, the native extension is under no such restriction."
- The **Java POSIX backend is the default for embedded contexts** and does not
  support real file descriptors, producing
  `NotImplementedError: 'PyObject_AsFileDescriptor' not supported when using
  'java' posix backend` for packages that reach past Python's I/O. GraalPy's
  documented fix is `python.PosixModuleBackend = "native"` combined with
  extracting resources to an external directory. The native backend in turn does
  not support `os.fork`, and `_posixsubprocess.fork_exec` does not support
  `preexec_fn`. SkyPilot's client import path pulls in `multiprocessing` and
  `sky.utils.subprocess_utils`.

(✓ VERIFIED —
[Embedding-Native-Extensions.md](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Embedding-Native-Extensions.md),
[Embedding-Permissions.md](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Embedding-Permissions.md),
[Troubleshooting.md#L64-L111](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Troubleshooting.md#L64-L111);
✗ UNCERTAIN whether SkyPilot's *client* code paths actually require the native
backend — that is an empirical question for the prototype.)

**Memory behaviour under C extensions.** GraalPy reconciles CPython reference
counting with the JVM GC by keeping crossing objects strongly referenced and
running a periodic cycle detector. "Both of these mechanisms together mean there
is additional delay between objects becoming unreachable and their memory being
reclaimed… This can manifest in increased memory usage when running C
extensions." Tunable via `python.BackgroundGCTaskInterval`,
`python.BackgroundGCTaskThreshold`, `BackgroundGCTaskMinimum`. (✓ VERIFIED —
Embedding-Native-Extensions.md, "Memory Management")

**Runtime compilation depends on the JDK.** On stock OpenJDK 25 the polyglot
engine falls back to an interpreter-only runtime and warns: "The polyglot engine
uses a fallback runtime that does not support runtime compilation to native
code. Execution without runtime compilation will negatively impact the guest
application performance." Oracle GraalVM 25 is "Supported with additional
compiler optimizations. No extra configuration is required." GraalPy's Tier 1
platforms are all `graal-latest`; JDK 21 rows are Tier 3 and "tested without JIT
compilation." (✓ VERIFIED —
[Embedding Reference Manual — Runtime Optimization Support](https://www.graalvm.org/latest/reference-manual/embed-languages/#runtime-optimization-support),
Test-Tiers.md)

### 4. Threading and context semantics

**Polyglot rule.** "It is safe to use a context instance from a single thread.
It is also safe to use it with multiple threads if they do not access the
context at the same time… If initialized languages support multi-threading, then
the context instance may be used from multiple threads at the same time. If a
context is used from multiple threads and the language does not fit, then an
`IllegalStateException` is thrown by the accessing method." A context may be
closed from any thread if it is not executing code; otherwise
`close(boolean cancelIfExecuting)`. `interrupt(Duration)` must not be called
from a thread that has the context entered. (✓ VERIFIED —
[`org.graalvm.polyglot.Context` javadoc](https://www.graalvm.org/sdk/javadoc/org/graalvm/polyglot/Context.html))

**GraalPy permits multi-threaded access.** `PythonLanguage.isThreadAccessAllowed`
returns `true` unconditionally in the non-single-threaded case, and
`initializeMultiThreading` then invalidates the single-threaded assumption. So
concurrent entry from several Java threads does not throw. (✓ VERIFIED —
[PythonLanguage.java#L980-L994](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/com.oracle.graal.python/src/com/oracle/graal/python/PythonLanguage.java#L980-L994))

**It permits it behind a GIL, and the GIL is per Context.** "We always run with
a GIL, because C extensions in CPython expect to do so and are usually not
written to be reentrant… commonly at any given time there is only one Graal
Python thread executing and all the other threads are waiting to acquire the
GIL." The lock itself is an instance field
`private final GlobalInterpreterLock globalInterpreterLock` on `PythonContext`,
so two Contexts have two independent GILs and can execute Python in genuine
parallel. (✓ VERIFIED —
[IMPLEMENTATION_DETAILS.md — The GIL / Threading](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/contributor/IMPLEMENTATION_DETAILS.md#L94-L166),
[PythonContext.java#L789-L802](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/com.oracle.graal.python/src/com/oracle/graal/python/runtime/PythonContext.java#L789-L802))

**A blocking read releases the GIL.** `SocketUtils.callSocketFunctionWithRetry`
performs `gil.release(true)` around the blocking call and re-acquires afterwards;
`select` does the same, and `SSLOperationNode` routes its reads and writes
through the same helper. A followed log stream sitting in a socket read therefore
does **not** hold the GIL against other Python threads in the same Context. (✓
VERIFIED —
[SocketUtils.java#L83-L130](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/com.oracle.graal.python/src/com/oracle/graal/python/builtins/objects/socket/SocketUtils.java#L83-L130),
[SelectModuleBuiltins.java](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/com.oracle.graal.python/src/com/oracle/graal/python/builtins/modules/SelectModuleBuiltins.java),
[SSLOperationNode.java](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/com.oracle.graal.python/src/com/oracle/graal/python/builtins/objects/ssl/SSLOperationNode.java))

**But preemption stops at the C boundary.** "A timer is running the interrupts
threads periodically to relinquish the GIL and give other threads a chance to
run. This preemption is prohibited in most C extension code, however, since the
assumption in C extensions written for CPython is that the GIL will not be
relinquished while executing the C code." So GIL fairness holds for Python-level
and I/O-level work but not while a C extension is on the stack. (✓ VERIFIED —
IMPLEMENTATION_DETAILS.md, "Threading")

**Two Contexts with C extensions is experimental and Linux-only.** "Using C
extensions in multiple contexts is only possible on Linux for now, and many C
extensions still have issues in this mode. You should test your applications
thoroughly if you want to use this feature. There are many possibilities for
native code to sidestep the library isolation through other process-wide global
state, corrupting the state and leading to incorrect results or crashing… all
GraalPy contexts in the same process, not just those in the same engine, must
set the `python.IsolateNativeModules` option to `true`." The option is declared
`OptionStability.EXPERIMENTAL`. This is a direct constraint on ADR 0009's
"long-held work gets a context of its own." (✓ VERIFIED —
[Embedding-Native-Extensions.md — Multi-Context and Native Libraries](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Embedding-Native-Extensions.md#L40-L52),
[PythonOptions.java#L340-L343](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/com.oracle.graal.python/src/com/oracle/graal/python/runtime/PythonOptions.java#L340-L343))

**Virtual threads are ruled out.** "Python native extensions are not compatible
with Java virtual threads. Native extensions commonly use native thread-local
state, including CPython and extension-runtime state that cannot track Java
virtual-thread scheduling. If an embedded application may load or call native
extensions, run that Python code on platform threads. For example, server
applications that use virtual threads for request handling should dispatch
GraalPy calls that may use native extensions to a platform-thread executor."
ADR 0009's "dedicated single-threaded executor" satisfies this only if it is
explicitly a *platform*-thread executor. (✓ VERIFIED —
[Embedding-Native-Extensions.md — Java Virtual Threads](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Embedding-Native-Extensions.md#L16-L21))

**Signal handlers are off by default when embedding.** GraalPy 25.1 added
`python.AllowSignalHandlers`, "disabled by default for Java embedding and
enabled in the standalone." SkyPilot's client import path does not call
`signal.signal` — the calls live in `sky/server/`, `sky/backends/`,
`sky/serve/server/`, `sky/batch/` and `sky/utils/command_runner.py`. (✓ VERIFIED
— [CHANGELOG 25.1.0](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/CHANGELOG.md);
grep over the 0.13.0 tree.)

**Reading for ADR 0009:** one long-lived Context served by a dedicated
single-platform-thread executor is well within what GraalPy allows, and cheap.
The part of the model that is *not* on documented ground is the second Context
for long-held work, because it lands on an experimental, Linux-only,
explicitly-caveated feature. A cheaper arrangement that stays inside one Context
— a second platform thread entering the same Context, which the per-Context GIL
serializes but releases across blocking reads — is documented as supported and
avoids `IsolateNativeModules` entirely. Whether the log stream's other work is
Python-level (releases the GIL freely) or C-extension-level (does not) is an
empirical question.

### 5. Version alignment

**The client version can be pinned exactly.** The Maven plugin's `packages`
element takes pip syntax, so `<package>skypilot==0.13.0</package>` is directly
expressible; `requirementsFile` forwards a pip-compatible `requirements.txt`
straight to `pip install -r`. (✓ VERIFIED — Embedding-Build-Tools.md, "Maven
Plugin Configuration" and "Using `requirements.txt`")

**The transitive tree can be pinned too, by one of two mechanisms, and they are
mutually exclusive.**

- `mvn org.graalvm.python:graalpy-maven-plugin:lock-packages` generates
  `graalpy.lock`, capturing "exact versions of all dependencies (those specified
  explicitly in the pom.xml… and all their transitive dependencies)." Once
  committed, builds install exactly those versions, and a change to `packages`
  that no longer matches the lock **fails the build** until the lock is
  regenerated.
- If `requirementsFile` is used instead, "the GraalPy lock file is **not created
  and not used**, the `lock-packages` goal is **disabled**, dependency locking
  must be handled externally by pip (for example using `pip freeze`)."

(✓ VERIFIED — Embedding-Build-Tools.md, "Dependency Management", "Locking
Python Packages", and the `requirementsFile` note.)

**GraalPy's `pip` does reorder version preference, but only among candidates
that already satisfy the requirement.** `metadata.toml` carries an
`install-priority` per patch rule: "When ordering all available versions in the
index, each version gets a priority of the first entry it matches in this file.
If it doesn't match, it gets priority 0. Versions with higher priority are then
preferred for installation. This means that by default, versions with patches
are preferred." GraalPy's own guidance is "We recommend specifying dependencies
without version numbers… GraalPy automatically installs compatible versions for
well-known packages." An explicit `==` pin or a lock file overrides that
preference; it does not override a hard requirement. (✓ VERIFIED —
[patches/README.md](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/graalpython/lib-graalpython/patches/README.md),
Embedding-Build-Tools.md "Dependency Management")

**So ADR 0009's requirement is satisfiable — with a tension.** Pinning
`skypilot==0.13.0` to match the API server is mechanically easy. But it also
pins the transitive tree to the versions SkyPilot's ranges resolve to *today*,
which §2 shows are newer than every version GraalPy has tested, patched, or
built a wheel for. Steering the tree back toward GraalPy-known versions means
adding constraints SkyPilot did not ask for, and at least one such steer is
blocked outright:

> `pydantic` pins `pydantic-core` with `==`. The only prebuilt `pydantic-core`
> wheel GraalPy publishes is 2.10.1, which is `pydantic==2.4.2`. SkyPilot's
> requirement is `pydantic!=2.0.*,!=2.1.*,!=2.2.*,!=2.3.*,!=2.4.*,<3,>2`, which
> **excludes 2.4.\* outright**. The GraalPy-prebuilt `pydantic-core` is
> unreachable from a valid SkyPilot resolution; `pydantic-core` must be built
> from source. (✓ VERIFIED — SkyPilot's constraint at
> [dependencies.py#L71](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/setup_files/dependencies.py#L71);
> `pydantic 2.4.2 → pydantic-core==2.10.1` and `pydantic 2.13.4 →
> pydantic-core==2.46.4` read from
> [PyPI JSON metadata](https://pypi.org/pypi/pydantic/2.4.2/json).)

The mitigating fact from §2 is GraalPy's own note that recent pyo3 no longer
needs a patch, so building `pydantic-core` from source is the expected path
rather than a fallback.

**One more version axis: the GraalPy artifact itself.** GraalPy Maven artifacts
and the GraalVM JDK must be version-aligned or the build reports
`Your Java runtime … with compiler version … is incompatible with polyglot
version …`. That is a third pinned version alongside SkyPilot's, and it moves on
Oracle's schedule, not SkyPilot's. (✓ VERIFIED —
[Troubleshooting.md#L134-L151](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/docs/user/Troubleshooting.md#L134-L151))

## Could not establish

- **Whether `pip install skypilot==0.13.0` succeeds under GraalPy at all.** No
  source, official or otherwise, records anyone installing SkyPilot on GraalPy.
  `skypilot` does not appear in GraalPy's compatibility dataset. This is the
  question the prototype exists to answer, and the three status-`2` packages in
  §2 make it a genuine coin-flip rather than a formality.
- **Whether `cffi` builds on GraalPy 25.x.** The compatibility dataset says no
  for 1.17.1; the patch rules and the `cffi.sh` wheel-build script imply yes.
  Not resolvable from documentation.
- **The status of `ijson`, `propcache` and `rpds-py` on GraalPy.** They are
  absent from the published dataset. `rpds-py` at least has a historical patch
  entry with a note that pyo3 support made it unnecessary; `ijson` and
  `propcache` have no GraalPy mention at all. `ijson` ships pure-Python fallback
  backends, `propcache` and `SQLAlchemy`'s `cyextension` are documented by their
  own projects as optional accelerators, but no GraalPy source confirms the
  fallback path is exercised.
- **Any GraalPy compatibility data or prebuilt wheel for 25.1 or 25.2.** The
  published CSVs stop at `v250` and the wheel index's newest ABI tag is
  `graalpy250_312`, while Maven Central's newest artifact is 25.2.4. Using the
  latest GraalPy means using a release for which no package-compatibility data
  has been published.
- **Whether SkyPilot's client code paths require `python.PosixModuleBackend =
  "native"`.** GraalPy documents the failure mode and the fix; nothing
  establishes which of the two backends SkyPilot's client needs, and the answer
  determines whether the venv can stay inside the JAR.
- **Whether SkyPilot's client tolerates `os.fork` being unavailable.**
  `multiprocessing` and `sky.utils.subprocess_utils` are on the import path, but
  importing is not calling. Not established either way.
- **Startup cost of the import under GraalPy.** ADR 0009 says the build "pays
  SkyPilot's import cost once." 1811 modules is a large import under CPython;
  under an interpreter-only or warming JIT it will be materially larger, and no
  source quantifies it.
- **Whether two GraalPy Contexts with `IsolateNativeModules=true` actually work
  for *this* dependency set.** GraalPy's documentation explicitly declines to
  promise it: "many C extensions still have issues in this mode."
- **Whether a GraalPy build of this tree is reproducible over time.** Locking
  fixes versions, but every native package is compiled from an sdist against
  GraalPy's patch set, and since 24.2 GraalPy's `pip` "is now able to fetch
  newer versions of GraalPy patches for third-party packages from `graalpython`
  GitHub repository" at install time, overridable via `PIP_GRAALPY_PATCHES_URL`
  ([CHANGELOG 24.2.0](https://github.com/oracle/graalpython/blob/196ff1a0e59d668babb49435e6fdfc5b2d388817/CHANGELOG.md)).
  The lock does not pin the patch revision.

## Source versions

- **SkyPilot v0.13.0**, commit
  `b1431e52d97c22e9bb8fa8b67f162543754ddaf5`
  ([release](https://github.com/skypilot-org/skypilot/releases/tag/v0.13.0)).
  Dependency resolution and import graph measured against
  `pip install skypilot==0.13.0` (no extras) into CPython 3.12.11 on
  x86-64 Linux/glibc, 2026-08-08.
- **GraalPy** `release/graal-vm/25.2`, commit
  `196ff1a0e59d668babb49435e6fdfc5b2d388817`
  ([oracle/graalpython](https://github.com/oracle/graalpython)). Cross-checked
  against `release/graal-vm/25.0`, commit
  `ee284159ae112c676b116d394b5f7f529dc9666d`, where 25.2 reorganised the docs.
- **GraalPy compatibility dataset** `v250` and the GraalPy wheel repository,
  both read 2026-08-08.
- **Maven Central** metadata and artifact sizes for `org.graalvm.polyglot:*`
  and `org.graalvm.python:*`, read 2026-08-08. Latest published version 25.2.4.
- **GraalVM Polyglot SDK javadoc** and the GraalVM Embedding Reference Manual,
  queried 2026-08-08.
