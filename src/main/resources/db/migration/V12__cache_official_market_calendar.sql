CREATE TABLE quote_market_calendar (
    market VARCHAR(4) NOT NULL,
    calendar_date DATE NOT NULL,
    is_open BOOLEAN NOT NULL,
    reason VARCHAR(1000),
    source VARCHAR(500) NOT NULL,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (market, calendar_date)
);
