package tw.holdings.holding;

import java.util.List;

import tw.holdings.profit.PortfolioSummary;

record HoldingPortfolio(List<HoldingQuoteView> holdings, PortfolioSummary summary) {
}
