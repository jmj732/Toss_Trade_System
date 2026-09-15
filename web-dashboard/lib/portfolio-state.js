function amountMap(currency, value) {
  return currency && value != null ? { [currency]: value } : {};
}

function buyingPowerMap(currency, value) {
  return currency && value != null ? { [currency]: { cashBuyingPower: value } } : {};
}

function list(value) {
  return Array.isArray(value) ? value : [];
}

function percentRatio(value) {
  const number = Number(value);
  return value == null || value === "" || !Number.isFinite(number) ? null : number / 100;
}

function statePosition(position, previous) {
  return {
    ...previous,
    symbol: position.symbol,
    quantity: position.quantity,
    currency: position.currency,
    averagePrice: position.avgPrice,
    lastPrice: position.currentPrice,
    marketValueAmount: position.marketValue,
    profitLossAmount: null,
    profitLossRate: percentRatio(position.unrealizedPnlPct),
    weight: percentRatio(position.weightPct),
    ...position
  };
}

/** Adapts the shared PortfolioState contract to existing dashboard section envelopes. */
export function portfolioStateSection(state, previous = {}) {
  const currency = state?.currency ?? null;
  const account = state?.account ?? {};
  const previousData = previous?.data ?? {};
  const previousPositions = new Map(list(previousData.positions).map(position => [position.symbol, position]));
  const positions = list(state?.positions).map(position =>
    statePosition(position, previousPositions.get(position.symbol)));
  const unknownFields = list(state?.unknownFields);
  return {
    ...previous,
    stale: state?.stale === true,
    unknown: unknownFields.length > 0,
    unknownFields,
    unavailable: false,
    unavailableReason: null,
    data: {
      ...previousData,
      asOf: state?.asOf ?? previousData.asOf,
      completedAt: state?.asOf ?? previousData.completedAt,
      staleReason: state?.staleReason ?? null,
      partial: state?.partial === true,
      missingSections: list(state?.missingSections),
      account: {
        ...previousData.account,
        marketValueAmounts: amountMap(currency, account.totalValue),
        dailyProfitLossAmounts: {},
        dailyProfitLossRate: null,
        profitLossAmounts: {},
        profitLossRate: null,
        cashBalanceStatus: account.cash == null ? "UNKNOWN" : "KNOWN"
      },
      positions,
      buyingPower: buyingPowerMap(currency, account.cash),
      openOrders: list(state?.openOrders),
      risk: state?.risk ?? null
    }
  };
}

export function unavailablePortfolioStateSection(previous = {}, reason = "PORTFOLIO_STATE_UNAVAILABLE") {
  const previousData = previous?.data ?? {};
  const unknownFields = list(previous?.unknownFields);
  return {
    ...previous,
    stale: true,
    unknown: true,
    unknownFields: [...new Set([...unknownFields, "PORTFOLIO_STATE"])],
    unavailable: false,
    data: {
      ...previousData,
      staleReason: reason,
      partial: true,
      missingSections: [...new Set([...(previousData.missingSections ?? []), "PORTFOLIO_STATE"])]
    }
  };
}
