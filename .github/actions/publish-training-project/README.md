# Publish Training Project Action

This reusable composite Action is the only supported Training Project Version publication
interface. A downstream Training Project supplies one committed `skywright-project.json` and OCI
push credentials; the Action validates its clean checkout, builds and smoke-tests every declared
backend image, publishes the exact contracts, and exposes the complete version last.

```yaml
- id: project-version
  uses: Zorro909/skywright/.github/actions/publish-training-project@<full-commit-sha>
  with:
    definition: skywright-project.json
    registry-username: ${{ github.actor }}
    registry-password: ${{ secrets.GITHUB_TOKEN }}
```

Callers must check out full commit history first and grant only the registry permission required by
their repository. Pin the Action to a full commit SHA. The outputs are `version-label`,
`manifest-digest`, and `artifact-digest`.

The Action creates an isolated Python environment containing the SDK contract compilers from its
own pinned Skywright revision. Each image build checks that the Skywright library supplied by its
Environment Profile exposes those exact configuration and metric schema identities before the
image can be published. Docker/OCI publication code remains private to this Action and is not
installed with the `skywright` runtime SDK.

On a dedicated ephemeral runner, set `cleanup-docker: 'true'` when the CUDA
and ROCm images cannot fit together. After each successful image push and
registry digest resolution, the action removes unused Docker build cache and
images before building the next backend. This deletes unrelated unused Docker
state too, so leave the default `false` on shared or retained Docker hosts.
Cleanup does not delete published images or skip either backend's smoke test.
