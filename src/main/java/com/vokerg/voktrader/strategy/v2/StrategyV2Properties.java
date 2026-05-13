package com.vokerg.voktrader.strategy.v2;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "strategy-v2")
public class StrategyV2Properties {
    private String schemaVersion = "2.0";
    private String setId;
    private Engine engine = new Engine();
    private Map<String, Profile> defaults = new LinkedHashMap<>();
    private List<Strategy> strategies = new ArrayList<>();

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getSetId() { return setId; }
    public void setSetId(String setId) { this.setId = setId; }
    public Engine getEngine() { return engine; }
    public void setEngine(Engine engine) { this.engine = engine == null ? new Engine() : engine; }
    public Map<String, Profile> getDefaults() { return defaults; }
    public void setDefaults(Map<String, Profile> defaults) { this.defaults = defaults == null ? new LinkedHashMap<>() : defaults; }
    public List<Strategy> getStrategies() { return strategies; }
    public void setStrategies(List<Strategy> strategies) { this.strategies = strategies == null ? new ArrayList<>() : strategies; }

    public static class Engine {
        private boolean enabled = false;
        private List<String> activeStrategyIds = new ArrayList<>();
        private long tickMs = 1000;
        private String decisionMode = "single_market_single_position";
        private int decimalScale = 8;
        private boolean requireCompleteUpDownPrice = true;
        private boolean requireMidSumSane = true;
        private BigDecimal minMidSum = new BigDecimal("0.97");
        private BigDecimal maxMidSum = new BigDecimal("1.03");
        private String defaultProfile = "conservative_paper";
        private Safety safety = new Safety();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getActiveStrategyIds() { return activeStrategyIds; }
        public void setActiveStrategyIds(List<String> activeStrategyIds) { this.activeStrategyIds = activeStrategyIds == null ? new ArrayList<>() : activeStrategyIds; }
        public long getTickMs() { return tickMs; }
        public void setTickMs(long tickMs) { this.tickMs = tickMs; }
        public String getDecisionMode() { return decisionMode; }
        public void setDecisionMode(String decisionMode) { this.decisionMode = decisionMode; }
        public int getDecimalScale() { return decimalScale; }
        public void setDecimalScale(int decimalScale) { this.decimalScale = decimalScale; }
        public boolean isRequireCompleteUpDownPrice() { return requireCompleteUpDownPrice; }
        public void setRequireCompleteUpDownPrice(boolean requireCompleteUpDownPrice) { this.requireCompleteUpDownPrice = requireCompleteUpDownPrice; }
        public boolean isRequireMidSumSane() { return requireMidSumSane; }
        public void setRequireMidSumSane(boolean requireMidSumSane) { this.requireMidSumSane = requireMidSumSane; }
        public BigDecimal getMinMidSum() { return minMidSum; }
        public void setMinMidSum(BigDecimal minMidSum) { this.minMidSum = minMidSum; }
        public BigDecimal getMaxMidSum() { return maxMidSum; }
        public void setMaxMidSum(BigDecimal maxMidSum) { this.maxMidSum = maxMidSum; }
        public String getDefaultProfile() { return defaultProfile; }
        public void setDefaultProfile(String defaultProfile) { this.defaultProfile = defaultProfile; }
        public Safety getSafety() { return safety; }
        public void setSafety(Safety safety) { this.safety = safety == null ? new Safety() : safety; }
    }

    public static class Safety {
        private boolean killSwitchRespected = true;
        private boolean liveRequiresExplicitAllowlist = true;
        private boolean blockLiveMakerWithoutReconciliation = true;
        private boolean blockUnknownOrderType = true;
        private boolean blockMissingFeeModel = true;
        private boolean blockMissingBookForBookRules = true;

