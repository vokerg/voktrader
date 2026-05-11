from __future__ import annotations

import argparse
import csv
import datetime as dt
import difflib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

from optimizer_profiles import (
    ExperimentClass,
    StrategyProfile,
    canonical_changes,
    canonical_params_hash,
    get_profile,
    list_profiles,
)

VERSION = "python-ollama-ledger-v2"


def now_stamp() -> str:
    return dt.datetime.now().strftime("%Y%m%d_%H%M%S")


def log(message: str) -> None:
    print(f"[{dt.datetime.now().strftime('%H:%M:%S')}] {message}", flush=True)


def result_signature(result: Dict[str, Any]) -> str:
    fields = {
        "tradeCount": result.get("tradeCount", 0),
        "grossPnlUsd": str(result.get("grossPnlUsd", 0)),
        "totalFeeUsd": str(result.get("totalFeeUsd", 0)),
        "finalPnlUsd": str(result.get("finalPnlUsd", 0)),
    }
    payload = json.dumps(fields, sort_keys=True).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()[:12]


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
    SIMPLE_PATCH_SCHEMA = {
        "type": "object",
        "properties": {
            "hypothesis": {"type": "string"},
            "changes": {
                "type": "object",
                "additionalProperties": {
                    "type": ["number", "integer", "string", "boolean"],
                },
            },
        },
        "required": ["hypothesis", "changes"],
    }

    def __init__(
        self,
        base_url: str,
        model: str,
        timeout: int,
        num_ctx: int,
        temperature: float,
        progress_seconds: int,
        *,
        keep_alive: str,
        stream: bool,
        think: bool,
        response_format: str,
        num_predict: int,
        top_k: int,
        top_p: float,
        repeat_penalty: float,
        seed: int,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout = timeout
        self.num_ctx = num_ctx
        self.temperature = temperature
        self.progress_seconds = max(1, progress_seconds)
        self.keep_alive = keep_alive
        self.stream = stream
        self.think = think
        self.response_format = response_format
        self.num_predict = num_predict
        self.top_k = top_k
        self.top_p = top_p
        self.repeat_penalty = repeat_penalty
        self.seed = seed

    def _resolve_format(self) -> Optional[Dict[str, Any]]:
        if self.response_format == "none":
            return None
        if self.response_format == "simple_patch":
            return self.SIMPLE_PATCH_SCHEMA
        raise ValueError(f"Unsupported Ollama response format preset: {self.response_format}")

    def generate(self, prompt: str, raw_path: Optional[Path] = None) -> str:
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": self.stream,
            "think": self.think,
            "keep_alive": self.keep_alive,
            "options": {
                "temperature": self.temperature,
                "num_ctx": self.num_ctx,
                "num_predict": self.num_predict,
                "top_k": self.top_k,
                "top_p": self.top_p,
                "repeat_penalty": self.repeat_penalty,
                "seed": self.seed,
            },
        }
        response_format = self._resolve_format()
        if response_format is not None:
            payload["format"] = response_format
        request = urllib.request.Request(
            f"{self.base_url}/api/generate",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        chunks: List[str] = []
        started = time.time()
        last_progress = started
        log(
            "Ollama request started: "
            f"model={self.model}, num_ctx={self.num_ctx}, num_predict={self.num_predict}, "
            f"temperature={self.temperature}, stream={self.stream}, think={self.think}, "
            f"format={self.response_format}, raw={raw_path or '<memory>'}"
        )
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            if not self.stream:
                obj = json.loads(response.read().decode("utf-8"))
                piece = str(obj.get("response", ""))
                if piece:
                    chunks.append(piece)
                    if raw_path is not None:
                        raw_path.write_text(piece, encoding="utf-8")
                elapsed = int(time.time() - started)
                log(f"Ollama response complete: elapsed={elapsed}s, chars={sum(len(chunk) for chunk in chunks)}")
                return "".join(chunks)

            for line in response:
                if not line.strip():
                    continue
                obj = json.loads(line.decode("utf-8"))
                piece = str(obj.get("response", ""))
                if piece:
                    chunks.append(piece)
                    if raw_path is not None:
                        with open(raw_path, "a", encoding="utf-8", errors="replace") as handle:
                            handle.write(piece)

                now = time.time()
                if now - last_progress >= self.progress_seconds:
                    elapsed = int(now - started)
                    log(f"Ollama still generating: elapsed={elapsed}s, chars={sum(len(chunk) for chunk in chunks)}")
                    last_progress = now

                if obj.get("done"):
                    elapsed = int(time.time() - started)
                    log(f"Ollama response complete: elapsed={elapsed}s, chars={sum(len(chunk) for chunk in chunks)}")
                    break

        return "".join(chunks)


def extract_json_object(text: str) -> Dict[str, Any]:
    start = text.find("{")
    if start < 0:
        raise ValueError("No JSON object found")
    depth = 0
    in_string = False
    escape = False
    end: Optional[int] = None
    for index in range(start, len(text)):
        char = text[index]
        if in_string:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == '"':
                in_string = False
        else:
            if char == '"':
                in_string = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    end = index + 1
                    break
    if end is None:
        raise ValueError("Could not find balanced JSON object")
    obj = json.loads(text[start:end])
    if not isinstance(obj, dict):
        raise ValueError("JSON root is not an object")
    return obj


def validate_model_patch(profile: StrategyProfile, obj: Dict[str, Any], exp_class: ExperimentClass) -> Dict[str, Any]:
    changes_raw = obj.get("changes")
    if not isinstance(changes_raw, dict):
        raise ValueError("JSON must contain object field 'changes'")
    allowed = set(exp_class.allowed_keys)
    invalid_keys = sorted(set(changes_raw) - allowed)
    if invalid_keys:
        raise ValueError(f"patch used keys outside experiment class: {invalid_keys}")
    if len(changes_raw) == 0:
        raise ValueError("patch changes is empty")
    if len(changes_raw) > exp_class.max_changes:
        raise ValueError(f"too many changes: {len(changes_raw)} > {exp_class.max_changes}")

    changes = canonical_changes(profile, changes_raw)
    if not changes:
        raise ValueError("no supported parameter changes")
    if "expiry_min" in changes and "expiry_max" in changes and int(changes["expiry_min"]) >= int(changes["expiry_max"]):
        raise ValueError("expiry_min must be < expiry_max")
    if "mid_min" in changes and "mid_max" in changes and float(changes["mid_min"]) >= float(changes["mid_max"]):
        raise ValueError("mid_min must be < mid_max")

    return {
        "hypothesis": str(obj.get("hypothesis", "model proposal"))[:400],
        "changes": changes,
    }


class BacktestRunner:
    def __init__(self, repo: Path, repo_win: str, port: int, server_mode: str, run_root: Path, reuse_server: bool) -> None:
        self.repo = repo
        self.repo_win = repo_win
        self.port = port
        self.server_mode = server_mode
        self.run_root = run_root
        self.reuse_server = reuse_server
        self.proc: Optional[subprocess.Popen] = None
        self.base_url = f"http://localhost:{port}"
        self.ready = False

    def stop_app(self) -> None:
        self.ready = False
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
        powershell = (
            "$ErrorActionPreference = 'SilentlyContinue'; "
            f"$pidsToKill = @(Get-NetTCPConnection -LocalPort {self.port} | Select-Object -ExpandProperty OwningProcess -Unique); "
            "foreach ($p in $pidsToKill) { if ($p) { Stop-Process -Id $p -Force } }"
        )
        subprocess.run(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", powershell],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def start_app(self, iteration: int) -> None:
        if self.reuse_server and self.ready:
            return
        log_file = self.run_root / f"app_{iteration}.log"
        if self.server_mode == "manual":
            print("\nManual server mode:")
            print("  1. Stop the currently running Spring Boot server.")
            print("  2. Start it again from PowerShell:")
            print(f"       cd {self.repo_win}")
            print("       ./mvnw spring-boot:run")
            input(f"Press Enter when server is ready for optimizer session starting at iteration {iteration}...")
            self.wait_ready(log_file)
            self.ready = True
            return
        if self.server_mode == "external":
            self.wait_ready(log_file)
            self.ready = True
            return

        self.stop_app()
        log(f"Starting Spring Boot for iteration {iteration}...")
        log(f"App log: {log_file}")
        command = f"Set-Location -LiteralPath '{self.repo_win}'; & .\\mvnw.cmd spring-boot:run"
        output = open(log_file, "w", encoding="utf-8", errors="replace")
        self.proc = subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
            cwd=str(self.repo),
            stdout=output,
            stderr=subprocess.STDOUT,
        )
        self.wait_ready(log_file)
        self.ready = True

    def wait_ready(self, log_file: Path, timeout_seconds: int = 240) -> None:
        deadline = time.time() + timeout_seconds
        while time.time() < deadline:
            if self._http_status(f"{self.base_url}/v3/api-docs") is not None:
                log("App ready.")
                time.sleep(2)
                return
            if self.server_mode == "auto" and self.proc and self.proc.poll() is not None:
                raise RuntimeError(f"Spring Boot exited early. Log tail:\n{self._tail(log_file)}")
            time.sleep(2)
        raise TimeoutError(f"Timed out waiting for app. Log tail:\n{self._tail(log_file)}")

    def _http_status(self, url: str) -> Optional[int]:
        try:
            request = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(request, timeout=3) as response:
                return int(response.status)
        except Exception:
            return None

    def _tail(self, path: Path, lines: int = 120) -> str:
        if not path.exists():
            return ""
        text = path.read_text(encoding="utf-8", errors="replace")
        return "\n".join(text.splitlines()[-lines:])

    def run_backtest(
        self,
        iteration: int,
        strategy_id: str,
        market_ids: List[str],
        bot_id: int,
        strategy_yaml_override: Optional[str] = None,
    ) -> Dict[str, Any]:
        body: Dict[str, Any] = {"strategyId": strategy_id, "marketIds": market_ids, "botId": bot_id}
        if strategy_yaml_override is not None:
            body["strategyYamlOverride"] = strategy_yaml_override
        request_path = self.run_root / f"request_{iteration}.json"
        result_path = self.run_root / f"result_{iteration}.json"
        request_path.write_text(json.dumps(body, indent=2), encoding="utf-8")

        request = urllib.request.Request(
            f"{self.base_url}/api/backtests",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        log("Posting backtest request...")
        with urllib.request.urlopen(request, timeout=600) as response:
            raw = response.read().decode("utf-8")
        result_path.write_text(raw, encoding="utf-8")
        return json.loads(raw)


def repo_to_windows_path(repo: Path) -> str:
    path = str(repo.resolve())
    if re.match(r"^[A-Za-z]:", path):
        return path
    cygpath = shutil.which("cygpath")
    if cygpath:
        try:
            converted = subprocess.check_output([cygpath, "-w", path], text=True).strip()
            if converted:
                return converted
        except Exception:
            pass
    if path.startswith("/c/"):
        return "C:\\" + path[3:].replace("/", "\\")
    return path


def make_diff(before: str, after: str, fromfile: str, tofile: str) -> str:
    return "".join(
        difflib.unified_diff(
            before.splitlines(keepends=True),
            after.splitlines(keepends=True),
            fromfile=fromfile,
            tofile=tofile,
        )
    )


def summarize_experiments(records: List[ExperimentRecord], limit: int = 30) -> str:
    if not records:
        return "[]"
    return json.dumps([record.compact() for record in records[-limit:]], indent=2, default=str)


def build_prompt(
    profile: StrategyProfile,
    iteration: int,
    exp_class: ExperimentClass,
    best_params: Dict[str, Any],
    best_result: Dict[str, Any],
    records: List[ExperimentRecord],
    min_trades: int,
) -> str:
    specs = {
        key: {
            "lo": profile.param_specs[key].lo,
            "hi": profile.param_specs[key].hi,
            "type": profile.param_specs[key].value_type,
            "dec": profile.param_specs[key].decimals,
        }
        for key in exp_class.allowed_keys
    }
    duplicate_note = ""
    if records:
        signature_counts: Dict[str, int] = {}
        for record in records:
            signature_counts[record.result_signature] = signature_counts.get(record.result_signature, 0) + 1
        repeated = [signature for signature, count in signature_counts.items() if count > 1]
        if repeated:
            duplicate_note = f"\nSome different patches produced identical backtest behavior signatures: {repeated}. Avoid their neighborhoods."

    return f"""Return exactly one JSON object. No markdown. No explanation.

You are suggesting one experiment for {profile.prompt_target}.
Python will validate your JSON, edit only the configured strategy YAML, run the backtest, and accept/reject by measured result.

JSON shape:
{{
  "hypothesis": "one short sentence",
  "changes": {{
    "some_allowed_key": 0.123
  }}
}}

Experiment {iteration}: {exp_class.name}
Profile: {profile.name}
Intent: {exp_class.intent}
Allowed keys for this experiment only: {list(exp_class.allowed_keys)}
Max changed keys: {exp_class.max_changes}
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
{summarize_experiments(records, limit=40)}

Recent rejected/bad experiments:
{json.dumps([record.compact() for record in records if not record.accepted][-10:], indent=2, default=str)}
{duplicate_note}

Return only the JSON object.
"""


def write_jsonl(path: Path, obj: Dict[str, Any]) -> None:
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(obj, ensure_ascii=False, default=str) + "\n")


def append_csv(path: Path, row: Dict[str, Any]) -> None:
    exists = path.exists()
    fieldnames = [
        "iteration", "experiment_type", "score", "finalPnlUsd", "grossPnlUsd", "totalFeeUsd", "netRoiPct",
        "tradeCount", "closedTradeCount", "fillRate", "runId", "accepted", "notes", "param_hash", "changes",
    ]
    with open(path, "a", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        if not exists:
            writer.writeheader()
        writer.writerow({key: row.get(key, "") for key in fieldnames})


def run_optimizer(args: argparse.Namespace) -> int:
    profile = get_profile(args.profile)
    repo = Path(args.repo).resolve()
    strategy_file = args.strategy_file or profile.strategy_file
    strategy_path = repo / strategy_file
    if not strategy_path.exists():
        raise FileNotFoundError(f"Missing strategy file: {strategy_path}")

    strategy_id = args.strategy_id or profile.strategy_id
    use_runtime_override = bool(args.use_strategy_override and profile.supports_runtime_override and strategy_id == "strategy-v2")
    repo_win = args.repo_win or repo_to_windows_path(repo)
    run_root = Path(args.run_root) if args.run_root else repo / "runs" / profile.run_slug / now_stamp()
    if not run_root.is_absolute():
        run_root = repo / run_root
    run_root.mkdir(parents=True, exist_ok=True)
    for subdir in ["prompts", "raw_model", "patches", "proposed", "diffs", "results"]:
        (run_root / subdir).mkdir(exist_ok=True)

    exclude = repo / ".git" / "info" / "exclude"
    if exclude.parent.exists():
        exclude.touch(exist_ok=True)
        existing = exclude.read_text(encoding="utf-8", errors="replace").splitlines()
        for line in ["runs/", Path(__file__).name, "optimizer_profiles.py", "voktrader_optimizer.py"]:
            if line not in existing:
                with open(exclude, "a", encoding="utf-8") as handle:
                    handle.write(f"\n{line}\n")

    best_yaml_path = run_root / f"best.{strategy_path.name}"
    shutil.copy2(strategy_path, best_yaml_path)

    leaderboard_path = run_root / "leaderboard.csv"
    experiments_path = run_root / "experiments.jsonl"
    tried_hashes_path = run_root / "tried_hashes.json"

    runner = BacktestRunner(repo, repo_win, args.port, args.server_mode, run_root, reuse_server=use_runtime_override)
    ollama = OllamaClient(
        args.ollama_url,
        args.model,
        args.ollama_timeout,
        args.num_ctx,
        args.temperature,
        args.ollama_progress_seconds,
        keep_alive=args.ollama_keep_alive,
        stream=args.ollama_stream,
        think=args.ollama_think,
        response_format=args.ollama_format,
        num_predict=args.num_predict,
        top_k=args.top_k,
        top_p=args.top_p,
        repeat_penalty=args.repeat_penalty,
        seed=args.seed,
    )

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
        proposed_yaml = profile.adapter.apply_changes(base_yaml, changes, profile)
        params = profile.adapter.extract_params(proposed_yaml, profile)
        param_hash = canonical_params_hash(profile, params)
        if param_hash in tried_hashes:
            raise RuntimeError(f"duplicate parameter vector skipped: {param_hash}")

        proposed_path = run_root / "proposed" / f"proposed_{iteration}.yml"
        proposed_path.write_text(proposed_yaml, encoding="utf-8")
        diff = make_diff(base_yaml, proposed_yaml, best_yaml_path.name, strategy_file)
        (run_root / "diffs" / f"diff_{iteration}.patch").write_text(diff, encoding="utf-8")

        print("\nApplied changes:")
        print(json.dumps({"hypothesis": hypothesis, "changes": changes, "param_hash": param_hash}, indent=2))
        print("\nDiff:")
        print(diff if diff else "<empty diff>")
        if not diff:
            raise RuntimeError("empty diff skipped")

        if not use_runtime_override:
            strategy_path.write_text(proposed_yaml, encoding="utf-8")
        runner.start_app(iteration)
        result = runner.run_backtest(
            iteration,
            strategy_id,
            args.market_ids,
            args.bot_id,
            strategy_yaml_override=proposed_yaml if use_runtime_override else None,
        )

        (run_root / "results" / f"result_{iteration}.json").write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
        score = score_result(result, args.min_trades)
        accepted = score > best_score
        record = ExperimentRecord(
            iteration=iteration,
            experiment_type=exp_type,
            hypothesis=hypothesis,
            changes=canonical_changes(profile, changes),
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
            if not use_runtime_override:
                strategy_path.write_text(best_yaml_path.read_text(encoding="utf-8", errors="replace"), encoding="utf-8")
            print(f"Rejected. score={score:.8f}, best={best_score:.8f}")
        return record, param_hash

    print()
    print(f"Version:       {VERSION}")
    print(f"Profile:       {profile.name}")
    print(f"Repo:          {repo}")
    print(f"Repo win path: {repo_win}")
    print(f"Strategy:      {strategy_path}")
    print(f"Strategy ID:   {strategy_id}")
    print(f"Run folder:    {run_root}")
    print(f"Model:         {args.model}")
    print(f"Warm server:   {'on' if use_runtime_override else 'off'}")
    print(f"Ollama stream: {'on' if args.ollama_stream else 'off'}")
    print(f"Ollama think:  {'on' if args.ollama_think else 'off'}")
    print(f"Ollama format: {args.ollama_format}")
    print(f"Server mode:   {args.server_mode}")
    print()

    log("Running baseline...")
    baseline_yaml = strategy_path.read_text(encoding="utf-8", errors="replace")
    baseline_params = profile.adapter.extract_params(baseline_yaml, profile)
    baseline_hash = canonical_params_hash(profile, baseline_params)
    runner.start_app(0)
    best_result = runner.run_backtest(
        0,
        strategy_id,
        args.market_ids,
        args.bot_id,
        strategy_yaml_override=baseline_yaml if use_runtime_override else None,
    )
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

    try:
        for iteration in range(1, args.iters + 1):
            exp_class = profile.experiments[(iteration - 1) % len(profile.experiments)]
            print("\n" + "=" * 72)
            print(f"Iteration {iteration}/{args.iters}: {exp_class.name}")
            print("=" * 72)

            if not use_runtime_override:
                shutil.copy2(best_yaml_path, strategy_path)
            best_params = profile.adapter.extract_params(best_yaml_path.read_text(encoding="utf-8", errors="replace"), profile)
            prompt = build_prompt(profile, iteration, exp_class, best_params, best_result, records, args.min_trades)

            prompt_path = run_root / "prompts" / f"prompt_{iteration}.txt"
            raw_path = run_root / "raw_model" / f"ai_raw_{iteration}.txt"
            patch_path = run_root / "patches" / f"patch_{iteration}.json"
            prompt_path.write_text(prompt, encoding="utf-8")

            patch: Optional[Dict[str, Any]] = None
            raw = ""
            for attempt in range(1, args.ai_retries + 1):
                try:
                    log(f"Calling Ollama for {exp_class.name} proposal, attempt {attempt}...")
                    raw_path.write_text("", encoding="utf-8")
                    raw = ollama.generate(prompt, raw_path)
                    patch = validate_model_patch(profile, extract_json_object(raw), exp_class)
                    base_yaml = best_yaml_path.read_text(encoding="utf-8", errors="replace")
                    proposed_yaml = profile.adapter.apply_changes(base_yaml, patch["changes"], profile)
                    param_hash = canonical_params_hash(profile, profile.adapter.extract_params(proposed_yaml, profile))
                    if param_hash in tried_hashes:
                        log(f"Model proposed duplicate parameter hash {param_hash}; retrying/fallback.")
                        patch = None
                        continue
                    break
                except Exception as exc:
                    log(f"AI proposal invalid: {exc}")
                    patch = None

            if patch is None:
                log("Using deterministic fallback candidates.")
                for candidate in profile.fallback_candidates.get(exp_class.name, []):
                    try:
                        patch = validate_model_patch(profile, candidate, exp_class)
                        base_yaml = best_yaml_path.read_text(encoding="utf-8", errors="replace")
                        proposed_yaml = profile.adapter.apply_changes(base_yaml, patch["changes"], profile)
                        param_hash = canonical_params_hash(profile, profile.adapter.extract_params(proposed_yaml, profile))
                        if param_hash not in tried_hashes:
                            break
                        patch = None
                    except Exception:
                        patch = None

            if patch is None:
                print("No non-duplicate candidate available for this class; skipping iteration.")
                continue

            patch_path.write_text(json.dumps(patch, indent=2), encoding="utf-8")
            print("Patch:")
            print(json.dumps(patch, indent=2))

            try:
                record, _ = evaluate_candidate(
                    iteration,
                    exp_class.name,
                    str(patch.get("hypothesis", "")),
                    dict(patch["changes"]),
                    "model" if raw else "fallback",
                )
                record_experiment(record)
            except Exception as exc:
                print(f"Candidate failed/skipped: {exc}")
                if not use_runtime_override:
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
        if not use_runtime_override:
            shutil.copy2(best_yaml_path, strategy_path)

    print("\nDone.")
    print(f"Run folder:    {run_root}")
    print(f"Leaderboard:   {leaderboard_path}")
    print(f"Experiments:   {experiments_path}")
    print(f"Best YAML:     {best_yaml_path}")
    print(f"Best restored: {strategy_path}")
    return 0


def parse_market_ids_env(value: str) -> List[str]:
    return [part for part in re.split(r"[\s,]+", value.strip()) if part]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="AI/backtest optimizer with reusable engine and strategy profiles.")
    parser.add_argument("--repo", default=os.getcwd(), help="Repo path. Default: current working directory.")
    parser.add_argument("--repo-win", default="", help="Windows repo path for PowerShell, e.g. C:\\repos\\voktrader. Usually auto-detected.")
    parser.add_argument("--profile", default=os.environ.get("PROFILE", "strategy-v2-paper"), help="Strategy profile name.")
    parser.add_argument("--list-profiles", action="store_true", help="Print available strategy profiles and exit.")
    parser.add_argument("--strategy-file", default=os.environ.get("STRATEGY_FILE", ""), help="Override strategy YAML relative to repo.")
    parser.add_argument("--model", default=os.environ.get("MODEL"), help="Ollama model name. Runner scripts provide OS-specific defaults.")
    parser.add_argument("--ollama-url", default="http://localhost:11434")
    parser.add_argument("--ollama-timeout", type=int, default=420)
    parser.add_argument("--ollama-progress-seconds", type=int, default=15)
    parser.add_argument("--ollama-keep-alive", default=os.environ.get("OLLAMA_KEEP_ALIVE", "30m"))
    parser.add_argument("--ollama-stream", dest="ollama_stream", action="store_true")
    parser.add_argument("--no-ollama-stream", dest="ollama_stream", action="store_false")
    parser.set_defaults(ollama_stream=os.environ.get("OLLAMA_STREAM", "true").lower() == "true")
    parser.add_argument("--ollama-think", dest="ollama_think", action="store_true")
    parser.add_argument("--no-ollama-think", dest="ollama_think", action="store_false")
    parser.set_defaults(ollama_think=os.environ.get("OLLAMA_THINK", "true").lower() == "true")
    parser.add_argument("--ollama-format", choices=["none", "simple_patch"], default=os.environ.get("OLLAMA_FORMAT", "none"))
    parser.add_argument("--num-ctx", type=int, default=16384)
    parser.add_argument("--num-predict", type=int, default=int(os.environ.get("NUM_PREDICT", "256")))
    parser.add_argument("--temperature", type=float, default=0.2)
    parser.add_argument("--top-k", type=int, default=int(os.environ.get("TOP_K", "40")))
    parser.add_argument("--top-p", type=float, default=float(os.environ.get("TOP_P", "0.9")))
    parser.add_argument("--repeat-penalty", type=float, default=float(os.environ.get("REPEAT_PENALTY", "1.1")))
    parser.add_argument("--seed", type=int, default=int(os.environ.get("SEED", "0")))
    parser.add_argument("--iters", type=int, default=int(os.environ.get("MAX_ITERS", "20")))
    parser.add_argument("--ai-retries", type=int, default=2)
    parser.add_argument("--min-trades", type=int, default=int(os.environ.get("MIN_TRADES", "5")))
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--server-mode", choices=["auto", "manual", "external"], default=os.environ.get("SERVER_MODE", "auto"))
    parser.add_argument("--strategy-id", default=os.environ.get("STRATEGY_ID", ""), help="Override backtest strategy id.")
    parser.add_argument("--bot-id", type=int, default=1)
    parser.add_argument("--market-ids", nargs="*", default=None, help="Polymarket market IDs. Defaults come from env or profile.")
    parser.add_argument("--run-root", default="", help="Optional run directory relative to repo or absolute path.")
    parser.add_argument("--use-strategy-override", dest="use_strategy_override", action="store_true")
    parser.add_argument("--no-strategy-override", dest="use_strategy_override", action="store_false")
    parser.set_defaults(use_strategy_override=os.environ.get("USE_STRATEGY_OVERRIDE", "true").lower() == "true")
    return parser


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.list_profiles:
        return args
    if not args.model:
        parser.error("--model is required unless MODEL is set")

    try:
        profile = get_profile(args.profile)
    except KeyError as exc:
        parser.error(str(exc))

    if args.market_ids is None:
        args.market_ids = parse_market_ids_env(os.environ.get("MARKET_IDS", ""))
    if not args.market_ids:
        args.market_ids = list(profile.default_market_ids)
    if not args.market_ids:
        parser.error("--market-ids is required unless MARKET_IDS is set or the profile provides defaults")
    return args


def print_profiles() -> None:
    print("Available profiles:")
    for profile in list_profiles():
        print(f"- {profile.name}: {profile.description}")
        print(f"  strategy_file={profile.strategy_file}")
        print(f"  strategy_id={profile.strategy_id}")


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    if args.list_profiles:
        print_profiles()
        return 0
    return run_optimizer(args)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
