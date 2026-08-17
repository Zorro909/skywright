---
status: accepted
---

# Use PostgreSQL with JPA and Liquibase

Skywright persists control-plane metadata in PostgreSQL 18 through Spring Data JPA and Hibernate. JPQL and the Criteria API may express queries that repository derivation cannot, but application code never uses raw JDBC, native queries, or handwritten SQL; this keeps one persistence boundary and lets explicit mappings insulate the domain and HTTP models from storage.

Liquibase Community is the sole schema authority, and Hibernate validates rather than creates or alters the schema. Changelogs use declarative change types whenever Liquibase can express the required PostgreSQL object. A reviewed SQL file is permitted only for an essential feature unavailable declaratively, and every such migration includes and tests an explicit SQL rollback before it is accepted.

All application objects and Liquibase metadata share one `skywright` schema, while tables remain provenance-partitioned as required by ADR 0005. PostgreSQL 18 support is established by running migrations, rollback compatibility, and persistence integration tests against the pinned real engine even when Liquibase has not yet listed that major as verified.
