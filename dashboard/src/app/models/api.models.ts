export interface BotConfigResponse {
  id: number;
  name: string;
  enabled: boolean;
  runtimeActive: boolean;
  marketFamily: string;
  asset: string;
  interval: string;
  strategyId: string;
  status: string;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DashboardOptionsResponse {
  marketFamilies: MarketFamilyOption[];
  strategies: StrategyOption[];
  botStatuses: string[];
  tradeStatuses: string[];
  executionModes: string[];
  trackingStatuses: string[];
  resolutionStatuses: string[];
}

export interface MarketFamilyOption {
  id: string;
  asset: string;
  interval: string;
}

export interface StrategyOption {
  id: string;
  name: string;
  status: string;
  intent: string;
}

export interface MarketSummaryResponse {
  id: number;
  polymarketMarketId: string;
  conditionId: string;
  question: string;
  slug: string;
  endDate: string;
  active: boolean;
  closed: boolean;
  acceptingOrders: boolean;
  resolved: boolean;
  trackingStatus: string;
  resolutionStatus: string;
  winningOutcome: string | null;
  winningAssetId: string | null;
  resolvedAt: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  latestPrice: any; // Simplified for now
  latestOrderBook: any; // Simplified for now
}

export interface TradeSummaryResponse {
  id: number;
  botId: number;
  mode: string;
  strategyId: string;
  marketId: string;
  marketSlug: string;
  question: string;
  tokenId: string;
  outcome: string;
  status: string;
  decisionSide: string;
  intendedAmountUsd: number;
  entryFilledUsd: number;
  exitFilledUsd: number;
  finalPnlUsd: number;
  decisionAt: string;
  updatedAt: string;
}
