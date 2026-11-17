ALTER TABLE quote_cache ADD COLUMN last_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE quote_cache ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE quote_cache ADD COLUMN failure_count INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE quote_cache ADD COLUMN post_close_attempts INTEGER DEFAULT 0 NOT NULL;

CREATE INDEX idx_quote_cache_latest ON quote_cache (code, status, price_date, lookup_date);
