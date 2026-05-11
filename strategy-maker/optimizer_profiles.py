from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Sequence, Tuple


@dataclass(frozen=True)
class ParamSpec:
    lo: float
    hi: float
    value_type: str = "float"
    decimals: int = 3


@dataclass(frozen=True)
class ExperimentClass:
    name: str
    allowed_keys: Sequence[str]
    max_changes: int
    intent: str


class StrategyYamlAdapter:
    def extract_params(self, yaml_text: str, profile: "StrategyProfile") -> Dict[str, Any]:
        raise NotImplementedError

    def apply_changes(self, yaml_text: str, changes: Dict[str, Any], profile: "StrategyProfile") -> str:
        raise NotImplementedError


@dataclass(frozen=True)
class StrategyProfile:
    name: str
    description: str
    strategy_file: str
    strategy_id: str
    root_key: str
    run_slug: str
    prompt_target: str
    param_specs: Dict[str, ParamSpec]
    experiments: Sequence[ExperimentClass]
    fallback_candidates: Dict[str, List[Dict[str, Any]]]
    adapter: StrategyYamlAdapter
    default_market_ids: Sequence[str]


def clamp_value(profile: StrategyProfile, name: str, value: Any) -> Any:
    if name not in profile.param_specs:
        raise ValueError(f"unsupported parameter: {name}")
    spec = profile.param_specs[name]
    try:
        number = float(value)
    except Exception as exc:
        raise ValueError(f"{name} must be numeric, got {value!r}") from exc

    number = max(spec.lo, min(spec.hi, number))
    if spec.value_type == "int":
        return int(round(number))
    return round(number, spec.decimals)


def fmt_value(profile: StrategyProfile, name: str, value: Any) -> str:
    spec = profile.param_specs[name]
    normalized = clamp_value(profile, name, value)
    if spec.value_type == "int":
        return str(int(normalized))
    return f"{float(normalized):.{spec.decimals}f}"


def canonical_changes(profile: StrategyProfile, changes: Dict[str, Any]) -> Dict[str, Any]:
    normalized: Dict[str, Any] = {}
    for key in sorted(changes):
        if key in profile.param_specs:
            normalized[key] = clamp_value(profile, key, changes[key])
    return normalized


