ALTER TABLE skywright.training_project_registry_binding
  ADD CONSTRAINT ck_training_project_binding_access_mode CHECK (access_mode IN ('PUBLIC', 'PRIVATE')),
  ADD CONSTRAINT ck_training_project_binding_readiness CHECK (readiness IN ('READY', 'MISSING', 'INVALID', 'EXPIRED', 'UNAVAILABLE')),
  ADD CONSTRAINT ck_training_project_binding_state CHECK (state IN ('active', 'candidate', 'retired')),
  ADD CONSTRAINT ck_training_project_binding_credentials CHECK (
    (access_mode = 'PUBLIC' AND resolver_credential_binding_id IS NULL AND execution_credential_binding_id IS NULL)
    OR
    (access_mode = 'PRIVATE' AND (resolver_credential_binding_id IS NULL OR execution_credential_binding_id IS NULL OR resolver_credential_binding_id <> execution_credential_binding_id))
  ),
  ADD CONSTRAINT ck_training_project_binding_repository CHECK (
    repository ~ '^ghcr[.]io/[a-z0-9]+(([._]|__|-+)[a-z0-9]+)*/[a-z0-9]+(([._]|__|-+)[a-z0-9]+)*$'
  );

ALTER TABLE skywright.registry_rebinding_operation
  ADD CONSTRAINT ck_registry_rebinding_state CHECK (state IN ('verifying', 'failed', 'promoted', 'abandoned')),
  ADD CONSTRAINT ck_registry_rebinding_attempts CHECK (attempts >= 0),
  ADD CONSTRAINT ck_registry_rebinding_revisions CHECK (candidate_binding_revision > active_binding_revision);

ALTER TABLE skywright.registry_rebinding_artifact
  ADD CONSTRAINT ck_registry_rebinding_artifact_kind CHECK (kind IN ('VERSION_MANIFEST', 'IMAGE', 'CONTRACT')),
  ADD CONSTRAINT ck_registry_rebinding_artifact_digest CHECK (digest ~ '^sha256:[0-9a-f]{64}$');