        public boolean isKillSwitchRespected() { return killSwitchRespected; }
        public void setKillSwitchRespected(boolean killSwitchRespected) { this.killSwitchRespected = killSwitchRespected; }
        public boolean isLiveRequiresExplicitAllowlist() { return liveRequiresExplicitAllowlist; }
        public void setLiveRequiresExplicitAllowlist(boolean liveRequiresExplicitAllowlist) { this.liveRequiresExplicitAllowlist = liveRequiresExplicitAllowlist; }
        public boolean isBlockLiveMakerWithoutReconciliation() { return blockLiveMakerWithoutReconciliation; }
        public void setBlockLiveMakerWithoutReconciliation(boolean blockLiveMakerWithoutReconciliation) { this.blockLiveMakerWithoutReconciliation = blockLiveMakerWithoutReconciliation; }
        public boolean isBlockUnknownOrderType() { return blockUnknownOrderType; }
        public void setBlockUnknownOrderType(boolean blockUnknownOrderType) { this.blockUnknownOrderType = blockUnknownOrderType; }
        public boolean isBlockMissingFeeModel() { return blockMissingFeeModel; }
        public void setBlockMissingFeeModel(boolean blockMissingFeeModel) { this.blockMissingFeeModel = blockMissingFeeModel; }
        public boolean isBlockMissingBookForBookRules() { return blockMissingBookForBookRules; }
        public void setBlockMissingBookForBookRules(boolean blockMissingBookForBookRules) { this.blockMissingBookForBookRules = blockMissingBookForBookRules; }
    }

    public static class Strategy {
        private String strategyId;
        private boolean enabled = true;
        private String profile;
        private String description;
        private String hypothesis;
        private List<String> tags = new ArrayList<>();
        private List<String> allowedExecutionModes = new ArrayList<>();
        private CandidateSelection candidateSelection = new CandidateSelection();
        private Entry entry = new Entry();
        private Exit exit = new Exit();
        private Simulation simulation = new Simulation();
        private Diagnostics diagnostics = new Diagnostics();
        private EntryOrderManagement entryOrderManagement = new EntryOrderManagement();
        private PartialFillManagement partialFillManagement = new PartialFillManagement();
        private ExitOrderManagement exitOrderManagement = new ExitOrderManagement();
        private Map<String, Object> marketFilter = new LinkedHashMap<>();
        private Map<String, Object> dataRequirements = new LinkedHashMap<>();
        private Map<String, Object> features = new LinkedHashMap<>();
        private Map<String, Object> risk = new LinkedHashMap<>();
        private Map<String, Object> orderManagement = new LinkedHashMap<>();
        private Map<String, Object> experiment = new LinkedHashMap<>();

