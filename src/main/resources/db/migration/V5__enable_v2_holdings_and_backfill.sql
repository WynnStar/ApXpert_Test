ALTER TABLE holdings ADD COLUMN v2_managed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE holdings ALTER COLUMN lots DROP NOT NULL;
ALTER TABLE holdings ALTER COLUMN buy_price DROP NOT NULL;

ALTER TABLE holding_trades ALTER COLUMN side SET DATA TYPE VARCHAR(7);
ALTER TABLE holding_trades DROP CONSTRAINT ck_holding_trades_side;
ALTER TABLE holding_trades ALTER COLUMN trade_date DROP NOT NULL;
ALTER TABLE holding_trades ADD CONSTRAINT ck_holding_trades_side CHECK (
    (side = 'OPENING' AND trade_date IS NULL)
    OR (side IN ('BUY', 'SELL') AND trade_date IS NOT NULL)
);

INSERT INTO holding_trades (holding_id, side, trade_date, shares, unit_price)
SELECT id, 'OPENING', NULL, CAST(lots AS BIGINT) * 1000, buy_price FROM holdings;

ALTER TABLE holdings ADD CONSTRAINT ck_holdings_version CHECK (
    (v2_managed = TRUE AND lots IS NULL AND buy_price IS NULL)
    OR (v2_managed = FALSE AND lots IS NOT NULL AND buy_price IS NOT NULL)
);
