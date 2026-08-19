REVOKE SELECT, INSERT, UPDATE, DELETE ON
    target_storage,
    target_storage_configuration,
    target_storage_binding,
    target_storage_assessment,
    target_storage_resource,
    target_storage_defaults
FROM "${runtimeRole}";
