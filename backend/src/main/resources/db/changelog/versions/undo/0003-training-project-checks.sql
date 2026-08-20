ALTER TABLE skywright.registry_rebinding_artifact
  DROP CONSTRAINT ck_registry_rebinding_artifact_digest,
  DROP CONSTRAINT ck_registry_rebinding_artifact_kind;

ALTER TABLE skywright.registry_rebinding_operation
  DROP CONSTRAINT ck_registry_rebinding_revisions,
  DROP CONSTRAINT ck_registry_rebinding_attempts,
  DROP CONSTRAINT ck_registry_rebinding_state;

ALTER TABLE skywright.training_project_registry_binding
  DROP CONSTRAINT ck_training_project_binding_repository,
  DROP CONSTRAINT ck_training_project_binding_credentials,
  DROP CONSTRAINT ck_training_project_binding_state,
  DROP CONSTRAINT ck_training_project_binding_readiness,
  DROP CONSTRAINT ck_training_project_binding_access_mode;