def canonical_params_hash(profile: StrategyProfile, params: Dict[str, Any]) -> str:
    payload = json.dumps(canonical_changes(profile, params), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


class StrategyV2PaperYamlAdapter(StrategyYamlAdapter):
    VALUE_MAP: Sequence[Tuple[str, str, str]] = (
        ("spread_max", "candidate.spread", "<="),
        ("ask_max", "candidate.ask", "<="),
        ("worst_price_max", "candidate.taker_buy.worst_price", "<="),
        ("bid_min", "candidate.bid", ">="),
        ("mid_edge_min", "candidate.mid_edge", ">="),
        ("mid_move_5s_min", "candidate.mid_move_5s", ">="),
        ("bid_move_3s_min", "candidate.bid_move_3s", ">="),
        ("volatility_max", "candidate.volatility_10s", "<="),
        ("depth_imbalance_min", "candidate.depth_imbalance_0_03", ">="),
        ("slippage_max", "candidate.taker_buy.slippage", "<="),
        ("fee_usd_max", "candidate.taker_buy.fee_usd", "<="),
    )

    @staticmethod
    def _sub_once(text: str, pattern: str, replacement_fn, label: str) -> str:
        new_text, count = re.subn(pattern, replacement_fn, text, count=1, flags=re.S)
        if count != 1:
            raise RuntimeError(f"Could not replace {label}; regex matches={count}")
        return new_text

    @staticmethod
    def _feature_value_pattern(feature: str, op: str) -> str:
        return (
            r'(feature:\s*"' + re.escape(feature) +
            r'"\s+op:\s*"' + re.escape(op) +
            r'"\s+value:\s*")[^"]+(")'
        )

    @staticmethod
    def _feature_between_pattern(feature: str) -> str:
        return (
            r'(feature:\s*"' + re.escape(feature) +
            r'"\s+op:\s*"between"\s+values:\s*)\["[^"]+",\s*"[^"]+"\]'
        )

    @staticmethod
    def _named_rule_value_pattern(rule_name: str, feature: str, op: str) -> str:
        return (
            r'(name:\s*"' + re.escape(rule_name) +
            r'"(?:(?!- name:).)*?feature:\s*"' + re.escape(feature) +
            r'"\s+op:\s*"' + re.escape(op) +
            r'"\s+value:\s*")[^"]+(")'
        )

    @classmethod
    def set_feature_value(cls, text: str, feature: str, op: str, value: str) -> str:
        pattern = cls._feature_value_pattern(feature, op)
        return cls._sub_once(text, pattern, lambda match: f"{match.group(1)}{value}{match.group(2)}", feature)

    @classmethod
    def set_feature_between(cls, text: str, feature: str, start: str, end: str) -> str:
        pattern = cls._feature_between_pattern(feature)
        return cls._sub_once(text, pattern, lambda match: f'{match.group(1)}["{start}", "{end}"]', feature)

    @classmethod
    def set_named_rule_value(cls, text: str, rule_name: str, feature: str, op: str, value: str) -> str:
        pattern = cls._named_rule_value_pattern(rule_name, feature, op)
        return cls._sub_once(
            text,
            pattern,
            lambda match: f"{match.group(1)}{value}{match.group(2)}",
            f"{rule_name}.{feature}",
        )

    @classmethod
    def get_feature_value(cls, text: str, feature: str, op: str) -> Optional[float]:
        match = re.search(cls._feature_value_pattern(feature, op), text, flags=re.S)
        if not match:
            return None
        return float(match.group(0).split("value:")[-1].strip().strip('"'))

    @classmethod
    def get_feature_between(cls, text: str, feature: str) -> Optional[Tuple[float, float]]:
        match = re.search(cls._feature_between_pattern(feature), text, flags=re.S)
        if not match:
            return None
        values = re.findall(r'"([-0-9.]+)"', match.group(0))
        if len(values) < 2:
            return None
        return float(values[-2]), float(values[-1])

    @classmethod
    def get_min_score(cls, text: str) -> Optional[float]:
        match = re.search(r'min-score:\s*"([^"]+)"', text)
        return float(match.group(1)) if match else None

    @classmethod
    def get_named_rule_value(cls, text: str, rule_name: str, feature: str, op: str) -> Optional[float]:
        pattern = cls._named_rule_value_pattern(rule_name, feature, op)
        match = re.search(pattern, text, flags=re.S)
        if not match:
            return None
        return float(match.group(0).split("value:")[-1].strip().strip('"'))

    def extract_params(self, yaml_text: str, profile: StrategyProfile) -> Dict[str, Any]:
        params: Dict[str, Any] = {}
        expiry = self.get_feature_between(yaml_text, "market.seconds_to_expiry")
        if expiry:
            params["expiry_min"] = int(expiry[0])
            params["expiry_max"] = int(expiry[1])

        mid = self.get_feature_between(yaml_text, "candidate.mid")
        if mid:
            params["mid_min"] = round(mid[0], 3)
            params["mid_max"] = round(mid[1], 3)

        for key, feature, op in self.VALUE_MAP:
            value = self.get_feature_value(yaml_text, feature, op)
            if value is not None:
                params[key] = value

        min_score = self.get_min_score(yaml_text)
        if min_score is not None:
            params["min_score"] = min_score

        take_profit = self.get_named_rule_value(yaml_text, "fee_aware_take_profit", "trade.estimated_net_pnl_usd", ">=")
        if take_profit is not None:
            params["take_profit_usd"] = take_profit

        time_decay = self.get_named_rule_value(yaml_text, "time_decay_exit", "trade.hold_seconds", ">=")
        if time_decay is not None:
            params["time_decay_hold_seconds"] = int(time_decay)

        return canonical_changes(profile, params)

    def apply_changes(self, yaml_text: str, changes: Dict[str, Any], profile: StrategyProfile) -> str:
        text = yaml_text
        normalized = canonical_changes(profile, changes)

        if "expiry_min" in normalized or "expiry_max" in normalized:
            current = self.extract_params(text, profile)
            start = int(normalized.get("expiry_min", current.get("expiry_min", 45)))
            end = int(normalized.get("expiry_max", current.get("expiry_max", 240)))
            if start >= end:
                raise ValueError(f"invalid expiry range {start}..{end}")
            text = self.set_feature_between(text, "market.seconds_to_expiry", str(start), str(end))

        if "mid_min" in normalized or "mid_max" in normalized:
            current = self.extract_params(text, profile)
            start = float(normalized.get("mid_min", current.get("mid_min", 0.55)))
            end = float(normalized.get("mid_max", current.get("mid_max", 0.64)))
            if start >= end:
                raise ValueError(f"invalid mid range {start}..{end}")
            text = self.set_feature_between(
                text,
                "candidate.mid",
                fmt_value(profile, "mid_min", start),
                fmt_value(profile, "mid_max", end),
            )

        for key, feature, op in self.VALUE_MAP:
            if key in normalized:
                text = self.set_feature_value(text, feature, op, fmt_value(profile, key, normalized[key]))

        if "min_score" in normalized:
            text = self._sub_once(
                text,
                r'(min-score:\s*")[^"]+(")',
                lambda match: f"{match.group(1)}{fmt_value(profile, 'min_score', normalized['min_score'])}{match.group(2)}",
                "min-score",
            )

        if "take_profit_usd" in normalized:
            text = self.set_named_rule_value(
                text,
                "fee_aware_take_profit",
                "trade.estimated_net_pnl_usd",
                ">=",
                fmt_value(profile, "take_profit_usd", normalized["take_profit_usd"]),
            )

        if "time_decay_hold_seconds" in normalized:
            text = self.set_named_rule_value(
                text,
                "time_decay_exit",
                "trade.hold_seconds",
                ">=",
                fmt_value(profile, "time_decay_hold_seconds", normalized["time_decay_hold_seconds"]),
            )

        if f"{profile.root_key}:" not in text:
            raise RuntimeError(f"YAML lost {profile.root_key} root")
        if re.search(r'execution-mode:\s*"?LIVE"?|allowed-execution-modes:.*LIVE', text, re.I | re.S):
            raise RuntimeError("Refusing YAML that appears to enable LIVE")
        return text


STRATEGY_V2_PAPER_PROFILE = StrategyProfile(
    name="strategy-v2-paper",
    description="Liquidity and momentum paper strategy for Strategy V2.",
    strategy_file="src/main/resources/strategy-v2.paper.yml",
    strategy_id="strategy-v2",
    root_key="strategy-v2",
    run_slug="strategy-v2-ai",
    prompt_target="Voktrader strategy-v2 paper profile",
    param_specs={
        "expiry_min": ParamSpec(lo=15, hi=90, value_type="int"),
        "expiry_max": ParamSpec(lo=120, hi=400, value_type="int"),
        "spread_max": ParamSpec(lo=0.015, hi=0.050, decimals=3),
        "ask_max": ParamSpec(lo=0.55, hi=0.70, decimals=2),
        "worst_price_max": ParamSpec(lo=0.55, hi=0.72, decimals=2),
        "bid_min": ParamSpec(lo=0.35, hi=0.55, decimals=2),
        "mid_min": ParamSpec(lo=0.45, hi=0.60, decimals=2),
        "mid_max": ParamSpec(lo=0.60, hi=0.75, decimals=2),
        "mid_edge_min": ParamSpec(lo=0.05, hi=0.20, decimals=3),
        "mid_move_5s_min": ParamSpec(lo=0.004, hi=0.020, decimals=3),
        "bid_move_3s_min": ParamSpec(lo=-0.020, hi=0.010, decimals=3),
        "volatility_max": ParamSpec(lo=0.040, hi=0.200, decimals=3),
        "depth_imbalance_min": ParamSpec(lo=-0.500, hi=0.200, decimals=3),
        "slippage_max": ParamSpec(lo=0.005, hi=0.030, decimals=3),
        "fee_usd_max": ParamSpec(lo=0.020, hi=0.080, decimals=3),
        "min_score": ParamSpec(lo=0.030, hi=0.120, decimals=3),
        "take_profit_usd": ParamSpec(lo=0.040, hi=0.200, decimals=3),
        "time_decay_hold_seconds": ParamSpec(lo=20, hi=120, value_type="int"),
    },
    experiments=[
        ExperimentClass(
            name="ask_worst_price",
            allowed_keys=("ask_max", "worst_price_max", "bid_min"),
            max_changes=3,
            intent="Test entry price gates only. Do not touch expiry, mid range, score, spread, or exits.",
        ),
        ExperimentClass(
            name="expiry_window",
            allowed_keys=("expiry_min", "expiry_max"),
            max_changes=2,
            intent="Test seconds-to-expiry window only. Try narrower or wider, but do not touch price gates.",
        ),
        ExperimentClass(
            name="momentum_quality",
            allowed_keys=("mid_move_5s_min", "mid_edge_min", "bid_move_3s_min"),
            max_changes=2,
            intent="Test momentum/edge quality gates only. Tightening is allowed; not every high reject count is bad.",
        ),
        ExperimentClass(
            name="liquidity_cost",
            allowed_keys=("spread_max", "slippage_max", "fee_usd_max"),
            max_changes=2,
            intent="Test liquidity/cost gates only. Extra trades are bad if gross PnL worsens.",
        ),
        ExperimentClass(
            name="price_band",
            allowed_keys=("mid_min", "mid_max"),
            max_changes=2,
            intent="Test the candidate mid price band only.",
        ),
        ExperimentClass(
            name="score_threshold",
            allowed_keys=("min_score",),
            max_changes=1,
            intent="Test candidate-selection min-score only. Both higher and lower thresholds are valid experiments.",
        ),
        ExperimentClass(
            name="exits_only",
            allowed_keys=("take_profit_usd", "time_decay_hold_seconds"),
            max_changes=2,
            intent="Test exits only. Do not change entry conditions.",
        ),
        ExperimentClass(
            name="volatility_depth",
            allowed_keys=("volatility_max", "depth_imbalance_min"),
            max_changes=2,
            intent="Test volatility/depth filters only.",
        ),
        ExperimentClass(
            name="counter_regime",
            allowed_keys=(
                "expiry_min",
                "expiry_max",
                "spread_max",
                "ask_max",
                "worst_price_max",
                "bid_min",
                "mid_min",
                "mid_max",
                "mid_edge_min",
                "mid_move_5s_min",
                "bid_move_3s_min",
                "volatility_max",
                "depth_imbalance_min",
                "slippage_max",
                "fee_usd_max",
                "min_score",
                "take_profit_usd",
                "time_decay_hold_seconds",
            ),
            max_changes=3,
            intent="Test a compensated regime: if you loosen one gate, tighten another quality/cost gate. Avoid broad-loosen patches.",
        ),
    ],
    fallback_candidates={
        "ask_worst_price": [
            {"hypothesis": "Slightly loosen ask cap only.", "changes": {"ask_max": 0.62, "worst_price_max": 0.64}},
            {"hypothesis": "Tighten ask cap to test whether current extra candidates are low quality.", "changes": {"ask_max": 0.58, "worst_price_max": 0.60}},
            {"hypothesis": "Keep ask near baseline but raise worst price a little.", "changes": {"worst_price_max": 0.64}},
            {"hypothesis": "Tighten bid floor to demand better entry quality.", "changes": {"bid_min": 0.49}},
        ],
        "expiry_window": [
            {"hypothesis": "Widen only the early side of expiry window.", "changes": {"expiry_min": 30, "expiry_max": 240}},
            {"hypothesis": "Tighten expiry window around baseline to avoid bad edges.", "changes": {"expiry_min": 60, "expiry_max": 220}},
            {"hypothesis": "Widen only the late side of expiry window.", "changes": {"expiry_min": 45, "expiry_max": 300}},
            {"hypothesis": "Moderately widen expiry window.", "changes": {"expiry_min": 30, "expiry_max": 260}},
        ],
        "momentum_quality": [
            {"hypothesis": "Lower momentum threshold slightly.", "changes": {"mid_move_5s_min": 0.010}},
            {"hypothesis": "Raise momentum threshold to filter bad extra trades.", "changes": {"mid_move_5s_min": 0.015}},
            {"hypothesis": "Require stronger edge.", "changes": {"mid_edge_min": 0.120}},
            {"hypothesis": "Require non-negative short bid move.", "changes": {"bid_move_3s_min": 0.000}},
        ],
        "liquidity_cost": [
            {"hypothesis": "Tighten spread to reduce bad fills.", "changes": {"spread_max": 0.020}},
            {"hypothesis": "Relax spread modestly.", "changes": {"spread_max": 0.030}},
            {"hypothesis": "Lower allowed slippage.", "changes": {"slippage_max": 0.010}},
            {"hypothesis": "Lower fee cap.", "changes": {"fee_usd_max": 0.040}},
        ],
        "price_band": [
            {"hypothesis": "Narrow mid band upward.", "changes": {"mid_min": 0.57, "mid_max": 0.64}},
            {"hypothesis": "Widen lower side of mid band only.", "changes": {"mid_min": 0.53, "mid_max": 0.64}},
            {"hypothesis": "Widen upper side of mid band only.", "changes": {"mid_min": 0.55, "mid_max": 0.68}},
            {"hypothesis": "Shift mid band lower.", "changes": {"mid_min": 0.50, "mid_max": 0.60}},
        ],
        "score_threshold": [
            {"hypothesis": "Raise min-score to demand stronger candidates.", "changes": {"min_score": 0.090}},
            {"hypothesis": "Lower min-score slightly to reduce no-candidate cases.", "changes": {"min_score": 0.065}},
            {"hypothesis": "Raise min-score aggressively.", "changes": {"min_score": 0.105}},
            {"hypothesis": "Lower min-score moderately.", "changes": {"min_score": 0.050}},
        ],
        "exits_only": [
            {"hypothesis": "Take profit earlier.", "changes": {"take_profit_usd": 0.080}},
            {"hypothesis": "Let profits run longer.", "changes": {"take_profit_usd": 0.150}},
            {"hypothesis": "Exit time-decay positions earlier.", "changes": {"time_decay_hold_seconds": 40}},
            {"hypothesis": "Hold time-decay positions longer.", "changes": {"time_decay_hold_seconds": 90}},
        ],
        "volatility_depth": [
            {"hypothesis": "Tighten volatility filter.", "changes": {"volatility_max": 0.060}},
            {"hypothesis": "Relax volatility filter.", "changes": {"volatility_max": 0.120}},
            {"hypothesis": "Require better depth imbalance.", "changes": {"depth_imbalance_min": -0.100}},
            {"hypothesis": "Require positive depth imbalance.", "changes": {"depth_imbalance_min": 0.000}},
        ],
        "counter_regime": [
            {"hypothesis": "Loosen ask slightly but tighten spread to avoid broad bad regime.", "changes": {"ask_max": 0.62, "worst_price_max": 0.64, "spread_max": 0.020}},
            {"hypothesis": "Widen expiry but require stronger momentum.", "changes": {"expiry_min": 30, "expiry_max": 260, "mid_move_5s_min": 0.015}},
            {"hypothesis": "Lower min score but tighten fee and slippage.", "changes": {"min_score": 0.065, "fee_usd_max": 0.040, "slippage_max": 0.010}},
            {"hypothesis": "Widen mid band but raise min score.", "changes": {"mid_min": 0.53, "mid_max": 0.66, "min_score": 0.095}},
        ],
    },
    adapter=StrategyV2PaperYamlAdapter(),
    default_market_ids=(
        "2184295",
        "2184298",
        "2184330",
        "2184341",
        "2184493",
        "2184500",
        "2184524",
        "2184531",
        "2184560",
        "2184566",
        "2184581",
    ),
)


PROFILES: Dict[str, StrategyProfile] = {
    STRATEGY_V2_PAPER_PROFILE.name: STRATEGY_V2_PAPER_PROFILE,
}


def get_profile(name: str) -> StrategyProfile:
    try:
        return PROFILES[name]
    except KeyError as exc:
        available = ", ".join(sorted(PROFILES))
        raise KeyError(f"Unknown profile '{name}'. Available profiles: {available}") from exc


def list_profiles() -> List[StrategyProfile]:
    return [PROFILES[name] for name in sorted(PROFILES)]
