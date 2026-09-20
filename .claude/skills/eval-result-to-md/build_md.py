"""
초기AB테스트 eval json 결과들을 docs/evaluation/result/device/files/eval/양식.txt 형식의
md 리포트로 변환한다.

사용법:
  python build_md.py <model_dir_name> [--queue-id ID] [--out PATH]

예:
  python build_md.py gemini-3-flash-preview
"""
import argparse
import hashlib
import json
import glob
import os
import re
import urllib.error
import urllib.request
from collections import Counter


def round_time_suffix(t):
    # 구버전 기록은 llm_ms/tool_ms 필드 자체가 없음 -> 표시하지 않음 (0.0s로 오인 방지)
    if "llm_ms" not in t and "tool_ms" not in t:
        return ""
    round_s = (t.get("llm_ms", 0) + t.get("tool_ms", 0)) / 1000
    return f" ({round_s:.1f}s)"


# reason/summary는 줄 본문·판정 카드에 있으므로 args에서 뺀다.
_ARG_KEYS = ("package", "node_id", "text", "query", "direction", "question")


def format_tool_args(t):
    args = t.get("args") or {}
    parts = []
    for key in _ARG_KEYS:
        val = args.get(key)
        if val is None or val == "":
            continue
        if key in ("text", "query"):
            parts.append(f'"{val}"')
        else:
            parts.append(f"`{val}`")
    return (" " + " ".join(parts)) if parts else ""


def fence(text):
    return f"```\n{text.rstrip()}\n```"


def agent_steps(d):
    """에이전트 스텝 = tools[] 길이 (finish 포함)."""
    return len(d.get("tools") or [])


def efficiency_metrics(human, agent):
    """성공한 런에서만 호출. RRR = 사람 / 에이전트, 초과 스텝 = 에이전트 − 사람."""
    if not human or agent <= 0:
        return None
    return {
        "rrr": human / agent,
        "excess": agent - human,
        "human": human,
        "agent": agent,
    }


def format_rrr(metrics):
    if not metrics:
        return "—"
    return f"{metrics['rrr']:.2f}"


def format_excess(metrics):
    if not metrics:
        return "—"
    n = metrics["excess"]
    return f"{n:+d}" if n != 0 else "0"


def parse_d0(path):
    """set_D0.md 성공 기준 표 → {task_index: {kind, criterion, human_steps}}."""
    with open(path, encoding="utf-8") as fp:
        text = fp.read()
    specs = {}
    for line in text.splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 4 or not cells[0].isdigit():
            continue
        try:
            steps = int(cells[3])
        except ValueError:
            continue
        specs[int(cells[0])] = {
            "kind": cells[1],
            "criterion": cells[2],
            "human_steps": steps,
        }
    return specs


def confirm_calls(d):
    out = []
    for t in d.get("tools") or []:
        if t.get("name") != "ask_user":
            continue
        args = t.get("args") or {}
        if args.get("kind") != "confirm":
            continue
        out.append({
            "question": args.get("question") or "",
            "result": t.get("result") or "",
        })
    return out


def judge_payload(d, spec):
    screen = d.get("final_screen") or ""
    if len(screen) > 6000:
        screen = screen[:6000] + "\n…(truncated)"
    return {
        "task": d.get("task") or "",
        "kind": spec["kind"],
        "criterion": spec["criterion"],
        "finish_summary": d.get("finish_summary") or "",
        "final_package": d.get("final_package") or "",
        "final_screen": screen,
        "ask_user_confirm": confirm_calls(d),
    }


