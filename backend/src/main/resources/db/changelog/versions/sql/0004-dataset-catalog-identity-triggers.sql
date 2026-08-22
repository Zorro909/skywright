CREATE FUNCTION retain_dataset_copy_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO dataset_copy_identity(definition_id, copy_id)
  VALUES (NEW.definition_id, NEW.copy_id) ON CONFLICT DO NOTHING;
  RETURN NEW;
END $$;

CREATE TRIGGER retain_dataset_copy_identity_before_insert
BEFORE INSERT ON dataset_copy FOR EACH ROW EXECUTE FUNCTION retain_dataset_copy_identity();

CREATE FUNCTION retain_dataset_generation_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO dataset_copy_identity(definition_id, copy_id)
  VALUES (NEW.definition_id, NEW.copy_id) ON CONFLICT DO NOTHING;
  INSERT INTO dataset_generation_identity(definition_id, copy_id, generation_number)
  VALUES (NEW.definition_id, NEW.copy_id, NEW.generation_number) ON CONFLICT DO NOTHING;
  RETURN NEW;
END $$;

CREATE TRIGGER retain_dataset_generation_identity_before_insert
BEFORE INSERT ON dataset_copy_generation FOR EACH ROW EXECUTE FUNCTION retain_dataset_generation_identity();
