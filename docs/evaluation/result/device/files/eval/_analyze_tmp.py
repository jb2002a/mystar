import json
from pathlib import Path

out = Path(r"c:\Users\jb\mystar\docs\evaluation\result\device\files\eval\_analyze_tmp.txt")
files = [
    Path(r"c:\Users\jb\mystar\docs\evaluation\result\device\files\eval\20260914T005952_오전_8시에_알람_좀_맞춰.json"),
    Path(r"c:\Users\jb\mystar\docs\evaluation\result\device\files\eval\20260914T011050_t04a1_익산에서_지금_여는_약국_찾아줘.json"),
    Path(r"c:\Users\jb\mystar\docs\evaluation\result\device\files\eval\20260914T011217_t04a2_익산에서_지금_여는_약국_찾아줘.json"),
]

parts = []
for f in files:
    d = json.loads(f.read_text(encoding="utf-8"))
    parts.append("=" * 90)
    parts.append(f"FILE: {f.name}")
    parts.append(f"task: {d.get('task')}")
    parts.append(f"task_index={d.get('task_index')} attempt={d.get('attempt')}")
    parts.append(f"elapsed_s={d.get('elapsed_s')} rounds={d.get('rounds')} tokens={d.get('tokens_total')} cost={d.get('cost_usd')}")
    rc = d.get("run_config") or {}
    parts.append(f"model={rc.get('model')} app_version={rc.get('app_version')}")
    parts.append(f"hitl_count={d.get('hitl_count')}")
    parts.append(f"finish_summary={d.get('finish_summary')}")
    parts.append(f"final_package={d.get('final_package')}")
    parts.append(f"final_screen:\n{d.get('final_screen')}")
    parts.append("")
    for t in d.get("tools", []):
        args = t.get("args") or {}
        compact = {k: v for k, v in args.items() if k != "reason"}
        parts.append("-" * 70)
        parts.append(
            f"R{t.get('round')} {t.get('name')} ok={t.get('ok')} settle={t.get('settle')} "
            f"llm_ms={t.get('llm_ms')} tool_ms={t.get('tool_ms')}"
        )
        parts.append(f"reason: {t.get('reason')}")
        parts.append(f"args: {compact}")
        parts.append(f"result: {t.get('result')}")
        screen = t.get("screen")
        if screen:
            parts.append("SCREEN:")
            parts.append(screen)
        else:
            parts.append("SCREEN: (none)")
        parts.append("")

out.write_text("\n".join(parts), encoding="utf-8")
print("wrote", out, "chars", out.stat().st_size)