def build_judge_prompt(payload):
    confirms = payload["ask_user_confirm"]
    if confirms:
        confirm_s = "\n".join(
            f"- question: {c['question']}\n  result: {c['result']}" for c in confirms
        )
    else:
        confirm_s = "(없음)"
    return f"""너는 Android 에이전트 런의 성공 여부를 판정한다.

태스크: {payload['task']}
유형: {payload['kind']}
성공 기준: {payload['criterion']}

아래 근거만 보고, 성공 기준에 적힌 내용만으로 pass 또는 fail을 판정하라.
기준 밖의 관대한 해석은 하지 마라.
finish_summary의 주장("보냈습니다")을 행동형·이동형에서 그대로 믿지 마라. 화면·패키지로 확인하라.
정보형은 답이 finish_summary에 있다.
성공 기준이 confirm을 요구하면 ask_user(confirm)이 있어야 한다.

finish_summary: {payload['finish_summary'] or '(없음)'}
final_package: {payload['final_package'] or '(없음)'}
final_screen:
{payload['final_screen'] or '(없음)'}

ask_user(confirm) 호출:
{confirm_s}

JSON만 출력하라.
{{"verdict":"pass 또는 fail","evidence":"한 줄"}}"""


def parse_verdict_json(text):
    text = (text or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    obj = json.loads(text)
    verdict = str(obj.get("verdict") or "").strip().lower()
    if verdict not in ("pass", "fail"):
        raise ValueError(f"verdict가 pass/fail이 아님: {verdict!r}")
    return {"verdict": verdict, "evidence": str(obj.get("evidence") or "").strip()}


def load_local_properties(path):
    props = {}
    if not os.path.isfile(path):
        return props
    with open(path, encoding="utf-8") as fp:
        for line in fp:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            props[key.strip()] = val.strip()
    return props


def _http_json(url, body, headers, timeout=60):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        err = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"LLM HTTP {e.code}: {err[:500]}") from e
    return json.loads(raw)


