CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE skywright.currency_conversion
ADD CONSTRAINT currency_conversion_rate_check
CHECK (rate > 0),
ADD CONSTRAINT currency_conversion_effective_until_check
CHECK (effective_from < effective_until),
ADD CONSTRAINT ex_currency_conversion_effective_interval
EXCLUDE USING gist (
    price_source_id WITH =,
    native_currency WITH =,
    reporting_currency WITH =,
    tstzrange(effective_from, effective_until, '[]') WITH &&
);
