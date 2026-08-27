CREATE OR REPLACE FUNCTION skywright.prevent_gpu_price_schedule_overlap()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended(NEW.price_source_id::text || ':' || NEW.source_revision::text || ':' || NEW.eligible_gpu_offering_id::text, 0)
    );

    IF EXISTS (
        SELECT 1
        FROM skywright.gpu_price_schedule_entry existing
        WHERE existing.price_source_id = NEW.price_source_id
          AND existing.source_revision = NEW.source_revision
          AND existing.eligible_gpu_offering_id = NEW.eligible_gpu_offering_id
          AND existing.id <> NEW.id
          AND existing.effective_from < NEW.effective_until
          AND NEW.effective_from < existing.effective_until
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23P01',
            CONSTRAINT = 'ex_gpu_price_schedule_no_overlap',
            MESSAGE = 'GPU price schedule entries overlap';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_gpu_price_schedule_no_overlap
BEFORE INSERT OR UPDATE ON skywright.gpu_price_schedule_entry
FOR EACH ROW EXECUTE FUNCTION skywright.prevent_gpu_price_schedule_overlap();
