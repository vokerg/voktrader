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

export interface TradingEvent {
  timestamp: string;
  type: string;
  phase: string | null;
  strategyId: string | null;
  ruleId: string | null;
  botId: number | null;
  marketId: string | null;
  marketSlug: string | null;
  tokenId: string | null;
  outcome: string | null;
  reason: string | null;
  data: Record<string, unknown>;
}

export type TelemetryConnectionState =
  | 'connecting'
  | 'live'
  | 'error'
  | 'disconnected';

export type TelemetryPanelKey =
  | 'market'
  | 'price'
  | 'strategy'
  | 'execution';

export interface BotCreateRequest {
  name: string | null;
  marketFamily: string | null;
  asset: string | null;
  interval: string | null;
  strategyId: string;
  strategySetId: string | null;
  subStrategyId: string | null;
  enabled: boolean;
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

export interface TradeDetailResponse extends TradeSummaryResponse {
  ruleId: string | null;
  conditionId: string;
  decisionReason: string;
  marketEndAt: string | null;
  secondsToExpiryAtDecision: number | null;
  observedBid: number | null;
  observedAsk: number | null;
  observedSpread: number | null;
  observedMidpoint: number | null;
  priceUpdatedAt: string | null;
  priceAgeMs: number | null;
  intendedShares: number | null;
  intendedEntryPrice: number | null;
  intendedExitPrice: number | null;
  maxSlippagePrice: number | null;
  entryOrderType: string | null;
  entryAvgPrice: number | null;
  entryFilledShares: number | null;
  entryFeeUsd: number | null;
  entryCompletedAt: string | null;
  exitAvgPrice: number | null;
  exitFilledShares: number | null;
  exitFeeUsd: number | null;
  exitCompletedAt: string | null;
  totalFeeUsd: number | null;
  realizedPnlUsd: number | null;
  resolvedPnlUsd: number | null;
  winningOutcome: string | null;
  resolvedAt: string | null;
  backtestRunId: string | null;
  createdAt: string;
  fills: TradeFillResponse[];
  orders: TradeOrderResponse[];
  events: TradeEventResponse[];
}

export interface TradeFillResponse {
  id: number;
  tradeId: number;
  orderId: number | null;
  exchangeOrderId: string | null;
  remoteFillId: string | null;
  marketId: string;
  tokenId: string;
  venue: string;
  side: string;
  price: number;
  shares: number;
  amountUsd: number;
  feeUsd: number | null;
  feeKnown: boolean;
  liquidityRole: string;
  filledAt: string;
  occurredAt: string;
  receivedAt: string;
  createdAt: string;
}

export interface TradeOrderResponse {
  id: number;
  botId: number;
  tradeId: number;
  localOrderId: string;
  clientOrderId: string;
  remoteOrderId: string | null;
  exchangeOrderId: string | null;
  strategyId: string;
  marketId: string;
  tokenId: string;
  outcome: string;
  side: string;
  phase: string;
  mode: string;
  venue: string;
  orderType: string;
  status: string;
  requestedPrice: number | null;
  requestedShares: number | null;
  requestedAmountUsd: number | null;
  filledPrice: number | null;
  filledShares: number | null;
  filledAmountUsd: number | null;
  avgFillPrice: number | null;
  realizedFeeUsd: number | null;
  errorMessage: string | null;
  submittedAt: string | null;
  acknowledgedAt: string | null;
  completedAt: string | null;
  lastReconciledAt: string | null;
  latencyMs: number | null;
  rawRequest: string | null;
  rawResponse: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TradeEventResponse {
  id: number;
  tradeId: number;
  tradeOrderId: number | null;
  tradeFillId: number | null;
  eventType: string;
  message: string;
  payloadJson: string | null;
  createdAt: string;
}

export interface TradeOrderDetailResponse {
  order: TradeOrderResponse;
  fills: TradeFillResponse[];
  events: TradeEventResponse[];
}

export interface MarketDetailResponse extends MarketSummaryResponse {
  recentPrices: any[];
  recentOrderBooks: any[];
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
