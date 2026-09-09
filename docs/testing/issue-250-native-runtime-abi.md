# Production native dependency qualification

Issue [#250](https://github.com/Zorro909/skywright/issues/250) qualifies the
Ubuntu production image independently of the developer host's native libraries.
The runtime remains GraalPy 25.3.4.1, SkyPilot 0.13.0 and GraalVM CE 25.3.4.1
with OpenJDK 25.0.4.1. The production Dockerfile and its system packages are
unchanged by this issue.

## Reproduced failure

On 2026-09-09, the normal image assembly at base commit `340e510` copied a
sealed Fedora 44 environment into the pinned Ubuntu 24.04 image. A disposable
Java import probe used that image's application JAR, GraalPy resources and JDK.
It failed with exit code 1:

```text
ImportError: cannot load .../cryptography/hazmat/bindings/_rust.graalpy253-313-native-x86_64-linux.so:
/lib/x86_64-linux-gnu/libssl.so.3: version `OPENSSL_3.2.0' not found
```

The Fedora environment recorded glibc 2.43 and OpenSSL 3.5.7. Its native
cryptography extension requires OpenSSL symbol versions 3.0.0, 3.2.0 and 3.3.0.
The probe extracted Java libraries from the image's own executable JAR into
its temporary directory; it supplied no host system libraries. It halted after
reporting the import failure and does not establish context shutdown behavior.

The reverse substitution also fails: importing Ubuntu CI resources on Fedora
reports an undefined `EVP_sm4_cfb128` symbol at `OPENSSL_3.0.0`. This is why
`graalpy.production.directory` selects only image resources while local backend
builds and tests keep their host-compatible `graalpy.external.directory`.

## Selected environment

The unmodified `graalpy-resources` artifact from successful Repository Quality
[run 34369470829](https://github.com/Zorro909/skywright/actions/runs/34369470829)
records Ubuntu 24.04 amd64, glibc 2.39 and OpenSSL 3.0.13. Its native inputs
match this checkout. Application and packaging-policy edits do not rebuild its
native dependencies.

| Environment | Identity SHA-256 | Payload SHA-256 |
| --- | --- | --- |
| Fedora | `b779888495db9752a2d7fdff127c94f058ea3ee3e0e1b86cf122fd4d665f0053` | `18da66c8a2293d3b469e3e9c2fb42c9948404147d616829e12722b004edce37a` |
| Ubuntu CI | `de563513b9315736ac29629108c45db9209e857caf6f3d62531359725ef82487` | `dff39c94293f8ec14ceb572d45367a7f334fce23426ed0cd4b6d5f1d3e995680` |

The new packaging gate accepts the Ubuntu record on Fedora and rejects the
Fedora record before assembly. It checks the complete sealed payload and
current effective native inputs, then enforces the supported build ABI.
The gate is repeated during every `prepare-package`, even for prebuilt inputs.

## Production regression

`ProductionImageIT.shippedNativeSdkInitiatesAndCompletesARealStatusRequest`
starts the actual production image with its default non-root user, read-only
root filesystem and production 64 MiB executable temporary mount. Its shipped
bridge imports SkyPilot, initiates `sky.jobs.queue_v2`, and completes the
request through `sky.stream_and_get` against an isolated stock server installed
from the repository lock. The server has a fresh home directory and no job
controller. Only its typed `ClusterNotUpError` is accepted. Neither an import
error nor a bridge reachability failure satisfies the test.

The qualification process must exit with code 0 within 120 seconds; the test
removes it on failure. No GPU or cloud work is launched. The server and client
SDK sources are unchanged. TLS policy remains the HTTPS behavior qualified by
[#248](issue-248-graalpy-https.md).

Run from a Fedora packaging host after selecting the CI artifact:

```bash
mvn -B -ntp -pl backend-deployment -am verify \
  -Dgraalpy.production.directory=/absolute/path/to/ubuntu/resources \
  -Dit.test=ProductionImageIT -Dfailsafe.failIfNoSpecifiedTests=false
python3 -m unittest discover -s tests/quality -v
```

Local verification results will be recorded after the complete image run.
The [deployment README](../../backend-deployment/README.md) records supported
hosts, selection commands and cache invalidation rules.
