CREATE TABLE market_event (
    code VARCHAR(6) NOT NULL,
    market VARCHAR(4) NOT NULL,
    effective_date DATE NOT NULL,
    kind VARCHAR(10) NOT NULL,
    stock_name VARCHAR(255),
    PRIMARY KEY (code, market, effective_date, kind)
);

CREATE TABLE market_identity (
    code VARCHAR(6) PRIMARY KEY,
    issuer_id VARCHAR(8) NOT NULL,
    listed_on DATE NOT NULL
);

CREATE TABLE market_final_close (
    code VARCHAR(6) NOT NULL,
    market VARCHAR(4) NOT NULL,
    effective_date DATE NOT NULL,
    next_month DATE NOT NULL,
    price_date DATE,
    close_price DECIMAL(38, 2),
    PRIMARY KEY (code, market, effective_date)
);

CREATE TABLE market_sync_day (
    sync_date DATE PRIMARY KEY
);
