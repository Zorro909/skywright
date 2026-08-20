CREATE UNIQUE INDEX training_project_display_name_key
  ON skywright.training_project (lower(btrim(display_name)));

CREATE UNIQUE INDEX training_project_live_repository_key
  ON skywright.training_project_registry_binding (repository)
  WHERE state IN ('active', 'candidate');

CREATE UNIQUE INDEX training_project_one_active_binding_key
  ON skywright.training_project_registry_binding (training_project_id)
  WHERE state = 'active';

CREATE UNIQUE INDEX training_project_one_candidate_binding_key
  ON skywright.training_project_registry_binding (training_project_id)
  WHERE state = 'candidate';

CREATE UNIQUE INDEX registry_rebinding_one_active_key
  ON skywright.registry_rebinding_operation (training_project_id)
  WHERE state IN ('verifying', 'failed');
