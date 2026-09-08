package com.mystar.agent

import android.content.Context
import java.io.IOException

/**
 * 골든셋 원본. 원본 파일은 docs/evaluation/set_D0.md 하나이며,
 * 빌드 시 assets로 복사된다(app/build.gradle.kts의 copyEvalSet).
 *
 * `<!-- tasks:start -->`와 `<!-- tasks:end -->` 사이의 번호 목록만 태스크로 읽는다.
 * 목록 순서가 task_index (1-based).
 */
object EvalSet {
    const val ASSET_PATH = "eval/set_D0.md"

    private const val BEGIN_MARKER = "<!-- tasks:start -->"
    private const val END_MARKER = "<!-- tasks:end -->"
    private val ITEM_REGEX = Regex("""^\d+\.\s+(.+)$""")

    fun load(context: Context): List<String> {
        val text = try {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            ServiceStatus.appendLog("골든셋 읽기 실패 ($ASSET_PATH) — ${e.message}")
            return emptyList()
        }
        return parse(text)
    }

    fun parse(markdown: String): List<String> {
        val lines = markdown.lines()
        val begin = lines.indexOfFirst { it.trim() == BEGIN_MARKER }
        val end = lines.indexOfFirst { it.trim() == END_MARKER }
        if (begin < 0 || end <= begin) {
            ServiceStatus.appendLog("골든셋 파싱 실패 — tasks 마커를 찾지 못함")
            return emptyList()
        }
        return lines.subList(begin + 1, end)
            .mapNotNull { line ->
                ITEM_REGEX.find(line.trim())?.groupValues?.get(1)?.trim()
            }
            .filter { it.isNotEmpty() }
    }
}
