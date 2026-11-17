ALTER TABLE holdings ADD COLUMN stock_name VARCHAR(255);
ALTER TABLE holdings ADD COLUMN market VARCHAR(4);

CREATE TABLE quote_cache (
    lookup_date DATE NOT NULL,
    code VARCHAR(6) NOT NULL,
    status VARCHAR(12) NOT NULL,
    stock_name VARCHAR(255),
    market VARCHAR(4),
    close_price DECIMAL(38, 2),
    price_date DATE,
    PRIMARY KEY (lookup_date, code)
);
