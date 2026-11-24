CREATE TABLE quote_market_refresh (
    market VARCHAR(4) NOT NULL,
    target_date DATE NOT NULL,
    window_start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    first_response_at TIMESTAMP WITH TIME ZONE,
    recovery_started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    normal_attempt_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (market, target_date)
);
