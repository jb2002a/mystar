package com.mystar.agent.tool

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 런처에 보이는 설치 앱 목록을 "이름 | 패키지" 한 줄씩 반환한다.
 * 라운드 1 LLM 컨텍스트 주입용. 자기 앱(호스트)은 제외한다.
 */
object AppCatalog {

    fun buildCatalog(context: Context): String {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            if (resolveInfos.isEmpty()) {
                return "(설치된 런처 앱 없음)"
            }

            val selfPackage = context.packageName
            val lines = resolveInfos
                .filter { it.activityInfo.packageName != selfPackage }
                .map { info ->
                    val label = info.loadLabel(pm).toString()
                    val pkg = info.activityInfo.packageName
                    "$label | $pkg"
                }
                .distinct()
                .sorted()

            if (lines.isEmpty()) {
                "(설치된 런처 앱 없음)"
            } else {
                lines.joinToString("\n")
            }
        } catch (e: Exception) {
            "(앱 목록 조회 실패: ${e.message})"
        }
    }
}
