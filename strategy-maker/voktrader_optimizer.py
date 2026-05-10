#!/usr/bin/env python3
"""
voktrader_optimizer.py
Version: python-ollama-ledger-v1

Windows-friendly optimizer for src/main/resources/strategy-v2.paper.yml.

Design:
- Ollama/model proposes JSON only.
- Python validates/clamps the JSON proposal.
- Python edits exactly one YAML file.
- Spring Boot/backtest is the judge.
- Every tried change/result is kept in a compact experiment ledger.
- Duplicate parameter vectors are skipped before expensive backtests.

Typical use from C:\repos\voktrader:

    python voktrader_optimizer.py --model qwen3-coder:30b --iters 20

If auto-start ever gives trouble:

    python voktrader_optimizer.py --model qwen3-coder:30b --server-mode manual --iters 10

Manual mode pauses before every backtest so you can restart Spring Boot yourself.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import difflib
import hashlib
import json
import os
import random
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

VERSION = "python-ollama-ledger-v1"

DEFAULT_MARKET_IDS = [
    "2184295", "2184298", "2184330", "2184341", "2184493", "2184500",
    "2184524", "2184531", "2184560", "2184566", "2184581",
]

PARAM_SPECS: Dict[str, Dict[str, Any]] = {
    "expiry_min": {"lo": 15, "hi": 90, "type": "int"},
    "expiry_max": {"lo": 120, "hi": 400, "type": "int"},
    "spread_max": {"lo": 0.015, "hi": 0.050, "dec": 3},
    "ask_max": {"lo": 0.55, "hi": 0.70, "dec": 2},
    "worst_price_max": {"lo": 0.55, "hi": 0.72, "dec": 2},
    "bid_min": {"lo": 0.35, "hi": 0.55, "dec": 2},
    "mid_min": {"lo": 0.45, "hi": 0.60, "dec": 2},
    "mid_max": {"lo": 0.60, "hi": 0.75, "dec": 2},
    "mid_edge_min": {"lo": 0.05, "hi": 0.20, "dec": 3},
    "mid_move_5s_min": {"lo": 0.004, "hi": 0.020, "dec": 3},
    "bid_move_3s_min": {"lo": -0.020, "hi": 0.010, "dec": 3},
    "volatility_max": {"lo": 0.040, "hi": 0.200, "dec": 3},
    "depth_imbalance_min": {"lo": -0.500, "hi": 0.200, "dec": 3},
    "slippage_max": {"lo": 0.005, "hi": 0.030, "dec": 3},
    "fee_usd_max": {"lo": 0.020, "hi": 0.080, "dec": 3},
    "min_score": {"lo": 0.030, "hi": 0.120, "dec": 3},
    "take_profit_usd": {"lo": 0.040, "hi": 0.200, "dec": 3},
    "time_decay_hold_seconds": {"lo": 20, "hi": 120, "type": "int"},
}

EXPERIMENT_CLASSES: List[Dict[str, Any]] = [
    {
        "name": "ask_worst_price",
        "allowed_keys": ["ask_max", "worst_price_max", "bid_min"],
        "max_changes": 3,
        "intent": "Test entry price gates only. Do not touch expiry, mid range, score, spread, or exits.",
    },
    {
        "name": "expiry_window",
        "allowed_keys": ["expiry_min", "expiry_max"],
        "max_changes": 2,
        "intent": "Test seconds-to-expiry window only. Try narrower or wider, but do not touch price gates.",
    },
    {
        "name": "momentum_quality",
        "allowed_keys": ["mid_move_5s_min", "mid_edge_min", "bid_move_3s_min"],
        "max_changes": 2,
        "intent": "Test momentum/edge quality gates only. Tightening is allowed; not every high reject count is bad.",
    },
    {
        "name": "liquidity_cost",
        "allowed_keys": ["spread_max", "slippage_max", "fee_usd_max"],
        "max_changes": 2,
        "intent": "Test liquidity/cost gates only. Extra trades are bad if gross PnL worsens.",
    },
    {
        "name": "price_band",
        "allowed_keys": ["mid_min", "mid_max"],
        "max_changes": 2,
        "intent": "Test the candidate mid price band only.",
    },
    {
        "name": "score_threshold",
        "allowed_keys": ["min_score"],
        "max_changes": 1,
        "intent": "Test candidate-selection min-score only. Both higher and lower thresholds are valid experiments.",
    },
    {
        "name": "exits_only",
        "allowed_keys": ["take_profit_usd", "time_decay_hold_seconds"],
        "max_changes": 2,
        "intent": "Test exits only. Do not change entry conditions.",
    },
    {
        "name": "volatility_depth",
        "allowed_keys": ["volatility_max", "depth_imbalance_min"],
        "max_changes": 2,
        "intent": "Test volatility/depth filters only.",
    },
    {
        "name": "counter_regime",
        "allowed_keys": list(PARAM_SPECS.keys()),
        "max_changes": 3,
        "intent": "Test a compensated regime: if you loosen one gate, tighten another quality/cost gate. Avoid broad-loosen patches.",
    },
]


def now_stamp() -> str:
    return dt.datetime.now().strftime("%Y%m%d_%H%M%S")


def log(msg: str) -> None:
    print(f"[{dt.datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def clamp_value(name: str, value: Any) -> Any:
    if name not in PARAM_SPECS:
        raise ValueError(f"unsupported parameter: {name}")
    spec = PARAM_SPECS[name]
    try:
        x = float(value)
    except Exception as exc:
        raise ValueError(f"{name} must be numeric, got {value!r}") from exc

    lo = float(spec["lo"])
    hi = float(spec["hi"])
    x = max(lo, min(hi, x))

    if spec.get("type") == "int":
        return int(round(x))
    dec = int(spec.get("dec", 3))
    return round(x, dec)


def fmt_value(name: str, value: Any) -> str:
    spec = PARAM_SPECS[name]
    v = clamp_value(name, value)
    if spec.get("type") == "int":
        return str(int(v))
    return f"{float(v):.{int(spec.get('dec', 3))}f}"


def canonical_changes(changes: Dict[str, Any]) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for key in sorted(changes):
        if key in PARAM_SPECS:
            out[key] = clamp_value(key, changes[key])
    return out


def canonical_params_hash(params: Dict[str, Any]) -> str:
    payload = json.dumps(canonical_changes(params), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


def result_signature(result: Dict[str, Any]) -> str:
    fields = {
        "tradeCount": result.get("tradeCount", 0),
        "grossPnlUsd": str(result.get("grossPnlUsd", 0)),
        "totalFeeUsd": str(result.get("totalFeeUsd", 0)),
        "finalPnlUsd": str(result.get("finalPnlUsd", 0)),
    }
    return hashlib.sha256(json.dumps(fields, sort_keys=True).encode("utf-8")).hexdigest()[:12]


def score_result(result: Dict[str, Any], min_trades: int) -> float:
    pnl = float(result.get("finalPnlUsd") or 0.0)
    trades = int(result.get("tradeCount") or 0)
    fill_rate = float((result.get("orderMetrics") or {}).get("fillRate") or 0.0)
    score = pnl
    if trades < min_trades:
        score -= (min_trades - trades) * 0.25
    if fill_rate < 1.0:
        score -= (1.0 - fill_rate) * 0.10
    return score


class StrategyYaml:
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
        return cls._sub_once(text, pattern, lambda m: f"{m.group(1)}{value}{m.group(2)}", feature)

    @classmethod
    def set_feature_between(cls, text: str, feature: str, a: str, b: str) -> str:
        pattern = cls._feature_between_pattern(feature)
        return cls._sub_once(text, pattern, lambda m: f'{m.group(1)}["{a}", "{b}"]', feature)

    @classmethod
    def set_named_rule_value(cls, text: str, rule_name: str, feature: str, op: str, value: str) -> str:
        pattern = cls._named_rule_value_pattern(rule_name, feature, op)
        return cls._sub_once(text, pattern, lambda m: f"{m.group(1)}{value}{m.group(2)}", f"{rule_name}.{feature}")

    @classmethod
    def get_feature_value(cls, text: str, feature: str, op: str) -> Optional[float]:
        m = re.search(cls._feature_value_pattern(feature, op), text, flags=re.S)
        if not m:
            return None
        return float(m.group(0).split('value:')[-1].strip().strip('"'))

    @classmethod
    def get_feature_between(cls, text: str, feature: str) -> Optional[Tuple[float, float]]:
        m = re.search(cls._feature_between_pattern(feature), text, flags=re.S)
        if not m:
            return None
        vals = re.findall(r'"([-0-9.]+)"', m.group(0))
        if len(vals) < 2:
            return None
        return float(vals[-2]), float(vals[-1])

    @classmethod
    def get_min_score(cls, text: str) -> Optional[float]:
        m = re.search(r'min-score:\s*"([^"]+)"', text)
        return float(m.group(1)) if m else None

    @classmethod
    def get_named_rule_value(cls, text: str, rule_name: str, feature: str, op: str) -> Optional[float]:
        pattern = cls._named_rule_value_pattern(rule_name, feature, op)
        m = re.search(pattern, text, flags=re.S)
        if not m:
            return None
        return float(m.group(0).split('value:')[-1].strip().strip('"'))

    @classmethod
    def extract_params(cls, yaml_text: str) -> Dict[str, Any]:
        params: Dict[str, Any] = {}
        expiry = cls.get_feature_between(yaml_text, "market.seconds_to_expiry")
        if expiry:
            params["expiry_min"] = int(expiry[0])
            params["expiry_max"] = int(expiry[1])

        mid = cls.get_feature_between(yaml_text, "candidate.mid")
        if mid:
            params["mid_min"] = round(mid[0], 3)
            params["mid_max"] = round(mid[1], 3)

        mapping = [
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
        ]
        for key, feature, op in mapping:
            val = cls.get_feature_value(yaml_text, feature, op)
            if val is not None:
                params[key] = val

        ms = cls.get_min_score(yaml_text)
        if ms is not None:
            params["min_score"] = ms

        tp = cls.get_named_rule_value(yaml_text, "fee_aware_take_profit", "trade.estimated_net_pnl_usd", ">=")
        if tp is not None:
            params["take_profit_usd"] = tp

        th = cls.get_named_rule_value(yaml_text, "time_decay_exit", "trade.hold_seconds", ">=")
        if th is not None:
            params["time_decay_hold_seconds"] = int(th)

        return canonical_changes(params)

    @classmethod
    def apply_changes(cls, yaml_text: str, changes: Dict[str, Any]) -> str:
        text = yaml_text
        c = canonical_changes(changes)

        if "expiry_min" in c or "expiry_max" in c:
            current = cls.extract_params(text)
            a = int(c.get("expiry_min", current.get("expiry_min", 45)))
            b = int(c.get("expiry_max", current.get("expiry_max", 240)))
            if a >= b:
                raise ValueError(f"invalid expiry range {a}..{b}")
            text = cls.set_feature_between(text, "market.seconds_to_expiry", str(a), str(b))

        if "mid_min" in c or "mid_max" in c:
            current = cls.extract_params(text)
            a = float(c.get("mid_min", current.get("mid_min", 0.55)))
            b = float(c.get("mid_max", current.get("mid_max", 0.64)))
            if a >= b:
                raise ValueError(f"invalid mid range {a}..{b}")
            text = cls.set_feature_between(text, "candidate.mid", fmt_value("mid_min", a), fmt_value("mid_max", b))

        value_map = [
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
        ]
        for key, feature, op in value_map:
            if key in c:
                text = cls.set_feature_value(text, feature, op, fmt_value(key, c[key]))

        if "min_score" in c:
            text = cls._sub_once(
                text,
                r'(min-score:\s*")[^"]+(")',
                lambda m: f"{m.group(1)}{fmt_value('min_score', c['min_score'])}{m.group(2)}",
                "min-score",
            )

        if "take_profit_usd" in c:
            text = cls.set_named_rule_value(
                text,
                "fee_aware_take_profit",
                "trade.estimated_net_pnl_usd",
                ">=",
                fmt_value("take_profit_usd", c["take_profit_usd"]),
            )

        if "time_decay_hold_seconds" in c:
            text = cls.set_named_rule_value(
                text,
                "time_decay_exit",
                "trade.hold_seconds",
                ">=",
                fmt_value("time_decay_hold_seconds", c["time_decay_hold_seconds"]),
            )

        if "strategy-v2:" not in text:
            raise RuntimeError("YAML lost strategy-v2 root")
        if re.search(r'execution-mode:\s*"?LIVE"?|allowed-execution-modes:.*LIVE', text, re.I | re.S):
            raise RuntimeError("Refusing YAML that appears to enable LIVE")
        return text


@dataclass
class ExperimentRecord:
    iteration: int
    experiment_type: str
    hypothesis: str
    changes: Dict[str, Any]
    param_hash: str
    result_signature: str
    result: Dict[str, Any]
    score: float
    accepted: bool
    notes: str

    def compact(self) -> Dict[str, Any]:
        return {
            "iteration": self.iteration,
            "type": self.experiment_type,
            "accepted": self.accepted,
            "score": round(self.score, 8),
            "changes": self.changes,
            "result": {
                "tradeCount": self.result.get("tradeCount"),
                "grossPnlUsd": self.result.get("grossPnlUsd"),
                "totalFeeUsd": self.result.get("totalFeeUsd"),
                "finalPnlUsd": self.result.get("finalPnlUsd"),
                "netRoiPct": self.result.get("netRoiPct"),
            },
            "notes": self.notes,
        }


class OllamaClient:
    def __init__(self, base_url: str, model: str, timeout: int, num_ctx: int, temperature: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout = timeout
        self.num_ctx = num_ctx
        self.temperature = temperature

    def generate(self, prompt: str) -> str:
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "keep_alive": "30m",
            "options": {
                "temperature": self.temperature,
                "top_p": 0.9,
                "num_ctx": self.num_ctx,
            },
        }
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}/api/generate",
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            obj = json.loads(resp.read().decode("utf-8"))
        return str(obj.get("response", ""))


def extract_json_object(text: str) -> Dict[str, Any]:
    start = text.find("{")
    if start < 0:
        raise ValueError("No JSON object found")
    depth = 0
    in_str = False
    escape = False
    end: Optional[int] = None
    for i in range(start, len(text)):
        ch = text[i]
        if in_str:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_str = False
        else:
            if ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
    if end is None:
        raise ValueError("Could not find balanced JSON object")
    obj = json.loads(text[start:end])
    if not isinstance(obj, dict):
        raise ValueError("JSON root is not an object")
    return obj


def validate_model_patch(obj: Dict[str, Any], exp_class: Dict[str, Any]) -> Dict[str, Any]:
    changes_raw = obj.get("changes")
    if not isinstance(changes_raw, dict):
        raise ValueError("JSON must contain object field 'changes'")
    allowed = set(exp_class["allowed_keys"])
    bad = sorted(set(changes_raw) - allowed)
    if bad:
        raise ValueError(f"patch used keys outside experiment class: {bad}")
    if len(changes_raw) == 0:
        raise ValueError("patch changes is empty")
    if len(changes_raw) > int(exp_class["max_changes"]):
        raise ValueError(f"too many changes: {len(changes_raw)} > {exp_class['max_changes']}")

    changes = canonical_changes(changes_raw)
    if not changes:
        raise ValueError("no supported parameter changes")

    # Cross-field sanity.
    if "expiry_min" in changes and "expiry_max" in changes and int(changes["expiry_min"]) >= int(changes["expiry_max"]):
        raise ValueError("expiry_min must be < expiry_max")
    if "mid_min" in changes and "mid_max" in changes and float(changes["mid_min"]) >= float(changes["mid_max"]):
        raise ValueError("mid_min must be < mid_max")

    return {
        "hypothesis": str(obj.get("hypothesis", "model proposal"))[:400],
        "changes": changes,
    }


class BacktestRunner:
    def __init__(self, repo: Path, repo_win: str, port: int, server_mode: str, run_root: Path) -> None:
        self.repo = repo
        self.repo_win = repo_win
        self.port = port
        self.server_mode = server_mode
        self.run_root = run_root
        self.proc: Optional[subprocess.Popen] = None
        self.base_url = f"http://localhost:{port}"

    def stop_app(self) -> None:
        if self.server_mode != "auto":
            return
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.terminate()
                self.proc.wait(timeout=8)
            except Exception:
                try:
                    self.proc.kill()
                except Exception:
                    pass
        self.proc = None
        ps = (
            "$ErrorActionPreference = 'SilentlyContinue'; "
            f"$pidsToKill = @(Get-NetTCPConnection -LocalPort {self.port} | Select-Object -ExpandProperty OwningProcess -Unique); "
            "foreach ($p in $pidsToKill) { if ($p) { Stop-Process -Id $p -Force } }"
        )
        subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def start_app(self, iteration: int) -> None:
        log_file = self.run_root / f"app_{iteration}.log"
        if self.server_mode == "manual":
            print("\nManual server mode:")
            print("  1. Stop the currently running Spring Boot server.")
            print("  2. Start it again from PowerShell:")
            print("       cd C:\\repos\\voktrader")
            print("       ./mvnw spring-boot:run")
            input(f"Press Enter when server is ready for iteration {iteration}...")
            self.wait_ready(log_file)
            return
        if self.server_mode == "external":
            self.wait_ready(log_file)
            return

        self.stop_app()
        log(f"Starting Spring Boot for iteration {iteration}...")
        log(f"App log: {log_file}")
        command = f"Set-Location -LiteralPath '{self.repo_win}'; & .\\mvnw.cmd spring-boot:run"
        out = open(log_file, "w", encoding="utf-8", errors="replace")
        self.proc = subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
            cwd=str(self.repo),
            stdout=out,
            stderr=subprocess.STDOUT,
        )
        self.wait_ready(log_file)

    def wait_ready(self, log_file: Path, timeout_seconds: int = 240) -> None:
        deadline = time.time() + timeout_seconds
        while time.time() < deadline:
            if self._http_status(f"{self.base_url}/v3/api-docs") is not None:
                log("App ready.")
                time.sleep(2)
                return
            if self.server_mode == "auto" and self.proc and self.proc.poll() is not None:
                tail = self._tail(log_file)
                raise RuntimeError(f"Spring Boot exited early. Log tail:\n{tail}")
            time.sleep(2)
        tail = self._tail(log_file)
        raise TimeoutError(f"Timed out waiting for app. Log tail:\n{tail}")

    def _http_status(self, url: str) -> Optional[int]:
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=3) as resp:
                return int(resp.status)
        except Exception:
            return None

    def _tail(self, path: Path, lines: int = 120) -> str:
        if not path.exists():
            return ""
        text = path.read_text(encoding="utf-8", errors="replace")
        return "\n".join(text.splitlines()[-lines:])

    def run_backtest(self, iteration: int, strategy_id: str, market_ids: List[str], bot_id: int) -> Dict[str, Any]:
        body = {"strategyId": strategy_id, "marketIds": market_ids, "botId": bot_id}
        request_path = self.run_root / f"request_{iteration}.json"
        result_path = self.run_root / f"result_{iteration}.json"
        request_path.write_text(json.dumps(body, indent=2), encoding="utf-8")

        req = urllib.request.Request(
            f"{self.base_url}/api/backtests",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        log("Posting backtest request...")
        with urllib.request.urlopen(req, timeout=600) as resp:
            raw = resp.read().decode("utf-8")
        result_path.write_text(raw, encoding="utf-8")
        result = json.loads(raw)
        return result


def repo_to_windows_path(repo: Path) -> str:
    # Windows Python normally returns C:\... already. Git-Bash/MSYS paths can be converted with cygpath.
    p = str(repo.resolve())
    if re.match(r"^[A-Za-z]:", p):
        return p
    cygpath = shutil.which("cygpath")
    if cygpath:
        try:
            out = subprocess.check_output([cygpath, "-w", p], text=True).strip()
            if out:
                return out
        except Exception:
            pass
    if p.startswith("/c/"):
        return "C:\\" + p[3:].replace("/", "\\")
    return p


def make_diff(before: str, after: str, fromfile: str, tofile: str) -> str:
    return "".join(difflib.unified_diff(
        before.splitlines(keepends=True),
        after.splitlines(keepends=True),
        fromfile=fromfile,
        tofile=tofile,
    ))


def fallback_candidates(exp_class: Dict[str, Any]) -> List[Dict[str, Any]]:
    name = exp_class["name"]
    table: Dict[str, List[Dict[str, Any]]] = {
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
    }
    return table.get(name, [])


def summarize_experiments(records: List[ExperimentRecord], limit: int = 30) -> str:
    if not records:
        return "[]"
    rows = [r.compact() for r in records[-limit:]]
    return json.dumps(rows, indent=2, default=str)


def build_prompt(
    iteration: int,
    exp_class: Dict[str, Any],
    best_params: Dict[str, Any],
    best_result: Dict[str, Any],
    records: List[ExperimentRecord],
    tried_hashes: Iterable[str],
    min_trades: int,
) -> str:
    allowed = exp_class["allowed_keys"]
    specs = {k: PARAM_SPECS[k] for k in allowed}
    tried_compact = summarize_experiments(records, limit=40)
    recent_bad = [r.compact() for r in records if not r.accepted][-10:]
    duplicate_note = ""
    if records:
        sig_counts: Dict[str, int] = {}
        for r in records:
            sig_counts[r.result_signature] = sig_counts.get(r.result_signature, 0) + 1
        repeated = [sig for sig, count in sig_counts.items() if count > 1]
        if repeated:
            duplicate_note = f"\nSome different patches produced identical backtest behavior signatures: {repeated}. Avoid their neighborhoods."

    return f"""Return exactly one JSON object. No markdown. No explanation.

