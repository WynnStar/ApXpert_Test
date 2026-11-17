ALTER TABLE holding_trades ADD COLUMN sort_order BIGINT DEFAULT 0 NOT NULL;
UPDATE holding_trades SET sort_order = id;

DROP INDEX idx_holding_trades_sequence;
CREATE INDEX idx_holding_trades_sequence ON holding_trades (holding_id, trade_date, sort_order, id);
