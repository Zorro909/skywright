DROP TRIGGER trg_gpu_price_schedule_no_overlap ON skywright.gpu_price_schedule_entry;
DROP FUNCTION skywright.prevent_gpu_price_schedule_overlap();
SET search_path TO skywright;