You are suggesting one experiment for Voktrader strategy-v2.
Python will validate your JSON, edit only strategy-v2.paper.yml, run the backtest, and accept/reject by measured result.

JSON shape:
{{
  "hypothesis": "one short sentence",
  "changes": {{
    "some_allowed_key": 0.123
  }}
}}

Experiment {iteration}: {exp_class['name']}
Intent: {exp_class['intent']}
Allowed keys for this experiment only: {allowed}
Max changed keys: {exp_class['max_changes']}
Allowed ranges/specs: {json.dumps(specs, indent=2)}

Rules:
- Include only allowed keys.
- Do not repeat previous tried changes or close variants that already failed.
- Do not assume a high reject count means a gate should be loosened. Some filters may be protecting against bad trades.
- You may tighten filters. You may loosen filters. Choose based on measured outcomes.
- Avoid the broad-loosen regime unless you compensate by tightening quality/cost gates.
- Need at least {min_trades} trades, but more trades are bad if grossPnlUsd or finalPnlUsd worsens.

Current best parameter vector:
{json.dumps(best_params, indent=2)}

Current best result:
{json.dumps({
        'tradeCount': best_result.get('tradeCount'),
        'grossPnlUsd': best_result.get('grossPnlUsd'),
        'totalFeeUsd': best_result.get('totalFeeUsd'),
        'finalPnlUsd': best_result.get('finalPnlUsd'),
        'netRoiPct': best_result.get('netRoiPct'),
        'entryRejectReasons': best_result.get('entryRejectReasons'),
    }, indent=2, default=str)}

