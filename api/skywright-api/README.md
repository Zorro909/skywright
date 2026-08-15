# Skywright product API

This module is the canonical Skywright product contract for operations under `/api/v1`. Maven
validates the OpenAPI document before packaging it as an immutable reactor input. Backend and
frontend builds regenerate disposable Java and TypeScript boundaries from that artifact; generated
sources live only under `target/` and remain uncommitted.

Verify the contract and all current application consumers from the repository root with:

```bash
scripts/quality run application
```
