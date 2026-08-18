---
status: accepted
---

# Use PostgreSQL with JPA and Liquibase

Skywright persists control-plane metadata in PostgreSQL 18 through Spring Data JPA and Hibernate. JPQL and the Criteria API may express queries that repository derivation cannot, but application persistence code never uses raw JDBC, native queries, or handwritten SQL; this keeps one persistence boundary and lets explicit mappings insulate the domain and HTTP models from storage. The narrow Liquibase schema-compatibility adapter may open a short-lived migration-role JDBC connection solely through Liquibase's API to validate its changelog history; it neither executes application queries nor exposes that connection outside migration infrastructure.

The database provisioner creates the empty database and its `skywright` schema namespace because Liquibase must have that namespace before it can create its own metadata tables there. Liquibase Community is the sole authority for every object inside that namespace, and Hibernate validates rather than creates or alters those objects. Changelogs use declarative change types whenever Liquibase can express the required PostgreSQL object. A reviewed SQL file is permitted only for an essential feature unavailable declaratively, and every such migration includes and tests an explicit SQL rollback before it is accepted.

All application objects and Liquibase metadata share one `skywright` schema, while tables remain provenance-partitioned as required by ADR 0005. PostgreSQL 18 support is established by running migrations, rollback compatibility, and persistence integration tests against the pinned real engine even when Liquibase has not yet listed that major as verified.
