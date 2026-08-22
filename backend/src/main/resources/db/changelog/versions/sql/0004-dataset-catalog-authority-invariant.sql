ALTER TABLE dataset_copy
  ADD CONSTRAINT ck_dataset_copy_role CHECK (role IN ('AUTHORITY', 'REPLICA'));

CREATE FUNCTION require_one_dataset_authority() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  affected_definition uuid := COALESCE(NEW.definition_id, OLD.definition_id);
BEGIN
  IF EXISTS (SELECT 1 FROM dataset_catalog WHERE definition_id = affected_definition)
      AND (SELECT count(*) FROM dataset_copy
           WHERE definition_id = affected_definition AND role = 'AUTHORITY') <> 1 THEN
    RAISE EXCEPTION 'Dataset Catalog must have exactly one authority';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER require_one_dataset_authority_after_change
AFTER INSERT OR UPDATE OR DELETE ON dataset_copy
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_one_dataset_authority();

CREATE CONSTRAINT TRIGGER require_one_dataset_authority_for_catalog
AFTER INSERT OR UPDATE ON dataset_catalog
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_one_dataset_authority();
