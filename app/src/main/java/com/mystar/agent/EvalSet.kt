package com.mystar.agent

import android.content.Context
import java.io.IOException

/**
 * 골든셋 원본. 태스크 목록은 assets의 이 파일 한 곳에만 둔다.
 * 한 줄에 목표 하나, 줄 순서가 task_index. 빈 줄과 '#' 주석은 건너뛴다.
 */
object EvalSet {
    const val ASSET_PATH = "eval/set_D0.txt"

    fun load(context: Context): List<String> {
        return try {
            context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                reader.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            }
        } catch (e: IOException) {
            ServiceStatus.appendLog("골든셋 읽기 실패 ($ASSET_PATH) — ${e.message}")
            emptyList()
        }
    }
}
