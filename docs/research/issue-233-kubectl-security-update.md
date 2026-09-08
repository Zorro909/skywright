# Issue 233: kubectl image dependency qualification

Investigated 2026-09-07 against official release artifacts. This note records research; it does not change the SkyPilot SDK or deployment.

## Recommended binary

Use official **kubectl v1.37.0** for the Kubernetes 1.36 qualification environment. Kubernetes supports kubectl within one minor version of its API server. The official release index currently resolves `stable.txt` to v1.37.0 and `stable-1.36.txt` to v1.36.4. [Release](https://github.com/kubernetes/kubernetes/releases/tag/v1.37.0), [version policy](https://kubernetes.io/releases/version-skew-policy/#kubectl).

The failed CI scan reports 14 high findings in `/usr/local/bin/kubectl`: the required fixed versions are x/net 0.56.0, x/text 0.39.0 and Go 1.26.6. Downloaded Linux amd64 and arm64 binaries were hashed against their official `.sha256` files, then their embedded Go build-information records were parsed without executing them:

| kubectl | Embedded Go | x/net | x/text | Assessment against reported thresholds |
| --- | --- | --- | --- | --- |
| v1.36.4, both architectures | go1.26.5 | v0.56.0 | v0.39.0 | Go standard-library findings remain |
| v1.37.0, both architectures | go1.26.6 | v0.57.0 | v0.40.0 | All three version thresholds satisfied |

The module versions agree with the corresponding [v1.36.4 go.mod](https://github.com/kubernetes/kubernetes/blob/v1.36.4/go.mod) and [v1.37.0 go.mod](https://github.com/kubernetes/kubernetes/blob/v1.37.0/go.mod). A newer module manifest alone is insufficient: v1.36.4's actual binaries still carry the older Go toolchain.

Verified v1.37.0 pins:

| Architecture | SHA256 |
| --- | --- |
| amd64 | `6129359f4e1f3848a5572ccb0b26cf28b8ca08cef38c95a765b2f64a2c961a2f` |
| arm64 | `922df28df248cc00a9e025f947704f1d1482de64ece54cfe57e61f19eaf1eef3` |

Download pattern: `https://dl.k8s.io/release/v1.37.0/bin/linux/${arch}/kubectl`. Official checksums: [amd64](https://dl.k8s.io/release/v1.37.0/bin/linux/amd64/kubectl.sha256), [arm64](https://dl.k8s.io/release/v1.37.0/bin/linux/arm64/kubectl.sha256). Follow the existing pinned download and SHA256 verification in Skywright's Dockerfile. This research establishes compatibility and the reported dependency thresholds; the rebuilt production image still needs its normal scanner and executable tests.

## Correction to the earlier executable minimum

The earlier research omitted **socat and nc**, which stock SkyPilot's Kubernetes credential preflight requires. `Kubernetes._check_compute_credentials()` calls `check_port_forward_mode_dependencies(False)` before its Kubernetes API check. That helper runs `socat -V` and `nc -h`, rejects missing binaries, and returns failure reasons. Its port-forward transport uses socat to connect the local forwarded port to SSH. Skywright's addition of `socat` and `netcat-openbsd` therefore addresses a real stock preflight requirement. [Credential preflight](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/clouds/kubernetes.py#L1280), [dependency check](https://github.com/skypilot-org/skypilot/blob/b1431e52d97c22e9bb8fa8b67f162543754ddaf5/sky/provision/kubernetes/utils.py#L3561).