        public String getStrategyId() { return strategyId; }
        public void setStrategyId(String strategyId) { this.strategyId = strategyId; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProfile() { return profile; }
        public void setProfile(String profile) { this.profile = profile; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getHypothesis() { return hypothesis; }
        public void setHypothesis(String hypothesis) { this.hypothesis = hypothesis; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
        public List<String> getAllowedExecutionModes() { return allowedExecutionModes; }
        public void setAllowedExecutionModes(List<String> allowedExecutionModes) { this.allowedExecutionModes = allowedExecutionModes == null ? new ArrayList<>() : allowedExecutionModes; }
        public CandidateSelection getCandidateSelection() { return candidateSelection; }
        public void setCandidateSelection(CandidateSelection candidateSelection) { this.candidateSelection = candidateSelection == null ? new CandidateSelection() : candidateSelection; }
        public Entry getEntry() { return entry; }
        public void setEntry(Entry entry) { this.entry = entry == null ? new Entry() : entry; }
        public Exit getExit() { return exit; }
        public void setExit(Exit exit) { this.exit = exit == null ? new Exit() : exit; }
        public Simulation getSimulation() { return simulation; }
        public void setSimulation(Simulation simulation) { this.simulation = simulation == null ? new Simulation() : simulation; }
        public Diagnostics getDiagnostics() { return diagnostics; }
        public void setDiagnostics(Diagnostics diagnostics) { this.diagnostics = diagnostics == null ? new Diagnostics() : diagnostics; }
        public EntryOrderManagement getEntryOrderManagement() { return entryOrderManagement; }
        public void setEntryOrderManagement(EntryOrderManagement entryOrderManagement) { this.entryOrderManagement = entryOrderManagement == null ? new EntryOrderManagement() : entryOrderManagement; }
        public PartialFillManagement getPartialFillManagement() { return partialFillManagement; }
        public void setPartialFillManagement(PartialFillManagement partialFillManagement) { this.partialFillManagement = partialFillManagement == null ? new PartialFillManagement() : partialFillManagement; }
        public ExitOrderManagement getExitOrderManagement() { return exitOrderManagement; }
        public void setExitOrderManagement(ExitOrderManagement exitOrderManagement) { this.exitOrderManagement = exitOrderManagement == null ? new ExitOrderManagement() : exitOrderManagement; }
        public Map<String, Object> getMarketFilter() { return marketFilter; }
        public void setMarketFilter(Map<String, Object> marketFilter) { this.marketFilter = marketFilter == null ? new LinkedHashMap<>() : marketFilter; }
        public Map<String, Object> getDataRequirements() { return dataRequirements; }
        public void setDataRequirements(Map<String, Object> dataRequirements) { this.dataRequirements = dataRequirements == null ? new LinkedHashMap<>() : dataRequirements; }
        public Map<String, Object> getFeatures() { return features; }
        public void setFeatures(Map<String, Object> features) { this.features = features == null ? new LinkedHashMap<>() : features; }
        public Map<String, Object> getRisk() { return risk; }
        public void setRisk(Map<String, Object> risk) { this.risk = risk == null ? new LinkedHashMap<>() : risk; }
        public Map<String, Object> getOrderManagement() { return orderManagement; }
        public void setOrderManagement(Map<String, Object> orderManagement) { this.orderManagement = orderManagement == null ? new LinkedHashMap<>() : orderManagement; }
        public Map<String, Object> getExperiment() { return experiment; }
        public void setExperiment(Map<String, Object> experiment) { this.experiment = experiment == null ? new LinkedHashMap<>() : experiment; }
    }

    public static class Profile {
        private Data data = new Data();
        private Sizing sizing = new Sizing();
        private Fees fees = new Fees();
        private Execution execution = new Execution();
        private Map<String, Object> marketFilter = new LinkedHashMap<>();
        private Map<String, Object> pricing = new LinkedHashMap<>();
        private Map<String, Object> risk = new LinkedHashMap<>();
        private Map<String, Object> makerExecution = new LinkedHashMap<>();
        private Map<String, Object> exit = new LinkedHashMap<>();
        private Simulation simulation = new Simulation();

        public Data getData() { return data; }
        public void setData(Data data) { this.data = data == null ? new Data() : data; }
        public Sizing getSizing() { return sizing; }
        public void setSizing(Sizing sizing) { this.sizing = sizing == null ? new Sizing() : sizing; }
        public Fees getFees() { return fees; }
        public void setFees(Fees fees) { this.fees = fees == null ? new Fees() : fees; }
        public Execution getExecution() { return execution; }
        public void setExecution(Execution execution) { this.execution = execution == null ? new Execution() : execution; }
        public Map<String, Object> getMarketFilter() { return marketFilter; }
        public void setMarketFilter(Map<String, Object> marketFilter) { this.marketFilter = marketFilter == null ? new LinkedHashMap<>() : marketFilter; }
        public Map<String, Object> getPricing() { return pricing; }
        public void setPricing(Map<String, Object> pricing) { this.pricing = pricing == null ? new LinkedHashMap<>() : pricing; }
        public Map<String, Object> getRisk() { return risk; }
        public void setRisk(Map<String, Object> risk) { this.risk = risk == null ? new LinkedHashMap<>() : risk; }
        public Map<String, Object> getMakerExecution() { return makerExecution; }
        public void setMakerExecution(Map<String, Object> makerExecution) { this.makerExecution = makerExecution == null ? new LinkedHashMap<>() : makerExecution; }
        public Map<String, Object> getExit() { return exit; }
        public void setExit(Map<String, Object> exit) { this.exit = exit == null ? new LinkedHashMap<>() : exit; }
        public Simulation getSimulation() { return simulation; }
        public void setSimulation(Simulation simulation) { this.simulation = simulation == null ? new Simulation() : simulation; }
    }

    public static class Data {
        private long maxPriceAgeMs = 1500;
        private long maxBookAgeMs = 1500;
        private long warmupSeconds = 30;
        private int minSamplesPerWindow = 3;
        public long getMaxPriceAgeMs() { return maxPriceAgeMs; }
        public void setMaxPriceAgeMs(long maxPriceAgeMs) { this.maxPriceAgeMs = maxPriceAgeMs; }
        public long getMaxBookAgeMs() { return maxBookAgeMs; }
        public void setMaxBookAgeMs(long maxBookAgeMs) { this.maxBookAgeMs = maxBookAgeMs; }
        public long getWarmupSeconds() { return warmupSeconds; }
        public void setWarmupSeconds(long warmupSeconds) { this.warmupSeconds = warmupSeconds; }
        public int getMinSamplesPerWindow() { return minSamplesPerWindow; }
        public void setMinSamplesPerWindow(int minSamplesPerWindow) { this.minSamplesPerWindow = minSamplesPerWindow; }
    }

    public static class Sizing {
        private BigDecimal paperSizeUsd = new BigDecimal("1.00");
        private BigDecimal liveSizeUsd = new BigDecimal("5.00");
        private BigDecimal maxOrderUsd = new BigDecimal("5.00");
        private BigDecimal minOrderUsd = new BigDecimal("1.00");
        private BigDecimal priceTick = new BigDecimal("0.01");
        private int roundSharesScale = 4;
        public BigDecimal getPaperSizeUsd() { return paperSizeUsd; }
        public void setPaperSizeUsd(BigDecimal paperSizeUsd) { this.paperSizeUsd = paperSizeUsd; }
        public BigDecimal getLiveSizeUsd() { return liveSizeUsd; }
        public void setLiveSizeUsd(BigDecimal liveSizeUsd) { this.liveSizeUsd = liveSizeUsd; }
        public BigDecimal getMaxOrderUsd() { return maxOrderUsd; }
        public void setMaxOrderUsd(BigDecimal maxOrderUsd) { this.maxOrderUsd = maxOrderUsd; }
        public BigDecimal getMinOrderUsd() { return minOrderUsd; }
        public void setMinOrderUsd(BigDecimal minOrderUsd) { this.minOrderUsd = minOrderUsd; }
        public BigDecimal getPriceTick() { return priceTick; }
        public void setPriceTick(BigDecimal priceTick) { this.priceTick = priceTick; }
        public int getRoundSharesScale() { return roundSharesScale; }
        public void setRoundSharesScale(int roundSharesScale) { this.roundSharesScale = roundSharesScale; }
    }

    public static class Fees {
        private BigDecimal makerFeeRate = BigDecimal.ZERO;
        private BigDecimal takerFeeRate = new BigDecimal("0.072");
        private boolean estimateLiveFeesWhenMissing = true;
        public BigDecimal getMakerFeeRate() { return makerFeeRate; }
        public void setMakerFeeRate(BigDecimal makerFeeRate) { this.makerFeeRate = makerFeeRate; }
        public BigDecimal getTakerFeeRate() { return takerFeeRate; }
        public void setTakerFeeRate(BigDecimal takerFeeRate) { this.takerFeeRate = takerFeeRate; }
        public boolean isEstimateLiveFeesWhenMissing() { return estimateLiveFeesWhenMissing; }
        public void setEstimateLiveFeesWhenMissing(boolean estimateLiveFeesWhenMissing) { this.estimateLiveFeesWhenMissing = estimateLiveFeesWhenMissing; }
    }

    public static class Execution {
        private String defaultEntryOrderType = "FOK";
        private String defaultExitOrderType = "FOK";
        private String defaultEntryLiquidityRole = "taker";
        private boolean postOnly = false;
        private boolean allowPartialFill = false;
        public String getDefaultEntryOrderType() { return defaultEntryOrderType; }
        public void setDefaultEntryOrderType(String defaultEntryOrderType) { this.defaultEntryOrderType = defaultEntryOrderType; }
        public String getDefaultExitOrderType() { return defaultExitOrderType; }
        public void setDefaultExitOrderType(String defaultExitOrderType) { this.defaultExitOrderType = defaultExitOrderType; }
        public String getDefaultEntryLiquidityRole() { return defaultEntryLiquidityRole; }
        public void setDefaultEntryLiquidityRole(String defaultEntryLiquidityRole) { this.defaultEntryLiquidityRole = defaultEntryLiquidityRole; }
        public boolean isPostOnly() { return postOnly; }
        public void setPostOnly(boolean postOnly) { this.postOnly = postOnly; }
        public boolean isAllowPartialFill() { return allowPartialFill; }
        public void setAllowPartialFill(boolean allowPartialFill) { this.allowPartialFill = allowPartialFill; }
    }

    public static class CandidateSelection {
        private String type = "higher_mid";
        private String outcome;
        private String feature;
        private List<String> candidates = new ArrayList<>(List.of("Up", "Down"));
        private Map<String, ScoreExpression> score = new LinkedHashMap<>();
        private String choose = "highest_score";
        private BigDecimal minScore;
        private String tieBreaker = "skip";
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
        public String getFeature() { return feature; }
        public void setFeature(String feature) { this.feature = feature; }
        public List<String> getCandidates() { return candidates; }
        public void setCandidates(List<String> candidates) { this.candidates = candidates == null ? new ArrayList<>() : candidates; }
        public Map<String, ScoreExpression> getScore() { return score; }
        public void setScore(Map<String, ScoreExpression> score) { this.score = score == null ? new LinkedHashMap<>() : score; }
        public String getChoose() { return choose; }
        public void setChoose(String choose) { this.choose = choose; }
        public BigDecimal getMinScore() { return minScore; }
        public void setMinScore(BigDecimal minScore) { this.minScore = minScore; }
        public String getTieBreaker() { return tieBreaker; }
        public void setTieBreaker(String tieBreaker) { this.tieBreaker = tieBreaker; }
    }

    public static class ScoreExpression {
        private String expression;
        public String getExpression() { return expression; }
        public void setExpression(String expression) { this.expression = expression; }
    }

    public static class Entry {
        private boolean enabled = true;
        private String ruleId = "entry";
        private Condition when;
        private Action action = new Action();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getRuleId() { return ruleId; }
        public void setRuleId(String ruleId) { this.ruleId = ruleId; }
        public Condition getWhen() { return when; }
        public void setWhen(Condition when) { this.when = when; }
        public Action getAction() { return action; }
        public void setAction(Action action) { this.action = action == null ? new Action() : action; }
    }

    public static class Exit {
        private boolean enabled = true;
        private String ruleId = "exit";
        private List<ExitRule> rules = new ArrayList<>();
        private String defaultAction = "HOLD";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getRuleId() { return ruleId; }
        public void setRuleId(String ruleId) { this.ruleId = ruleId; }
        public List<ExitRule> getRules() { return rules; }
        public void setRules(List<ExitRule> rules) { this.rules = rules == null ? new ArrayList<>() : rules; }
        public String getDefaultAction() { return defaultAction; }
        public void setDefaultAction(String defaultAction) { this.defaultAction = defaultAction; }
    }

    public static class ExitRule {
        private String name;
        private String action;
        private String orderType;
        private String liquidityRole;
        private Condition when;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getOrderType() { return orderType; }
        public void setOrderType(String orderType) { this.orderType = orderType; }
        public String getLiquidityRole() { return liquidityRole; }
        public void setLiquidityRole(String liquidityRole) { this.liquidityRole = liquidityRole; }
        public Condition getWhen() { return when; }
        public void setWhen(Condition when) { this.when = when; }
    }

    public static class Condition {
        private List<Condition> all;
        private List<Condition> any;
        private Condition not;
        private String feature;
        private String op;
        private Object value;
        private List<Object> values;
        public List<Condition> getAll() { return all; }
        public void setAll(List<Condition> all) { this.all = all; }
        public List<Condition> getAny() { return any; }
        public void setAny(List<Condition> any) { this.any = any; }
        public Condition getNot() { return not; }
        public void setNot(Condition not) { this.not = not; }
        public String getFeature() { return feature; }
        public void setFeature(String feature) { this.feature = feature; }
        public String getOp() { return op; }
        public void setOp(String op) { this.op = op; }
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
        public List<Object> getValues() { return values; }
        public void setValues(List<Object> values) { this.values = values; }
    }

    public static class Action {
        private String side = "BUY";
        private String liquidityRole = "taker";
        private String orderType = "FOK";
        private Boolean postOnly;
        private Price price = new Price();
        private Size size = new Size();
        private FillConstraints fillConstraints = new FillConstraints();
        private MakerLifecycle makerLifecycle = new MakerLifecycle();
        private String reasonTemplate;
        public String getSide() { return side; }
        public void setSide(String side) { this.side = side; }
        public String getLiquidityRole() { return liquidityRole; }
        public void setLiquidityRole(String liquidityRole) { this.liquidityRole = liquidityRole; }
        public String getOrderType() { return orderType; }
        public void setOrderType(String orderType) { this.orderType = orderType; }
        public Boolean getPostOnly() { return postOnly; }
        public void setPostOnly(Boolean postOnly) { this.postOnly = postOnly; }
        public Price getPrice() { return price; }
        public void setPrice(Price price) { this.price = price == null ? new Price() : price; }
        public Size getSize() { return size; }
        public void setSize(Size size) { this.size = size == null ? new Size() : size; }
        public FillConstraints getFillConstraints() { return fillConstraints; }
        public void setFillConstraints(FillConstraints fillConstraints) { this.fillConstraints = fillConstraints == null ? new FillConstraints() : fillConstraints; }
        public MakerLifecycle getMakerLifecycle() { return makerLifecycle; }
        public void setMakerLifecycle(MakerLifecycle makerLifecycle) { this.makerLifecycle = makerLifecycle == null ? new MakerLifecycle() : makerLifecycle; }
        public String getReasonTemplate() { return reasonTemplate; }
        public void setReasonTemplate(String reasonTemplate) { this.reasonTemplate = reasonTemplate; }
    }

    public static class Price {
        private String source = "best_ask";
        private String formula;
        private int offsetTicks = 0;
        private int improveByTicks = 0;
        private BigDecimal tickSize = new BigDecimal("0.01");
        private String rounding = "none";
        private BigDecimal minPrice = new BigDecimal("0.01");
        private BigDecimal maxPrice = new BigDecimal("0.99");
        private boolean doNotCrossSpread = true;
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getFormula() { return formula; }
        public void setFormula(String formula) { this.formula = formula; }
        public int getOffsetTicks() { return offsetTicks; }
        public void setOffsetTicks(int offsetTicks) { this.offsetTicks = offsetTicks; }
        public int getImproveByTicks() { return improveByTicks; }
        public void setImproveByTicks(int improveByTicks) { this.improveByTicks = improveByTicks; }
        public BigDecimal getTickSize() { return tickSize; }
        public void setTickSize(BigDecimal tickSize) { this.tickSize = tickSize; }
        public String getRounding() { return rounding; }
        public void setRounding(String rounding) { this.rounding = rounding; }
        public BigDecimal getMinPrice() { return minPrice; }
        public void setMinPrice(BigDecimal minPrice) { this.minPrice = minPrice; }
        public BigDecimal getMaxPrice() { return maxPrice; }
        public void setMaxPrice(BigDecimal maxPrice) { this.maxPrice = maxPrice; }
        public boolean isDoNotCrossSpread() { return doNotCrossSpread; }
        public void setDoNotCrossSpread(boolean doNotCrossSpread) { this.doNotCrossSpread = doNotCrossSpread; }
    }

    public static class Size {
        private String type = "fixed_usd";
        private BigDecimal paperUsd = new BigDecimal("1.00");
        private BigDecimal liveUsd = new BigDecimal("5.00");
        private BigDecimal maxUsd = new BigDecimal("5.00");
        private BigDecimal minUsd = new BigDecimal("1.00");
        private BigDecimal shares;
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public BigDecimal getPaperUsd() { return paperUsd; }
        public void setPaperUsd(BigDecimal paperUsd) { this.paperUsd = paperUsd; }
        public BigDecimal getLiveUsd() { return liveUsd; }
        public void setLiveUsd(BigDecimal liveUsd) { this.liveUsd = liveUsd; }
        public BigDecimal getMaxUsd() { return maxUsd; }
        public void setMaxUsd(BigDecimal maxUsd) { this.maxUsd = maxUsd; }
        public BigDecimal getMinUsd() { return minUsd; }
        public void setMinUsd(BigDecimal minUsd) { this.minUsd = minUsd; }
        public BigDecimal getShares() { return shares; }
        public void setShares(BigDecimal shares) { this.shares = shares; }
    }

    public static class FillConstraints {
        private boolean allowPartialFill = false;
        private BigDecimal minFillRatio = BigDecimal.ONE;
        private BigDecimal maxSlippage;
        private BigDecimal maxWorstPrice;
        private BigDecimal maxFeeUsd;
        public boolean isAllowPartialFill() { return allowPartialFill; }
        public void setAllowPartialFill(boolean allowPartialFill) { this.allowPartialFill = allowPartialFill; }
        public BigDecimal getMinFillRatio() { return minFillRatio; }
        public void setMinFillRatio(BigDecimal minFillRatio) { this.minFillRatio = minFillRatio; }
        public BigDecimal getMaxSlippage() { return maxSlippage; }
        public void setMaxSlippage(BigDecimal maxSlippage) { this.maxSlippage = maxSlippage; }
        public BigDecimal getMaxWorstPrice() { return maxWorstPrice; }
        public void setMaxWorstPrice(BigDecimal maxWorstPrice) { this.maxWorstPrice = maxWorstPrice; }
        public BigDecimal getMaxFeeUsd() { return maxFeeUsd; }
        public void setMaxFeeUsd(BigDecimal maxFeeUsd) { this.maxFeeUsd = maxFeeUsd; }
    }

    public static class MakerLifecycle {
        private int cancelAfterSeconds = 8;
        private int cooldownAfterNoFillCancelSeconds = 0;
        private int replaceIfBestBidMovesTicks = 1;
        private int maxReposts = 1;
        private boolean requireReconciliation = true;
        public int getCancelAfterSeconds() { return cancelAfterSeconds; }
        public void setCancelAfterSeconds(int cancelAfterSeconds) { this.cancelAfterSeconds = cancelAfterSeconds; }
        public int getCooldownAfterNoFillCancelSeconds() { return cooldownAfterNoFillCancelSeconds; }
        public void setCooldownAfterNoFillCancelSeconds(int cooldownAfterNoFillCancelSeconds) { this.cooldownAfterNoFillCancelSeconds = cooldownAfterNoFillCancelSeconds; }
        public int getReplaceIfBestBidMovesTicks() { return replaceIfBestBidMovesTicks; }
        public void setReplaceIfBestBidMovesTicks(int replaceIfBestBidMovesTicks) { this.replaceIfBestBidMovesTicks = replaceIfBestBidMovesTicks; }
        public int getMaxReposts() { return maxReposts; }
        public void setMaxReposts(int maxReposts) { this.maxReposts = maxReposts; }
        public boolean isRequireReconciliation() { return requireReconciliation; }
        public void setRequireReconciliation(boolean requireReconciliation) { this.requireReconciliation = requireReconciliation; }
    }

    public static class Simulation {
        private String fillModel = "taker_instant";
        private String feeModel = "configured";
        private BigDecimal takerFeeRate = new BigDecimal("0.072");
        private BigDecimal makerFeeRate = BigDecimal.ZERO;
        private Map<String, Object> taker = new LinkedHashMap<>();
        private Map<String, Object> maker = new LinkedHashMap<>();
        public String getFillModel() { return fillModel; }
        public void setFillModel(String fillModel) { this.fillModel = fillModel; }
        public String getFeeModel() { return feeModel; }
        public void setFeeModel(String feeModel) { this.feeModel = feeModel; }
        public BigDecimal getTakerFeeRate() { return takerFeeRate; }
        public void setTakerFeeRate(BigDecimal takerFeeRate) { this.takerFeeRate = takerFeeRate; }
        public BigDecimal getMakerFeeRate() { return makerFeeRate; }
        public void setMakerFeeRate(BigDecimal makerFeeRate) { this.makerFeeRate = makerFeeRate; }
        public Map<String, Object> getTaker() { return taker; }
        public void setTaker(Map<String, Object> taker) { this.taker = taker == null ? new LinkedHashMap<>() : taker; }
        public Map<String, Object> getMaker() { return maker; }
        public void setMaker(Map<String, Object> maker) { this.maker = maker == null ? new LinkedHashMap<>() : maker; }
    }

    public static class EntryOrderManagement {
        private int maxPendingSeconds = 8;
        private boolean cancelIfSignalInvalid = true;
        private boolean cancelIfBookStale = true;
        private boolean cancelIfPriceMovesAway = true;
        private boolean allowReprice = false;
        public int getMaxPendingSeconds() { return maxPendingSeconds; }
        public void setMaxPendingSeconds(int maxPendingSeconds) { this.maxPendingSeconds = maxPendingSeconds; }
        public boolean isCancelIfSignalInvalid() { return cancelIfSignalInvalid; }
        public void setCancelIfSignalInvalid(boolean cancelIfSignalInvalid) { this.cancelIfSignalInvalid = cancelIfSignalInvalid; }
        public boolean isCancelIfBookStale() { return cancelIfBookStale; }
        public void setCancelIfBookStale(boolean cancelIfBookStale) { this.cancelIfBookStale = cancelIfBookStale; }
        public boolean isCancelIfPriceMovesAway() { return cancelIfPriceMovesAway; }
        public void setCancelIfPriceMovesAway(boolean cancelIfPriceMovesAway) { this.cancelIfPriceMovesAway = cancelIfPriceMovesAway; }
        public boolean isAllowReprice() { return allowReprice; }
        public void setAllowReprice(boolean allowReprice) { this.allowReprice = allowReprice; }
    }

    public static class PartialFillManagement {
        private int cancelRemainingOnPartialAfterSeconds = 5;
        private boolean allowExitPartialPosition = true;
        public int getCancelRemainingOnPartialAfterSeconds() { return cancelRemainingOnPartialAfterSeconds; }
        public void setCancelRemainingOnPartialAfterSeconds(int cancelRemainingOnPartialAfterSeconds) { this.cancelRemainingOnPartialAfterSeconds = cancelRemainingOnPartialAfterSeconds; }
        public boolean isAllowExitPartialPosition() { return allowExitPartialPosition; }
        public void setAllowExitPartialPosition(boolean allowExitPartialPosition) { this.allowExitPartialPosition = allowExitPartialPosition; }
    }

    public static class ExitOrderManagement {
        private int maxPendingSeconds = 5;
        private boolean retryOnReject = false;
        private int maxRetries = 0;
        public int getMaxPendingSeconds() { return maxPendingSeconds; }
        public void setMaxPendingSeconds(int maxPendingSeconds) { this.maxPendingSeconds = maxPendingSeconds; }
        public boolean isRetryOnReject() { return retryOnReject; }
        public void setRetryOnReject(boolean retryOnReject) { this.retryOnReject = retryOnReject; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class Diagnostics {
        private boolean recordRejections = true;
        private boolean recordFeatureSnapshotOnEntry = true;
        private boolean recordCandidateScores = true;
        public boolean isRecordRejections() { return recordRejections; }
        public void setRecordRejections(boolean recordRejections) { this.recordRejections = recordRejections; }
        public boolean isRecordFeatureSnapshotOnEntry() { return recordFeatureSnapshotOnEntry; }
        public void setRecordFeatureSnapshotOnEntry(boolean recordFeatureSnapshotOnEntry) { this.recordFeatureSnapshotOnEntry = recordFeatureSnapshotOnEntry; }
        public boolean isRecordCandidateScores() { return recordCandidateScores; }
        public void setRecordCandidateScores(boolean recordCandidateScores) { this.recordCandidateScores = recordCandidateScores; }
    }
}