def llm_complete_json(prompt, props):
    provider = (props.get("LLM_PROVIDER") or "openai_compat").strip()
    api_key = (props.get("LLM_API_KEY") or "").strip()
    base_url = (props.get("LLM_BASE_URL") or "").strip().rstrip("/")
    model = (props.get("LLM_MODEL") or "").strip()
    if not api_key or not base_url or not model:
        raise RuntimeError("local.properties에 LLM_API_KEY / LLM_BASE_URL / LLM_MODEL 필요")

    if provider == "gemini_native":
        trimmed = base_url.rstrip("/")
        model_path = f"models/{model}:generateContent"
        if trimmed.endswith(":generateContent"):
            url = trimmed
        elif trimmed.endswith("/" + model_path):
            url = trimmed
        else:
            url = f"{trimmed}/{model_path}"
        body = {
            "contents": [{"role": "user", "parts": [{"text": prompt}]}],
            "generationConfig": {
                "temperature": 0,
                "responseMimeType": "application/json",
            },
        }
        resp = _http_json(
            url,
            body,
            {"Content-Type": "application/json", "x-goog-api-key": api_key},
        )
        parts = (
            ((resp.get("candidates") or [{}])[0].get("content") or {}).get("parts") or []
        )
        text = "".join(p.get("text") or "" for p in parts)
        return parse_verdict_json(text)

    url = base_url if base_url.endswith("/chat/completions") else f"{base_url}/chat/completions"
    body = {
        "model": model,
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": "JSON만 출력한다."},
            {"role": "user", "content": prompt},
        ],
    }
    resp = _http_json(
        url,
        body,
        {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
    )
    text = ((resp.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
    return parse_verdict_json(text)


def load_judge_cache(path):
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8") as fp:
        return json.load(fp)


def save_judge_cache(path, cache):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(cache, fp, ensure_ascii=False, indent=2)


def judge_run(d, spec, props, cache):
    payload = judge_payload(d, spec)
    key = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()
    if key in cache:
        return cache[key], True
    result = llm_complete_json(build_judge_prompt(payload), props)
    cache[key] = result
    return result, False


def format_judgment_card(d, verdict=None, metrics=None):
    summary = d.get("finish_summary")
    summary_s = f'"{summary}"' if summary else "(없음)"
    pkg = d.get("final_package")
    pkg_s = f"`{pkg}`" if pkg else "(없음)"
    screen = d.get("final_screen")
    lines = []
    if verdict:
        lines.append(f"- **verdict:** {verdict['verdict']}")
        lines.append(f"- **verdict_evidence:** {verdict.get('evidence') or '(없음)'}")
        if verdict["verdict"] == "pass" and metrics:
            lines.append(
                f"- **RRR:** {format_rrr(metrics)} "
                f"(사람 {metrics['human']} / 에이전트 {metrics['agent']}, "
                f"초과 {format_excess(metrics)})"
            )
        else:
            lines.append("- **RRR:** —")
    lines.append(f"- **finish_summary:** {summary_s}")
    lines.append(f"- **final_package:** {pkg_s}")
    if screen:
        lines.append("- **final_screen:**")
        lines.append(fence(screen))
    else:
        lines.append("- **final_screen:** (없음)")
    return "\n".join(lines)

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
BASE_DIR = os.path.join(
    REPO_ROOT, "docs", "evaluation", "result", "device", "files", "기록용", "초기AB테스트"
)
OUT_DIR = os.path.join(REPO_ROOT, "docs", "evaluation", "result", "device", "files", "eval")
DEFAULT_GOLDEN = os.path.join(REPO_ROOT, "docs", "evaluation", "set_D0.md")
DEFAULT_CACHE = os.path.join(REPO_ROOT, "docs", "evaluation", ".judge_cache.json")


def tokens_total(d):
    # 구버전 기록은 tokens_total이 없고 tokens_in/tokens_out만 있음
    return d.get("tokens_total", d.get("tokens_in", 0) + d.get("tokens_out", 0))


def join_attempts(attempts, fmt):
    return " / ".join(f"a{d['attempt']}: {fmt(d)}" for d in attempts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model", nargs="?", default=None, help="기록용/초기AB테스트/ 하위 모델 폴더명 (예: gemini-3-flash-preview)")
    ap.add_argument("--src-dir", default=None, help="json이 있는 폴더 경로 (--src-dir 사용 시 model 생략 가능)")
    ap.add_argument("--title", default=None, help="리포트 제목. 생략 시 model 또는 src-dir 폴더명")
    ap.add_argument("--queue-id", default=None, help="정식 배치로 취급할 queue_id. 생략 시 가장 많이 등장하는 queue_id를 자동 선택")
    ap.add_argument("--out", default=None, help="출력 md 경로. 생략 시 eval/<model>.md")
    ap.add_argument("--golden", default=DEFAULT_GOLDEN, help="성공 기준·사람 최단 스텝 md (기본: docs/evaluation/set_D0.md)")
    ap.add_argument("--no-judge", action="store_true", help="LLM 성공 판정을 건너뛴다. RRR은 모든 런에 참고용으로만 표시")
    ap.add_argument("--judge-cache", default=DEFAULT_CACHE, help="judge 결과 캐시 JSON")
    args = ap.parse_args()

    if args.src_dir:
        src_dir = os.path.abspath(args.src_dir)
        if not os.path.isdir(src_dir):
            raise SystemExit(f"소스 폴더를 찾을 수 없음: {src_dir}")
        report_name = args.title or os.path.basename(src_dir.rstrip(os.sep))
    elif args.model:
        src_dir = os.path.join(BASE_DIR, args.model)
        report_name = args.title or args.model
    else:
        raise SystemExit("model 또는 --src-dir 중 하나를 지정하세요.")

    if not args.src_dir and not os.path.isdir(src_dir):
        # 쉘에 괄호/한글이 섞인 폴더명을 직접 넘기기 까다로운 경우를 위한 부분일치 폴백
        # (예: "4o" -> "구버전(4o)")
        candidates = [
            name for name in os.listdir(BASE_DIR)
            if os.path.isdir(os.path.join(BASE_DIR, name)) and args.model.lower() in name.lower()
        ]
        if len(candidates) == 1:
            args.model = candidates[0]
            src_dir = os.path.join(BASE_DIR, args.model)
        elif len(candidates) > 1:
            raise SystemExit(f"'{args.model}'와 부분일치하는 폴더가 여러 개: {candidates}")
        else:
            raise SystemExit(f"모델 폴더를 찾을 수 없음: {src_dir}")

    if not os.path.isfile(args.golden):
        raise SystemExit(f"골든셋 없음: {args.golden}")
    specs = parse_d0(args.golden)
    if not specs:
        raise SystemExit(f"성공 기준 표를 파싱하지 못함: {args.golden}")

    safe_name = re.sub(r"[()]", "", report_name)
    out_path = args.out or os.path.join(OUT_DIR, f"{safe_name}.md")

    files = sorted(glob.glob(os.path.join(src_dir, "*.json")))
    if not files:
        raise SystemExit(f"json 파일 없음: {src_dir}")

    all_data = []
    for f in files:
        with open(f, encoding="utf-8") as fp:
            all_data.append((f, json.load(fp)))

    # queue_id 자동 선택: task_index x attempt 조합이 겹치지 않는 "정식 배치"를 고른다.
    # 지정 안 하면 가장 파일 수가 많은 queue_id를 사용하고, 나머지는 단발 테스트로 간주해 제외.
    if args.queue_id:
        main_queue_id = args.queue_id
    else:
        counts = Counter(d["queue_id"] for _, d in all_data)
        main_queue_id = counts.most_common(1)[0][0]

    tasks = {}
    excluded = []
    for f, d in all_data:
        if d["queue_id"] != main_queue_id:
            excluded.append(os.path.basename(f))
            continue
        tasks.setdefault(d["task_index"], []).append(d)

    for ti in tasks:
        tasks[ti].sort(key=lambda d: d["attempt"])

    props = {}
    cache = {}
    if not args.no_judge:
        props = load_local_properties(os.path.join(REPO_ROOT, "local.properties"))
        cache = load_judge_cache(args.judge_cache)

    missing_spec = []
    judged = 0
    cached_n = 0
    for ti, attempts in tasks.items():
        spec = specs.get(ti)
        if spec is None:
            missing_spec.append(ti)
            continue
        human = spec["human_steps"]
        for d in attempts:
            d["_human_steps"] = human
            d["_agent_steps"] = agent_steps(d)
            if args.no_judge:
                d["_verdict"] = None
                d["_metrics"] = efficiency_metrics(human, d["_agent_steps"])
                continue
            verdict, from_cache = judge_run(d, spec, props, cache)
            d["_verdict"] = verdict
            judged += 1
            if from_cache:
                cached_n += 1
            if verdict["verdict"] == "pass":
                d["_metrics"] = efficiency_metrics(human, d["_agent_steps"])
            else:
                d["_metrics"] = None
            print(
                f"judge t{ti:02d}a{d['attempt']} {verdict['verdict']}"
                f"{' (cache)' if from_cache else ''}"
            )

    if not args.no_judge:
        save_judge_cache(args.judge_cache, cache)

    if missing_spec:
        print("골든셋에 없는 task_index (RRR/판정 생략):", missing_spec)

    pass_runs = [
        d for attempts in tasks.values() for d in attempts
        if (d.get("_verdict") or {}).get("verdict") == "pass"
    ]
    all_runs = [d for attempts in tasks.values() for d in attempts]
    lines = []
    lines.append(f"# {report_name} 결과 정리\n")
    if args.no_judge:
        lines.append("LLM 판정 생략 (`--no-judge`). RRR·초과 스텝은 모든 런에 참고용.")
        lines.append("")
    else:
        n_pass = len(pass_runs)
        n_all = len(all_runs)
        rate = f"{n_pass}/{n_all}" if n_all else "—"
        if pass_runs:
            micro = sum(d["_human_steps"] for d in pass_runs) / sum(d["_agent_steps"] for d in pass_runs)
            excess_mean = sum(d["_metrics"]["excess"] for d in pass_runs) / n_pass
            rrr_s = f"{micro:.2f}"
            excess_s = f"{excess_mean:+.1f}" if excess_mean != 0 else "0"
        else:
            rrr_s = "—"
            excess_s = "—"
        k_vals = []
        k_all = []
        for ti, attempts in tasks.items():
            if specs.get(ti) is None:
                continue
            verdicts = [(d.get("_verdict") or {}).get("verdict") for d in attempts]
            if any(v is None for v in verdicts):
                continue
            k_vals.append(any(v == "pass" for v in verdicts))
            k_all.append(all(v == "pass" for v in verdicts))
        pass_at = f"{sum(k_vals)}/{len(k_vals)}" if k_vals else "—"
        pass_hat = f"{sum(k_all)}/{len(k_all)}" if k_all else "—"
        lines.append(
            f"성공률 {rate} · pass@k {pass_at} · pass^k {pass_hat} · "
            f"RRR(micro, 성공 런) {rrr_s} · 평균 초과 스텝 {excess_s}"
        )
        lines.append("")

    lines.append("| # | Task | 성공 | RRR | 초과 스텝 | 소요시간(s) | 라운드 | 총 토큰/금액$ |")
    lines.append("|---|------|------|-----|----------|------------|--------|--------------|")

    flow_sections = []

    for ti in sorted(tasks.keys()):
        attempts = tasks[ti]
        task_text = attempts[0]["task"]

        def success_cell(d):
            v = d.get("_verdict")
            if not v:
                return "?"
            return v["verdict"]

        def rrr_cell(d):
            if d.get("_verdict") and d["_verdict"]["verdict"] != "pass":
                return "—"
            return format_rrr(d.get("_metrics"))

        def excess_cell(d):
            if d.get("_verdict") and d["_verdict"]["verdict"] != "pass":
                return "—"
            return format_excess(d.get("_metrics"))

        success = join_attempts(attempts, success_cell)
        rrr = join_attempts(attempts, rrr_cell)
        excess = join_attempts(attempts, excess_cell)
        elapsed = join_attempts(attempts, lambda d: d["elapsed_s"])
        rounds = join_attempts(attempts, lambda d: d["rounds"])
        tok_cost = join_attempts(attempts, lambda d: f"{tokens_total(d)}(${d['cost_usd']})")

        lines.append(
            f"| {ti} | {task_text} | {success} | {rrr} | {excess} | {elapsed} | {rounds} | {tok_cost} |"
        )

        flow_lines = [f"### Task {ti} flow — {task_text}"]
        for d in attempts:
            flow_lines.append(
                f"\n**a{d['attempt']}** ({d['elapsed_s']}s, {d['rounds']}라운드)"
            )
            flow_lines.append(format_judgment_card(d, d.get("_verdict"), d.get("_metrics")))
            for t in d["tools"]:
                flow_lines.append(
                    f"{t['round']}. `{t['name']}`{format_tool_args(t)} — "
                    f"{t.get('reason', '')}{round_time_suffix(t)}"
                )
        flow_sections.append("\n".join(flow_lines))

    lines.append("")
    lines.append("---\n")
    lines.append("## flow 상세\n")

    md = "\n".join(lines) + "\n\n" + "\n\n".join(flow_sections) + "\n"

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fp:
        fp.write(md)

    print("done:", out_path)
    print("main_queue_id:", main_queue_id)
    print("tasks:", sorted(tasks.keys()))
    if not args.no_judge:
        print(f"judge: {judged}런 (cache {cached_n})")
    if excluded:
        print("제외된 파일(다른 queue_id):", excluded)


if __name__ == "__main__":
    main()