Previous experiments and outcomes:
{tried_compact}

Recent rejected/bad experiments:
{json.dumps(recent_bad, indent=2, default=str)}
{duplicate_note}

Return only the JSON object.
"""


def write_jsonl(path: Path, obj: Dict[str, Any]) -> None:
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False, default=str) + "\n")


def append_csv(path: Path, row: Dict[str, Any]) -> None:
    exists = path.exists()
    fields = [
        "iteration", "experiment_type", "score", "finalPnlUsd", "grossPnlUsd", "totalFeeUsd", "netRoiPct",
        "tradeCount", "closedTradeCount", "fillRate", "runId", "accepted", "notes", "param_hash", "changes",
    ]
    with open(path, "a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        if not exists:
            writer.writeheader()
        writer.writerow({k: row.get(k, "") for k in fields})


def run_optimizer(args: argparse.Namespace) -> int:
    repo = Path(args.repo).resolve()
    strategy_path = repo / args.strategy_file
    if not strategy_path.exists():
        raise FileNotFoundError(f"Missing strategy file: {strategy_path}")

    repo_win = args.repo_win or repo_to_windows_path(repo)
    run_root = repo / args.run_root if args.run_root else repo / "runs" / "strategy-v2-ai" / now_stamp()
    run_root.mkdir(parents=True, exist_ok=True)
    for sub in ["prompts", "raw_model", "patches", "proposed", "diffs", "results"]:
        (run_root / sub).mkdir(exist_ok=True)

    # Local exclude so experiment logs do not pollute git status.
    exclude = repo / ".git" / "info" / "exclude"
    if exclude.parent.exists():
        exclude.touch(exist_ok=True)
        txt = exclude.read_text(encoding="utf-8", errors="replace")
        for line in ["runs/", Path(__file__).name]:
            if line not in txt.splitlines():
                with open(exclude, "a", encoding="utf-8") as f:
                    f.write(f"\n{line}\n")

    best_yaml_path = run_root / "best.strategy-v2.paper.yml"
    shutil.copy2(strategy_path, best_yaml_path)

    leaderboard_path = run_root / "leaderboard.csv"
    experiments_path = run_root / "experiments.jsonl"
    tried_hashes_path = run_root / "tried_hashes.json"

    runner = BacktestRunner(repo, repo_win, args.port, args.server_mode, run_root)
    ollama = OllamaClient(args.ollama_url, args.model, args.ollama_timeout, args.num_ctx, args.temperature)

    records: List[ExperimentRecord] = []
    tried_hashes: set[str] = set()
    best_result: Dict[str, Any] = {}
    best_score = float("-inf")

    def record_experiment(record: ExperimentRecord) -> None:
        records.append(record)
        tried_hashes.add(record.param_hash)
        write_jsonl(experiments_path, {
            "iteration": record.iteration,
            "experiment_type": record.experiment_type,
            "hypothesis": record.hypothesis,
            "changes": record.changes,
            "param_hash": record.param_hash,
            "result_signature": record.result_signature,
            "score": record.score,
            "accepted": record.accepted,
            "notes": record.notes,
            "result": record.result,
        })
        tried_hashes_path.write_text(json.dumps(sorted(tried_hashes), indent=2), encoding="utf-8")
        append_csv(leaderboard_path, {
            "iteration": record.iteration,
            "experiment_type": record.experiment_type,
            "score": f"{record.score:.8f}",
            "finalPnlUsd": record.result.get("finalPnlUsd"),
            "grossPnlUsd": record.result.get("grossPnlUsd"),
            "totalFeeUsd": record.result.get("totalFeeUsd"),
            "netRoiPct": record.result.get("netRoiPct"),
            "tradeCount": record.result.get("tradeCount"),
            "closedTradeCount": record.result.get("closedTradeCount"),
            "fillRate": (record.result.get("orderMetrics") or {}).get("fillRate"),
            "runId": record.result.get("runId"),
            "accepted": "yes" if record.accepted else "no",
            "notes": record.notes,
            "param_hash": record.param_hash,
            "changes": json.dumps(record.changes, sort_keys=True),
        })

    def evaluate_candidate(iteration: int, exp_type: str, hypothesis: str, changes: Dict[str, Any], notes: str) -> Tuple[ExperimentRecord, str]:
        nonlocal best_score, best_result
        base_yaml = best_yaml_path.read_text(encoding="utf-8", errors="replace")
        proposed_yaml = StrategyYaml.apply_changes(base_yaml, changes)
        params = StrategyYaml.extract_params(proposed_yaml)
        param_hash = canonical_params_hash(params)
        if param_hash in tried_hashes:
            raise RuntimeError(f"duplicate parameter vector skipped: {param_hash}")

        proposed_path = run_root / "proposed" / f"proposed_{iteration}.yml"
        proposed_path.write_text(proposed_yaml, encoding="utf-8")
        diff = make_diff(base_yaml, proposed_yaml, str(best_yaml_path.name), args.strategy_file)
        (run_root / "diffs" / f"diff_{iteration}.patch").write_text(diff, encoding="utf-8")

        print("\nApplied changes:")
        print(json.dumps({"hypothesis": hypothesis, "changes": changes, "param_hash": param_hash}, indent=2))
        print("\nDiff:")
        print(diff if diff else "<empty diff>")
        if not diff:
            raise RuntimeError("empty diff skipped")

        strategy_path.write_text(proposed_yaml, encoding="utf-8")
        runner.start_app(iteration)
        try:
            result = runner.run_backtest(iteration, args.strategy_id, args.market_ids, args.bot_id)
        finally:
            runner.stop_app()

        (run_root / "results" / f"result_{iteration}.json").write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
        score = score_result(result, args.min_trades)
        accepted = score > best_score
        record = ExperimentRecord(
            iteration=iteration,
            experiment_type=exp_type,
            hypothesis=hypothesis,
            changes=canonical_changes(changes),
            param_hash=param_hash,
            result_signature=result_signature(result),
            result=result,
            score=score,
            accepted=accepted,
            notes=notes,
        )
        if accepted:
            best_score = score
            best_result = result
            best_yaml_path.write_text(proposed_yaml, encoding="utf-8")
            print(f"Accepted new best. score={score:.8f}")
        else:
            strategy_path.write_text(best_yaml_path.read_text(encoding="utf-8", errors="replace"), encoding="utf-8")
            print(f"Rejected. score={score:.8f}, best={best_score:.8f}")
        return record, param_hash

    print()
    print(f"Version:       {VERSION}")
    print(f"Repo:          {repo}")
    print(f"Repo win path: {repo_win}")
    print(f"Strategy:      {strategy_path}")
    print(f"Run folder:    {run_root}")
    print(f"Model:         {args.model}")
    print(f"Server mode:   {args.server_mode}")
    print()

    # Baseline.
    log("Running baseline...")
    baseline_yaml = strategy_path.read_text(encoding="utf-8", errors="replace")
    baseline_params = StrategyYaml.extract_params(baseline_yaml)
    baseline_hash = canonical_params_hash(baseline_params)
    runner.start_app(0)
    try:
        best_result = runner.run_backtest(0, args.strategy_id, args.market_ids, args.bot_id)
    finally:
        runner.stop_app()
    (run_root / "results" / "result_0.json").write_text(json.dumps(best_result, indent=2, default=str), encoding="utf-8")
    best_score = score_result(best_result, args.min_trades)
    baseline_record = ExperimentRecord(
        iteration=0,
        experiment_type="baseline",
        hypothesis="baseline current strategy",
        changes={},
        param_hash=baseline_hash,
        result_signature=result_signature(best_result),
        result=best_result,
        score=best_score,
        accepted=True,
        notes="baseline",
    )
    record_experiment(baseline_record)
    print("\nBaseline summary:")
    print(json.dumps(baseline_record.compact(), indent=2, default=str))

    # Main loop.
    try:
        for iteration in range(1, args.iters + 1):
            exp_class = EXPERIMENT_CLASSES[(iteration - 1) % len(EXPERIMENT_CLASSES)]
            print("\n" + "=" * 72)
            print(f"Iteration {iteration}/{args.iters}: {exp_class['name']}")
            print("=" * 72)

            # Always start from current best.
            shutil.copy2(best_yaml_path, strategy_path)
            best_params = StrategyYaml.extract_params(best_yaml_path.read_text(encoding="utf-8", errors="replace"))

            prompt = build_prompt(iteration, exp_class, best_params, best_result, records, tried_hashes, args.min_trades)
            prompt_path = run_root / "prompts" / f"prompt_{iteration}.txt"
            raw_path = run_root / "raw_model" / f"ai_raw_{iteration}.txt"
            patch_path = run_root / "patches" / f"patch_{iteration}.json"
            prompt_path.write_text(prompt, encoding="utf-8")

            patch: Optional[Dict[str, Any]] = None
            raw = ""
            for attempt in range(1, args.ai_retries + 1):
                try:
                    log(f"Calling Ollama for {exp_class['name']} proposal, attempt {attempt}...")
                    raw = ollama.generate(prompt)
                    raw_path.write_text(raw, encoding="utf-8", errors="replace")
                    obj = extract_json_object(raw)
                    patch = validate_model_patch(obj, exp_class)
                    # Pre-check duplicate vector.
                    base_yaml = best_yaml_path.read_text(encoding="utf-8", errors="replace")
                    proposed_yaml = StrategyYaml.apply_changes(base_yaml, patch["changes"])
                    ph = canonical_params_hash(StrategyYaml.extract_params(proposed_yaml))
                    if ph in tried_hashes:
                        log(f"Model proposed duplicate parameter hash {ph}; retrying/fallback.")
                        patch = None
                        continue
                    break
                except Exception as exc:
                    log(f"AI proposal invalid: {exc}")
                    patch = None

            if patch is None:
                log("Using deterministic fallback candidates.")
                for candidate in fallback_candidates(exp_class):
                    try:
                        candidate = validate_model_patch(candidate, exp_class)
                        base_yaml = best_yaml_path.read_text(encoding="utf-8", errors="replace")
                        proposed_yaml = StrategyYaml.apply_changes(base_yaml, candidate["changes"])
                        ph = canonical_params_hash(StrategyYaml.extract_params(proposed_yaml))
                        if ph not in tried_hashes:
                            patch = candidate
                            break
                    except Exception:
                        continue

            if patch is None:
                print("No non-duplicate candidate available for this class; skipping iteration.")
                continue

            patch_path.write_text(json.dumps(patch, indent=2), encoding="utf-8")
            print("Patch:")
            print(json.dumps(patch, indent=2))

            try:
                record, _ = evaluate_candidate(
                    iteration,
                    exp_class["name"],
                    str(patch.get("hypothesis", "")),
                    dict(patch["changes"]),
                    "model" if raw else "fallback",
                )
                record_experiment(record)
            except Exception as exc:
                print(f"Candidate failed/skipped: {exc}")
                shutil.copy2(best_yaml_path, strategy_path)
                runner.stop_app()
                continue

            print("\nRecent leaderboard:")
            try:
                print("".join(leaderboard_path.read_text(encoding="utf-8").splitlines(True)[-8:]))
            except Exception:
                pass
    except KeyboardInterrupt:
        print("\nInterrupted. Restoring best YAML and stopping app.")
    finally:
        runner.stop_app()
        shutil.copy2(best_yaml_path, strategy_path)

    print("\nDone.")
    print(f"Run folder:    {run_root}")
    print(f"Leaderboard:   {leaderboard_path}")
    print(f"Experiments:   {experiments_path}")
    print(f"Best YAML:     {best_yaml_path}")
    print(f"Best restored: {strategy_path}")
    return 0


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="AI/backtest optimizer for voktrader strategy-v2.paper.yml")
    parser.add_argument("--repo", default=os.getcwd(), help="Repo path. Default: current working directory.")
    parser.add_argument("--repo-win", default="", help="Windows repo path for PowerShell, e.g. C:\\repos\\voktrader. Usually auto-detected.")
    parser.add_argument("--strategy-file", default="src/main/resources/strategy-v2.paper.yml")
    parser.add_argument("--model", default=os.environ.get("MODEL", "qwen3-coder:30b"))
    parser.add_argument("--ollama-url", default="http://localhost:11434")
    parser.add_argument("--ollama-timeout", type=int, default=420)
    parser.add_argument("--num-ctx", type=int, default=16384)
    parser.add_argument("--temperature", type=float, default=0.2)
    parser.add_argument("--iters", type=int, default=int(os.environ.get("MAX_ITERS", "20")))
    parser.add_argument("--ai-retries", type=int, default=2)
    parser.add_argument("--min-trades", type=int, default=int(os.environ.get("MIN_TRADES", "5")))
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--server-mode", choices=["auto", "manual", "external"], default=os.environ.get("SERVER_MODE", "auto"))
    parser.add_argument("--strategy-id", default="strategy-v2")
    parser.add_argument("--bot-id", type=int, default=1)
    parser.add_argument("--market-ids", nargs="*", default=DEFAULT_MARKET_IDS)
    parser.add_argument("--run-root", default="", help="Optional run directory relative to repo or absolute path.")
    args = parser.parse_args(argv)

    if args.run_root:
        rr = Path(args.run_root)
        if rr.is_absolute():
            args.run_root = str(rr)
        else:
            args.run_root = str(Path(args.run_root))
    return args


if __name__ == "__main__":
    try:
        sys.exit(run_optimizer(parse_args()))
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
