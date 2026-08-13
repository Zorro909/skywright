---
status: accepted
---

# Version the Python SDK independently

The Python SDK has its own Semantic Version because Training Project Images pin the runtime library independently of the replaceable backend and other repository artifacts. Repository release automation may coordinate their builds and releases, but it must not collapse their version identities; before the SDK reaches 1.0, breaking public-API changes require a minor release while patch releases remain compatible.
