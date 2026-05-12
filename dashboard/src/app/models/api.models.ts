export interface BotConfigResponse {
  id: number;
  name: string;
  enabled: boolean;
  runtimeActive: boolean;
  marketFamily: string;
  asset: string;
  interval: string;
  strategyId: string;
  strategySetId: string | null;
  subStrategyId: string | null;
  runtimeIncluded: boolean;
  runtimeIncludeGuardActive: boolean;
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

export interface RuntimeStatusResponse {
  activeProfiles: string[];
  datasourceUrl: string;
  tradingMode: string;
  killSwitchEnabled: boolean;
  liveEnabled: boolean;
  maxOrderUsd: number;
  maxTradesPerMarket: number;
  maxOpenLiveTrades: number;
  allowedStrategyIds: string[];
  executor: ExecutorStatus;
  orderLayer: OrderLayerStatus;
  currentTopLevelActiveStrategy: string;
  strategyV2ActiveInnerStrategyIds: string[];
  enabledBots: RuntimeBotStatus[];
}

export interface ExecutorStatus {
  enabled: boolean;
  dryRun: boolean;
  baseUrl: string;
  requireImmediateFill: boolean;
}

export interface OrderLayerStatus {
  enabled: boolean;
  reconciliationEnabled: boolean;
}

export interface RuntimeBotStatus {
  id: number;
  name: string;
  strategyId: string;
  strategySetId: string | null;
  subStrategyId: string | null;
  marketFamily: string;
  status: string;
  enabled: boolean;
}

export interface StrategyCatalogResponse {
  topLevelStrategies: TopLevelStrategy[];
  currentDefaultActiveStrategy: string;
  strategyV2ActiveInnerStrategyIds: string[];
  strategyV2ConfiguredStrategies: StrategyV2InnerStrategy[];
  strategyV2SetIds: string[];
  validBacktestStrategyIds: string[];
  strategyV2TopLevelBacktestId: string;
  backtestStrategyIdRule: string;
}

export interface TopLevelStrategy {
  id: string;
  description: any;
}

export interface StrategyV2InnerStrategy {
  id: string;
  enabled: boolean;
  profile: string;
  description: string;
  allowedExecutionModes: string[];
}
