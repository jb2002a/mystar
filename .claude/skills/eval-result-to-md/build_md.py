"""
초기AB테스트 eval json 결과들을 docs/evaluation/result/device/files/eval/양식.txt 형식의
md 리포트로 변환한다.

사용법:
  python build_md.py <model_dir_name> [--queue-id ID] [--out PATH]

예:
  python build_md.py gemini-3-flash-preview
"""
import argparse
import json
import glob
import os
import re
from collections import Counter


def round_time_suffix(t):
    # 구버전 기록은 llm_ms/tool_ms 필드 자체가 없음 -> 표시하지 않음 (0.0s로 오인 방지)
    if "llm_ms" not in t and "tool_ms" not in t:
        return ""
    round_s = (t.get("llm_ms", 0) + t.get("tool_ms", 0)) / 1000
    return f" ({round_s:.1f}s)"


# reason은 줄 본문에, finish summary는 판정 카드에 있으므로 args에서 뺀다.
_ARG_KEYS = ("package", "node_id", "text", "query", "direction")


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


def format_judgment_card(d):
    summary = d.get("finish_summary")
    summary_s = f'"{summary}"' if summary else "(없음)"
    pkg = d.get("final_package")
    pkg_s = f"`{pkg}`" if pkg else "(없음)"
    screen = d.get("final_screen")
    lines = [
        f"- **finish_summary:** {summary_s}",
        f"- **final_package:** {pkg_s}",
    ]
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model", nargs="?", default=None, help="기록용/초기AB테스트/ 하위 모델 폴더명 (예: gemini-3-flash-preview)")
    ap.add_argument("--src-dir", default=None, help="json이 있는 폴더 경로 (--src-dir 사용 시 model 생략 가능)")
    ap.add_argument("--title", default=None, help="리포트 제목. 생략 시 model 또는 src-dir 폴더명")
    ap.add_argument("--queue-id", default=None, help="정식 배치로 취급할 queue_id. 생략 시 가장 많이 등장하는 queue_id를 자동 선택")
    ap.add_argument("--out", default=None, help="출력 md 경로. 생략 시 eval/<model>.md")
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

    lines = []
    lines.append(f"# {report_name} 결과 정리\n")
    lines.append("| # | Task | 소요시간(s) | 라운드 | 총 토큰/금액$ |")
    lines.append("|---|------|------------|--------|--------------|")

    flow_sections = []

    for ti in sorted(tasks.keys()):
        attempts = tasks[ti]
        task_text = attempts[0]["task"]

        elapsed = " / ".join(f"a{d['attempt']}: {d['elapsed_s']}" for d in attempts)
        rounds = " / ".join(f"a{d['attempt']}: {d['rounds']}" for d in attempts)
        def tokens_total(d):
            # 구버전 기록은 tokens_total이 없고 tokens_in/tokens_out만 있음
            return d.get("tokens_total", d.get("tokens_in", 0) + d.get("tokens_out", 0))

        tok_cost = " / ".join(
            f"a{d['attempt']}: {tokens_total(d)}(${d['cost_usd']})" for d in attempts
        )

        lines.append(f"| {ti} | {task_text} | {elapsed} | {rounds} | {tok_cost} |")

        flow_lines = [f"### Task {ti} flow — {task_text}"]
        for d in attempts:
            hitl_n = d.get("hitl_count", 0)
            header = f"\n**a{d['attempt']}** ({d['elapsed_s']}s, {d['rounds']}라운드"
            if hitl_n:
                header += f", 🙋 HITL {hitl_n}회"
            header += ")"
            flow_lines.append(header)
            flow_lines.append(format_judgment_card(d))

            tools = d["tools"]
            for idx, t in enumerate(tools):
                reason = t.get("reason", "")
                if t["name"] == "ask_user":
                    # HITL 개입: 질문/종류/답변을 눈에 띄게 표시
                    question = t.get("args", {}).get("question", "")
                    kind = t.get("args", {}).get("kind", "")
                    line = (
                        f"{t['round']}. 🙋 **`ask_user`(HITL)** — {reason}{round_time_suffix(t)}\n"
                        f"    - 질문({kind}): \"{question}\"\n"
                        f"    - 결과: {t.get('result', '')}"
                    )
                    flow_lines.append(line)
                    continue
                line = (
                    f"{t['round']}. `{t['name']}`{format_tool_args(t)} — "
                    f"{reason}{round_time_suffix(t)}"
                )
                ok = t.get("ok", True)
                settle = t.get("settle")
                cur_screen = t.get("screen")
                if not ok:
                    line += f" → ❌ 실패: {t.get('result', '')}"
                    # 실패한 이 액션을 결정할 때 실제로 본 화면(이 tool 자신의 screen)에
                    # 해당 node_id가 있었는지 대조 -> LLM 환각(없는 id) vs 타이밍/화면갱신 이슈 구분
                    node_id = t.get("args", {}).get("node_id")
                    if node_id:
                        if cur_screen is None:
                            line += " [해당 화면 기록 없음 — 판단 불가]"
                        elif f"[{node_id}]" in cur_screen:
                            line += " [해당 화면엔 id 존재 → 타이밍/화면갱신 이슈로 추정]"
                        else:
                            line += " [해당 화면에 id 없음 → LLM이 없는 id를 지어낸 것으로 추정]"
                elif settle not in (None, "matched"):
                    line += f" → ⚠️ {settle}: {t.get('result', '')}"
                flow_lines.append(line)
                # 성공 라운드 화면·thoughts는 넣지 않는다. 실패 라운드만 당시 화면을 붙인다.
                if not ok and cur_screen:
                    flow_lines.append(fence(cur_screen))
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
    if excluded:
        print("제외된 파일(다른 queue_id):", excluded)


if __name__ == "__main__":
    main()
