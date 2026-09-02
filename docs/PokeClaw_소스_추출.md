# PokeClaw 소스 추출 (Tool + ReAct 코드)

> [PokeClaw_참조맵.md](./PokeClaw_참조맵.md)에서 지목한 파일들의 **실제 코드를 그대로 가져와** 오프라인으로 대조할 수 있게 모아둔 문서. 매번 GitHub을 fetch하지 않고 이 문서만 봐도 로직을 확인할 수 있다.
>
> - **원본 저장소:** `agents-io/PokeClaw` (Apache License 2.0)
> - **추출 시점 기준 커밋:** `main` 브랜치 `936eca3` (2026-09-02 기준 최신). 원본은 계속 바뀌므로, 최신이 필요하면 각 섹션의 raw 링크로 재확인할 것.
> - **주의:** 여기 있는 코드는 프로덕션 코드 그대로다. MVP에 이식할 때는 [참조맵 §6](./PokeClaw_참조맵.md#6-이식-시-반드시-지킬-불변-규칙-참조하되-놓치기-쉬운-것) 원칙대로 **핵심 로직만 자기 코드로 재작성**한다 — 통째로 복사하지 않는다.

---

## 목차

1. [툴 프레임워크 기반 (`tool/*.kt`)](#1-툴-프레임워크-기반-toolkt)
2. [MVP 5개 툴 (`tool/impl/*`)](#2-mvp-5개-툴-toolimpl)
3. [접근성 서비스 핵심 로직 (`service/ClawAccessibilityService.java`)](#3-접근성-서비스-핵심-로직-serviceclawaccessibilityservicejava)
4. [ReAct 루프 (`agent/DefaultAgentService.kt`)](#4-react-루프-agentdefaultagentservicekt)

---

## 1. 툴 프레임워크 기반 (`tool/*.kt`)

모든 툴이 공유하는 뼈대. 개별 툴을 읽기 전에 이 4개부터 본다.

### 1.1 `BaseTool.kt` — 모든 툴의 추상 부모 클래스

역할: 이름·설명·파라미터·`execute()` 시그니처를 강제하고, 파라미터 파싱 헬퍼(`requireString` 등)와 `wait_after` 공통 파라미터, 접근성 서비스 조회(`requireAccessibilityService`)를 제공한다.

원본: [`tool/BaseTool.kt`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/tool/BaseTool.kt)

```kotlin
// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import com.blankj.utilcode.util.ScreenUtils
import io.agents.pokeclaw.service.ClawAccessibilityService

abstract class BaseTool {

    companion object {
        /** Tool description language: true = Chinese, false = English */
        @JvmField
        var useChineseDescription: Boolean = false

        /** Maximum value for the wait_after parameter (milliseconds) */
        private const val MAX_WAIT_AFTER_MS = 10000L

        /**
         * Shared wait_after parameter definition used by all tools.
         * Automatically appended to the end of each tool's parameter list by getParametersWithWaitAfter().
         */
        @JvmStatic
        val WAIT_AFTER_PARAM = ToolParameter(
            "wait_after",
            "integer",
            "Optional: milliseconds to wait after this action completes (e.g. 2000 for page load). Default 0 (no wait).",
            false
        )
    }

    abstract fun getName(): String
    abstract fun getParameters(): List<ToolParameter>
    abstract fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult

    /**
     * Returns the tool parameter list plus the shared wait_after parameter.
     * Used by ToolBridge when registering tool specifications.
     */
    fun getParametersWithWaitAfter(): List<ToolParameter> {
        val params = getParameters().toMutableList()
        // Do not add wait_after to observation tools like wait / finish / get_screen_info
        if (getName() !in listOf("wait", "finish", "get_screen_info", "take_screenshot", "get_installed_apps", "find_node_info", "scroll_to_find", "list_scheduled_tasks", "schedule_task", "cancel_scheduled_task")) {
            params.add(WAIT_AFTER_PARAM)
        }
        return params
    }

    /**
     * Execute the tool and handle wait_after delay.
     * Called by ToolRegistry.executeTool().
     */
    fun executeWithWaitAfter(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        val result = execute(params)
        // Only wait if execution succeeded
        if (result.isSuccess) {
            val waitMs = optionalLong(params, "wait_after", 0)
            if (waitMs in 1..MAX_WAIT_AFTER_MS) {
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return result
    }

    /** English description, subclasses must implement */
    abstract fun getDescriptionEN(): String

    /** Chinese description, subclasses must implement */
    abstract fun getDescriptionCN(): String

    /** Returns description based on language toggle */
    fun getDescription(): String =
        if (useChineseDescription) getDescriptionCN() else getDescriptionEN()

    /** Display name shown to the user; subclasses may override */
    open fun getDisplayName(): String = getName()

    // === Parameter helpers ===

    protected fun requireString(params: @JvmSuppressWildcards Map<String, Any>, key: String): String {
        return params[key]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: $key")
    }

    protected fun requireInt(params: @JvmSuppressWildcards Map<String, Any>, key: String): Int {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
    }

    protected fun requireLong(params: @JvmSuppressWildcards Map<String, Any>, key: String): Long {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLong()
        }
    }

    protected fun optionalInt(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Int): Int {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
    }

    protected fun optionalLong(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Long): Long {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLong()
        }
    }

    protected fun optionalString(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: String): String {
        return params[key]?.toString() ?: defaultValue
    }

    protected fun optionalBoolean(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Boolean): Boolean {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().toBoolean()
        }
    }

    /**
     * Accessibility can briefly disconnect/rebind while Android reshuffles the service.
     * Tools should tolerate that short gap instead of failing immediately.
     */
    @JvmOverloads
    protected fun requireAccessibilityService(timeoutMs: Long = 20_000L): ClawAccessibilityService? {
        return ClawAccessibilityService.getConnectedInstance(timeoutMs)
    }

    // === Screen bounds helpers ===

    /**
     * Get screen size [width, height].
     */
    protected fun getScreenSize(): IntArray {
        return intArrayOf(ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight())
    }

    /**
     * Validate that coordinates are within screen bounds.
     * Returns an error message if out of bounds, or null if valid.
     */
    protected fun validateCoordinates(x: Int, y: Int): String? {
        val size = getScreenSize()
        if (x < 0 || x >= size[0] || y < 0 || y >= size[1]) {
            return "Coordinates ($x, $y) out of screen bounds (${size[0]}x${size[1]}). Use get_screen_info to get valid coordinates."
        }
        return null
    }
}
```

**MVP 대조 포인트**
- `wait_after` 공통 파라미터, 언어 토글(`useChineseDescription`), 화면 경계 검증(`validateCoordinates`)은 MVP `Tool` 인터페이스에 당장 없어도 된다. 핵심은 `getName/getParameters/execute` 3종 세트와 파라미터 파싱 헬퍼.
- `requireAccessibilityService()`가 접근성 서비스 재연결 대기(최대 20초)까지 감싸준다 — MVP도 서비스가 `null`일 때 예외 대신 명확한 에러를 반환하는 패턴은 그대로 가져갈 만하다.

### 1.2 `ToolResult.kt` — 툴 반환 값

원본: [`tool/ToolResult.kt`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/tool/ToolResult.kt)

```kotlin
// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

class ToolResult private constructor(
    val isSuccess: Boolean,
    val data: String?,
    val error: String?
) {
    companion object {
        @JvmStatic
        fun success(data: String): ToolResult = ToolResult(true, data, null)

        @JvmStatic
        fun error(error: String): ToolResult = ToolResult(false, null, error)
    }

    override fun toString(): String = if (isSuccess) {
        "ToolResult{success=true, data='$data'}"
    } else {
        "ToolResult{success=false, error='$error'}"
    }
}
```

성공/실패를 하나의 타입으로 표현하는 단순한 값 객체. MVP `ToolResult`도 이 형태(성공 데이터 or 에러 메시지, 둘 중 하나만 채워짐)로 충분하다.

### 1.3 `ToolParameter.kt` — 파라미터 스키마

원본: [`tool/ToolParameter.kt`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/tool/ToolParameter.kt)

```kotlin
// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val isRequired: Boolean
)
```

이 4개 필드(`name`, `type`, `description`, `isRequired`)가 그대로 LLM API의 JSON Schema `properties` 항목으로 직렬화된다. MVP `CloudLlmClient` 구현 시 이 구조를 JSON Schema로 매핑하면 된다.

### 1.4 `ToolRegistry.kt` — 이름으로 툴 등록/조회

원본: [`tool/ToolRegistry.kt`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/tool/ToolRegistry.kt)

```kotlin
// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import io.agents.pokeclaw.agent.knowledge.*
import io.agents.pokeclaw.tool.impl.*
import io.agents.pokeclaw.tool.impl.mobile.*
import io.agents.pokeclaw.tool.impl.tv.*

object ToolRegistry {

    enum class DeviceType { TV, MOBILE }

    private val tools = LinkedHashMap<String, BaseTool>()
    var deviceType: DeviceType = DeviceType.TV
        private set

    @JvmStatic
    fun getInstance(): ToolRegistry = this

    fun registerAllTools(type: DeviceType = DeviceType.TV) {
        deviceType = type
        tools.clear()
        registerCommonTools()
        when (type) {
            DeviceType.TV -> registerTvTools()
            DeviceType.MOBILE -> registerMobileTools()
        }
    }

    private fun registerCommonTools() {
        register(GetScreenInfoTool())
        register(FindNodeInfoTool())
        register(InputTextTool())
        register(SystemKeyTool())
        register(OpenAppTool())
        register(GetInstalledAppsTool())
        register(TakeScreenshotTool())
        register(WaitTool())
        register(RepeatActionsTool())
        register(ClipboardTool())
        register(SendFileTool())
        register(GetDeviceInfoTool())
        register(GetNotificationsTool())
        register(MakeCallTool())
        register(FinishTool())
        // Knowledge Base tools — shared vault available in all modes
        register(KbWriteTool())
        register(KbReadTool())
        register(KbSearchTool())
        register(KbAppendTool())
        register(KbAddTodoTool())
    }

    private fun registerTvTools() {
        register(DpadUpTool())
        register(DpadDownTool())
        register(DpadLeftTool())
        register(DpadRightTool())
        register(DpadCenterTool())
        register(VolumeUpTool())
        register(VolumeDownTool())
        register(PressMenuTool())
        register(PressPowerTool())
    }

    private fun registerMobileTools() {
        register(TapTool())
        register(TapNodeTool())
        register(LongPressTool())
        register(SwipeTool())
        register(ScrollToFindTool())
        register(FindAndTapTool())
        register(SendMessageTool())
        register(AutoReplyTool())
    }

    fun register(tool: BaseTool) {
        tools[tool.getName()] = tool
    }

    fun getTool(name: String): BaseTool? = tools[name]

    fun getDisplayName(name: String): String = tools[name]?.getDisplayName() ?: name

    fun getAllTools(): List<BaseTool> = tools.values.toList()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
        return try {
            tool.executeWithWaitAfter(params)
        } catch (e: Exception) {
            io.agents.pokeclaw.utils.XLog.e("ToolRegistry", "Tool '$name' execution failed with params=$params", e)
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }
}
```

**MVP 대조 포인트**
- PokeClaw는 TV/모바일 두 기기 타입과 20개 넘는 툴을 분기 등록한다. MVP는 `registerCommonTools()`도 필요 없이 **5개를 그냥 순서대로 등록**하면 된다.
- `executeTool()`이 `try/catch`로 감싸 예외를 `ToolResult.error`로 변환하는 패턴은 그대로 가져갈 가치가 있다 — 툴 실행 중 예외가 루프 전체를 죽이지 않게 막아준다.

---

## 2. MVP 5개 툴 (`tool/impl/*`)

### 2.1 `FinishTool.java` — 가장 단순한 툴 (틀 확인용)

원본: [`tool/impl/FinishTool.java`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/tool/impl/FinishTool.java)

```java
// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FinishTool extends BaseTool {

    @Override
    public String getName() {
        return "finish";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_finish);
    }

    @Override
    public String getDescriptionEN() {
        return "Signal that the current task is complete. Call this when you have successfully accomplished the user's request. Provide a summary of what was done.";
    }

    @Override
    public String getDescriptionCN() {
        return "Mark the current task as complete. Call this tool when the user's request has been successfully fulfilled. Provide a summary of what was accomplished.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("summary", "string", "A brief summary of what was accomplished", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String summary = requireString(params, "summary");
        return ToolResult.success("Task completed: " + summary);
    }
}
```

`finish`는 부작용이 없다 — 그냥 `summary`를 받아 성공으로 되돌려준다. 실제 "루프 종료" 판정은 ReAct 루프(§4) 쪽에서 `toolName == "finish"`를 감지해서 처리한다. **툴 자체는 종료 로직을 모른다** — 이 분리가 MVP `FinishTool`에도 그대로 적용될 패턴이다.

### 2.2 `GetScreenInfoTool.java` — 관찰(observation)

원본: [`tool/impl/GetScreenInfoTool.java`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/tool/impl/GetScreenInfoTool.java)

```java
// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GetScreenInfoTool extends BaseTool {

    @Override
    public String getName() {
        return "get_screen_info";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_get_screen_info);
    }

    @Override
    public String getDescriptionEN() {
        return "Get the current screen's UI elements. Each element has a node ID (e.g. [n3]) that can be used with tap_node. Do not cache this result — node IDs change on each call.";
    }

    @Override
    public String getDescriptionCN() {
        return "Get the current screen's UI elements. Each element has a node ID (e.g. [n3]) that can be used with tap_node. Do not cache this result — node IDs change on each call.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    public static final String SYSTEM_DIALOG_BLOCKED = "__SYSTEM_DIALOG_BLOCKED__";

    /**
     * Switch to full node tree mode (includes all nodes and all attributes, for debugging).
     * false = compact mode (default, saves tokens); true = full mode.
     */
    public static boolean useFullTree = false;

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String tree = useFullTree ? service.getScreenTreeFull() : service.getScreenTree();
        if (tree == null) {
            return ToolResult.error(SYSTEM_DIALOG_BLOCKED);
        }
        return ToolResult.success(tree);
    }
}
```

**참조맵 §3에서 강조한 대로**, 이 파일 자체는 얇다. 진짜 알맹이(`getScreenTree()`)는 §3의 `ClawAccessibilityService`에 있다. 주목할 점:
- `SYSTEM_DIALOG_BLOCKED` 특수 마커: 트리가 `null`이면(시스템 다이얼로그가 화면을 가림) 일반 에러가 아니라 이 마커를 반환 → ReAct 루프(§4)가 이 마커를 보고 태스크를 완전히 중단시킨다. MVP도 "루프를 계속 재시도해봐야 의미 없는 실패"는 구분해서 처리할 가치가 있다.

### 2.3 `tool/impl/mobile/TapNodeTool.java` — node id로 탭

원본: [`tool/impl/mobile/TapNodeTool.java`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/tool/impl/mobile/TapNodeTool.java)

```java
// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl.mobile;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tap a UI element by its node ID (e.g. "n3") from get_screen_info output.
 * More reliable than coordinate-based tap — IDs are assigned per screen refresh.
 */
public class TapNodeTool extends BaseTool {

    @Override
    public String getName() {
        return "tap_node";
    }

    @Override
    public String getDisplayName() {
        return "Tap Node";
    }

    @Override
    public String getDescriptionEN() {
        return "Tap a UI element by its node ID (e.g. \"n3\") from the screen info. More reliable than raw coordinates.";
    }

    @Override
    public String getDescriptionCN() {
        return "Tap a UI element by its node ID (e.g. \"n3\") from the screen info. More reliable than raw coordinates.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("node_id", "string", "Node ID from screen info, e.g. n3", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String nodeId = requireString(params, "node_id");
        if (nodeId == null || nodeId.isEmpty()) {
            return ToolResult.error("node_id is required");
        }
        // Normalize: strip brackets if user passes "[n3]"
        nodeId = nodeId.replace("[", "").replace("]", "").trim();

        int[] coords = service.getNodeCoordinates(nodeId);
        if (coords == null) {
            return ToolResult.error("Node " + nodeId + " not found. Call get_screen_info first to refresh node IDs.");
        }
        int x = coords[0];
        int y = coords[1];
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        boolean success = service.performTap(x, y);
        return success ? ToolResult.success("Tapped node " + nodeId + " at (" + x + ", " + y + ")")
                : ToolResult.error("Failed to tap node " + nodeId + " at (" + x + ", " + y + ")");
    }
}
```

핵심 흐름: `node_id` 문자열 정규화(`[n3]` → `n3`) → `service.getNodeCoordinates(nodeId)`로 좌표 조회 → 화면 경계 검증 → `service.performTap(x, y)`. **모델은 좌표를 모른다** — id만 다루고, 실제 좌표 변환은 접근성 서비스의 저장된 맵에서 일어난다(§3 참조).

### 2.4 `InputTextTool.java` — 텍스트 입력

원본: [`tool/impl/InputTextTool.java`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/tool/impl/InputTextTool.java) (전체 258줄)

> MVP 골격에 필요한 건 **핵심 경로**(node_id 탭 → 포커스 대기 → `ACTION_SET_TEXT`)뿐이다. 아래는 그 핵심 경로이고, 클립보드 붙여넣기 폴백·재시도 3회·근접 노드 탐색 등은 "부가 견고성 장치"로 표시해뒀다.

```java
// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

// ... imports 생략 (android.os.Bundle, AccessibilityNodeInfo, ClipboardManager 등) ...

public class InputTextTool extends BaseTool {

    @Override
    public String getName() {
        return "input_text";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to input", true),
                new ToolParameter("node_id", "string", "Optional: node ID from get_screen_info (e.g. 'n5') to target a specific text field", false),
                new ToolParameter("clear_first", "boolean", "Whether to clear existing text before input (default true)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = requireString(params, "text");
        String nodeId = optionalString(params, "node_id", "");
        boolean clearFirst = optionalBoolean(params, "clear_first", true);
        int[] targetCoords = null;

        // 1) node_id가 있으면 먼저 탭해서 포커스를 준다 — 참조맵 §6의 불변 규칙
        if (!nodeId.isEmpty()) {
            nodeId = nodeId.replace("[", "").replace("]", "").trim();
            targetCoords = service.getNodeCoordinates(nodeId);
            if (targetCoords == null) {
                return ToolResult.error("Node " + nodeId + " not found. Call get_screen_info first to refresh node IDs.");
            }
            service.performTap(targetCoords[0], targetCoords[1]);
            try { Thread.sleep(300); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // 2) 포커스된 입력 필드를 찾는다 (findFocus(FOCUS_INPUT), 실패 시 좌표 근처 editable 탐색)
        AccessibilityNodeInfo targetNode = waitForTargetEditable(service, targetCoords);
        if (targetNode == null) {
            return ToolResult.error("No target text field found" + (nodeId.isEmpty() ? "" : " after tapping node " + nodeId));
        }

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);

        if (clearFirst) {
            clearNodeText(targetNode); // 전체 선택 → ACTION_SET_TEXT("")
        }

        // 3) 핵심: ACTION_SET_TEXT로 텍스트 주입
        if (trySetTextWithRetries(targetNode, text, clearFirst)) {
            return ToolResult.success(clearFirst ? "Input text: " + text : "Appended text: " + text);
        }

        // --- 이하는 "부가 견고성 장치": ACTION_SET_TEXT가 막힌 앱(WebView 등)을 위한 클립보드 붙여넣기 폴백 ---
        boolean clipboardSet = setClipboardText(service, text);
        if (!clipboardSet) {
            return ToolResult.error("Failed to set clipboard text");
        }
        targetNode = waitForTargetEditable(service, targetCoords);
        if (targetNode == null) {
            return ToolResult.error("Failed to recover text field before clipboard paste");
        }
        if (clearFirst) {
            clearNodeText(targetNode);
        } else {
            CharSequence existing = targetNode.getText();
            int end = existing != null ? existing.length() : 0;
            Bundle cursorArgs = new Bundle();
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end);
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs);
        }
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return ToolResult.success(clearFirst ? "Input text (via paste): " + text : "Appended text (via paste): " + text);
        }
        return ToolResult.error("Failed to input text, both ACTION_SET_TEXT and clipboard paste failed");
    }

    /** 전체 선택 후 빈 문자열로 덮어써서 지운다 */
    private void clearNodeText(AccessibilityNodeInfo node) {
        Bundle selectAllArgs = new Bundle();
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Integer.MAX_VALUE);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs);

        Bundle clearArgs = new Bundle();
        clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
    }

    /** ACTION_SET_TEXT를 최대 3회 재시도 (부가 견고성 장치) */
    private boolean trySetTextWithRetries(AccessibilityNodeInfo node, String text, boolean clearFirst) {
        for (int attempt = 0; attempt < 3; attempt++) {
            CharSequence existing = node.getText();
            String candidateText = clearFirst ? text : ((existing != null ? existing.toString() : "") + text);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, candidateText);
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    /** 포커스된 입력 필드 대기: findFocus(FOCUS_INPUT) → 실패 시 탭 좌표 근처 editable 탐색, 최대 5회 재시도 */
    private AccessibilityNodeInfo waitForTargetEditable(ClawAccessibilityService service, int[] targetCoords) {
        for (int attempt = 0; attempt < 5; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) { sleepShort(); continue; }

            AccessibilityNodeInfo focused = findFocusedEditText(root);
            if (focused != null) return focused;

            if (targetCoords != null) {
                AccessibilityNodeInfo nearTarget = findEditableNearPoint(root, targetCoords[0], targetCoords[1]);
                if (nearTarget != null) return nearTarget;
            }
            sleepShort();
        }
        return null;
    }

    // findFocusedEditText / findEditableNearPoint / findFirstEditable / setClipboardText / sleepShort
    // 는 "부가 견고성 장치" — 원본 파일 참조 (재귀 트리 탐색 + 클립보드 매니저 접근)
}
```

**MVP `InputTextTool` 핵심 경로 요약 (3단계):**
1. `node_id` 있으면 먼저 탭 → 포커스
2. 포커스된 editable 노드 찾기 (`findFocus(AccessibilityNodeInfo.FOCUS_INPUT)`)
3. `Bundle`에 `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE`를 담아 `performAction(ACTION_SET_TEXT, args)`

클립보드 폴백·재시도·근접 노드 탐색은 M2를 먼저 통과시킨 뒤 안정성이 부족할 때 추가한다(참조맵 §4의 "부가 장치는 필요 순서대로" 원칙과 동일).

### 2.5 `OpenAppTool.java` — 패키지명으로 앱 실행

원본: [`tool/impl/OpenAppTool.java`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/tool/impl/OpenAppTool.java) (전체 234줄)

> 핵심 경로는 매우 짧다(`service.openApp(packageName)` 한 줄). 나머지 대부분(앱 이름 → 패키지명 매핑, 제조사별 "허용" 다이얼로그 자동 클릭)은 프로덕션 견고성 장치다.

```java
// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

// ... imports 생략 ...

public class OpenAppTool extends BaseTool {

    @Override
    public String getName() {
        return "open_app";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("package_name", "string", "The package name of the app to open", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String packageName = params.containsKey("package_name")
                ? requireString(params, "package_name")
                : requireString(params, "app_name");

        // 부가 기능: LLM이 패키지명 대신 앱 이름("Instagram")을 보내면 매핑 테이블 + 설치된 앱 라벨 퍼지 매칭으로 해석
        if (!packageName.contains(".")) {
            String resolved = resolveAppName(packageName);
            if (resolved != null) packageName = resolved;
        }

        // 핵심: 실제 앱 실행은 이 한 줄
        boolean success = service.openApp(packageName);
        if (!success) {
            return ToolResult.error("Failed to open app: " + packageName + ". Make sure the app is installed.");
        }

        // 부가 기능: 샤오미/화웨이/오포 등에서 백그라운드 실행 시 뜨는 "허용" 인터셉트 다이얼로그 자동 클릭
        dismissChainLaunchDialog(service);

        return ToolResult.success("Opened app: " + packageName);
    }

    // resolveAppName(): "whatsapp" → "com.whatsapp" 같은 하드코딩 매핑 30여 개
    //                    + 매핑에 없으면 설치된 앱 라벨/패키지명에서 퍼지 매칭 (부가 기능, MVP 생략 가능)
    // dismissChainLaunchDialog() / tapPositiveDialogButton() / matchesAllowButton():
    //     최대 3회, 500ms 간격으로 "Allow"류 버튼을 찾아 자동 클릭 (부가 기능, 실기기 확인 후 필요시 추가)
}
```

**MVP `OpenAppTool` 핵심 경로:** `getPackageManager().getLaunchIntentForPackage(packageName)`로 인텐트를 얻고 `FLAG_ACTIVITY_NEW_TASK`를 붙여 `startActivity()` — 이 로직은 실제로 `ClawAccessibilityService.openApp()`(§3 마지막 섹션)에 있다. 앱 이름 매핑과 다이얼로그 자동 클릭은 카톡 시나리오가 불안정할 때 참조맵 §4 우선순위대로 나중에 추가한다.

---

## 3. 접근성 서비스 핵심 로직 (`service/ClawAccessibilityService.java`)

**참조맵에서 가장 중요하다고 강조한 파일.** 5개 툴이 위임하는 실제 관찰(read)·행동(act) 로직이 전부 여기 있다. 전체 838줄 중 MVP와 관련 없는 부분(스크린샷 캡처, 전역 액션/잠금해제, TV 키 이벤트 주입)은 절 끝에 요약만 남기고 생략했다.

원본: [`service/ClawAccessibilityService.java`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/service/ClawAccessibilityService.java)

### 3.1 싱글턴 + 연결 대기

```java
public class ClawAccessibilityService extends AccessibilityService {

    private static final String TAG = "ClawA11yService";
    private static volatile ClawAccessibilityService instance;

    public static ClawAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    /** 설정 > 접근성에서 켜졌는지 확인 (연결 여부와는 다름) */
    public static boolean isEnabledInSettings(Context context) {
        try {
            int accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (accessibilityEnabled != 1) return false;
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || enabledServices.isEmpty()) return false;
            String myService = context.getPackageName() + "/" + ClawAccessibilityService.class.getName();
            return enabledServices.contains(myService);
        } catch (Exception e) {
            return false;
        }
    }

    /** 최대 timeoutMs까지 서비스 연결을 기다린다 */
    public static boolean awaitRunning(long timeoutMs) {
        if (instance != null) return true;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (instance != null) return true;
        }
        return false;
    }

    /**
     * 안드로이드가 서비스를 잠깐 재바인딩하는 순간에도 즉시 실패시키지 않고
     * 짧게 기다렸다가 재확인한다. BaseTool.requireAccessibilityService()가 호출.
     */
    public static ClawAccessibilityService getConnectedInstance(long timeoutMs) {
        ClawAccessibilityService service = instance;
        if (service != null) return service;

        Context app = ClawApplication.Companion.getInstance();
        if (app == null || !isEnabledInSettings(app)) {
            return null;
        }
        return awaitRunning(timeoutMs) ? instance : null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this; // 시스템이 서비스를 생성 — new 불가, 여기서만 instance 확보
        // ... KVUtils 상태 기록, 포그라운드 동기화 생략 ...
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        // ...
    }
}
```

**MVP 대조 포인트:** 접근성 서비스는 안드로이드 시스템이 생성한다(`new` 불가). `onServiceConnected()`에서 `static` 인스턴스를 잡고, 다른 코드(툴들)는 이 static getter로 접근한다 — 참조맵 §6 불변 규칙과 동일. MVP는 `isEnabledInSettings`/`getConnectedInstance`의 재연결 대기까지는 필요 없고, `getInstance() != null` 체크 정도로 시작해도 된다.

### 3.2 제스처: 탭 / 롱프레스 / 스와이프

```java
public boolean performTap(int x, int y) {
    return performTap(x, y, 100);
}

public boolean performTap(int x, int y, long durationMs) {
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, durationMs);
    GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke)
            .build();
    return dispatchGestureSync(gesture);
}

public boolean performSwipe(int startX, int startY, int endX, int endY, long durationMs) {
    Path path = new Path();
    path.moveTo(startX, startY);
    path.lineTo(endX, endY);
    GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, durationMs);
    GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke)
            .build();
    return dispatchGestureSync(gesture);
}

/** dispatchGesture는 비동기 콜백 기반이라, CountDownLatch로 동기 호출처럼 감싼다 */
private boolean dispatchGestureSync(GestureDescription gesture) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean result = new AtomicBoolean(false);

    boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            result.set(true);
            latch.countDown();
        }

        @Override
        public void onCancelled(GestureDescription gestureDescription) {
            result.set(false);
            latch.countDown();
        }
    }, null);

    if (!dispatched) return false;

    try {
        latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    }
    return result.get();
}
```

`dispatchGesture()`는 API 24부터 제공되는 `AccessibilityService` 메서드로, 콜백 기반 비동기 API다. `CountDownLatch`로 감싸서 툴 코드 입장에서는 동기 함수처럼 보이게 만든 것이 패턴의 핵심. MVP `tapNode()` 구현 시 이 `dispatchGestureSync` 패턴을 그대로 가져가면 된다(Kotlin이면 `suspendCoroutine`으로 대체 가능).

### 3.3 트리 직렬화: `getScreenTree()` / `buildNodeTree()` — 가장 중요한 부분

```java
/** Node ID → center coordinates mapping for tap_node tool */
private final ConcurrentHashMap<String, int[]> nodeIdMap = new ConcurrentHashMap<>();
private final AtomicInteger nodeCounter = new AtomicInteger(0);

public String getScreenTree() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) {
        return null; // GetScreenInfoTool이 이걸 SYSTEM_DIALOG_BLOCKED로 취급
    }
    nodeIdMap.clear();     // 매 호출마다 재발급 — 참조맵 §6 "캐싱 금지" 규칙의 실제 구현
    nodeCounter.set(0);
    StringBuilder sb = new StringBuilder();
    buildNodeTree(root, sb, 0);
    return sb.toString();
}

/** node id로 좌표 조회. tap_node/input_text가 사용 */
public int[] getNodeCoordinates(String nodeId) {
    return nodeIdMap.get(nodeId);
}

private void buildNodeTree(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
    if (node == null) return;

    // 화면에 안 보이는 노드는 스킵하되, 자식은 계속 순회한다
    // (부모가 안 보여도 자식 중 일부는 보일 수 있음 — 예: 스크롤 컨테이너)
    if (!node.isVisibleToUser()) {
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                buildNodeTree(child, sb, depth);
                child.recycle();
            }
        }
        return;
    }

    // "의미 있는" 노드 판정: 텍스트/설명이 있거나, 클릭/스크롤/편집/체크/롱클릭 가능하거나,
    // 슬라이더/프로그레스바인 경우
    boolean hasText = node.getText() != null && node.getText().length() > 0;
    boolean hasDesc = node.getContentDescription() != null && node.getContentDescription().length() > 0;
    boolean isInteractive = node.isClickable() || node.isScrollable() || node.isEditable()
            || node.isCheckable() || node.isLongClickable();
    boolean isSlider = isSliderNode(node);
    CharSequence cn = node.getClassName();
    boolean isProgress = cn != null && cn.toString().contains("ProgressBar");
    boolean isMeaningful = hasText || hasDesc || isInteractive || isSlider || isProgress;

    if (isMeaningful) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int cx = (bounds.left + bounds.right) / 2;
        int cy = (bounds.top + bounds.bottom) / 2;

        // node id 발급 + 좌표 저장 (tap_node가 나중에 조회)
        String nodeId = "n" + nodeCounter.incrementAndGet();
        nodeIdMap.put(nodeId, new int[]{cx, cy});

        // 압축 포맷: [n1] "텍스트" tap edit (cx,cy)
        StringBuilder line = new StringBuilder();
        for (int d = 0; d < Math.min(depth, 4); d++) line.append("  ");
        line.append("[").append(nodeId).append("] ");

        if (hasText) {
            CharSequence text = node.getText();
            line.append("\"").append(text.length() > 40 ? text.subSequence(0, 40) + ".." : text).append("\"");
        } else if (hasDesc) {
            line.append("\"").append(node.getContentDescription()).append("\"");
        }
        if (node.isClickable()) line.append(" tap");
        if (node.isEditable()) line.append(" edit");
        if (node.isScrollable()) line.append(" scroll");
        if (node.isCheckable()) line.append(node.isChecked() ? " on" : " off");
        line.append(" (").append(cx).append(",").append(cy).append(")");

        sb.append(line).append("\n");
    }

    // 스킵된 노드의 자식은 같은 depth를 유지 (들여쓰기가 부모 스킵으로 밀리지 않게)
    int childDepth = isMeaningful ? depth + 1 : depth;
    for (int i = 0; i < node.getChildCount(); i++) {
        AccessibilityNodeInfo child = node.getChild(i);
        if (child != null) {
            buildNodeTree(child, sb, childDepth);
            child.recycle();
        }
    }
}

/** 슬라이더/시크바/레이팅바 판정 (버튼/텍스트 외 상호작용 요소 포함용) */
private boolean isSliderNode(AccessibilityNodeInfo node) {
    CharSequence className = node.getClassName();
    if (className == null) return false;
    String cls = className.toString();
    return cls.contains("SeekBar") || cls.contains("Slider") || cls.contains("RatingBar")
            || node.getRangeInfo() != null;
}
```

**이 함수가 참조맵 §3에서 말하는 "진짜 알맹이"다.** 재귀 순회 → 안 보이는 노드 스킵(자식은 계속 탐색) → 의미 있는 노드만 필터 → 중심 좌표 계산 → `n1, n2, ...` id 부여 후 `nodeIdMap`에 저장 → 압축 문자열 생성. MVP `getScreenTree()`를 구현할 때 이 알고리즘을 그대로(자기 코드로 재작성해서) 가져가면 된다. 참고로 `buildNodeTreeFull()`(전체 노드+전체 속성, 디버그용, `useFullTree` 플래그로 전환)은 원본에는 있지만 MVP엔 불필요하다.

### 3.4 노드 검색 / 클릭 (`OpenAppTool`의 다이얼로그 자동 클릭이 의존)

```java
public List<AccessibilityNodeInfo> findNodesByText(String text) {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    String query = text != null ? text.trim() : "";
    if (root == null || query.isEmpty()) return new ArrayList<>();

    List<AccessibilityNodeInfo> directMatches = root.findAccessibilityNodeInfosByText(query);
    if (directMatches != null && !directMatches.isEmpty()) return directMatches;

    // 정확 매칭 실패 시 완화된 매칭(대소문자/공백 무시 등)으로 폴백 — 부가 견고성 장치
    // ... collectTextMatches() 생략 ...
    return new ArrayList<>();
}

public List<AccessibilityNodeInfo> findNodesById(String viewId) {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) return new ArrayList<>();
    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
    return nodes != null ? nodes : new ArrayList<>();
}

public boolean clickNode(AccessibilityNodeInfo node) {
    if (node == null) return false;
    if (node.isClickable()) {
        boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (clicked) return true;
    }
    // 노드 자체가 클릭 불가면 부모를 타고 올라가며 클릭 가능한 조상을 찾는다
    AccessibilityNodeInfo parent = node.getParent();
    while (parent != null) {
        if (parent.isClickable()) {
            if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        parent = parent.getParent();
    }
    // 최후 수단: 노드 중심 좌표를 직접 탭
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    return performTap(bounds.centerX(), bounds.centerY());
}
```

`clickNode()`의 3단 폴백(직접 클릭 → 클릭 가능한 부모 클릭 → 좌표 탭)은 MVP 5개 툴엔 당장 필요 없지만(모든 탭이 `tap_node`를 통해 좌표로 이루어짐), `open_app` 확장 시 참고할 패턴이다.

### 3.5 앱 실행: `openApp()`

```java
public boolean openApp(String packageName) {
    try {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            return false; // 앱이 설치되지 않았거나 launcher intent가 없음
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        return true;
    } catch (Exception e) {
        return false;
    }
}
```

`OpenAppTool`(§2.5)의 핵심 경로가 바로 이 3줄이다. `FLAG_ACTIVITY_NEW_TASK`는 접근성 서비스 컨텍스트(Activity가 아님)에서 `startActivity()`를 호출하기 위해 필수.

### 3.6 (생략) MVP 범위 밖 기능

원본 파일에는 아래 기능도 있지만 5개 툴과 무관해 생략했다. 필요해지면 원본 링크에서 직접 대조:
- **스크린샷 캡처** (`takeScreenshot()`, API 30+, `TakeScreenshotCallback`)
- **전역 액션** (`pressBack/pressHome/openRecentApps/lockScreen/unlockScreen`)
- **TV 리모컨 키 이벤트 주입** (`sendKeyEvent()`, shell `input keyevent` 호출)
- **`getScreenTreeFull()` / `buildNodeTreeFull()`** (디버그용 무필터 전체 트리)
- **`AutoReplyManager` 연동** (`onAccessibilityEvent`의 알림 감지 → 자동 답장, 완전히 별개 기능)

---

## 4. ReAct 루프 (`agent/DefaultAgentService.kt`)

원본: [`agent/DefaultAgentService.kt`](https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/agent/DefaultAgentService.kt) (전체 871줄)

이 파일은 ReAct 루프 코어에 참조맵 §4가 언급한 **6가지 부가 장치**(재시도, 루프 정체 감지, 3종 Guard, 예산/토큰, 히스토리 압축)가 전부 얹혀 있다. 아래는 부가 장치를 주석으로 표시해가며 **핵심 골격만 남긴 발췌**다. 실제로 이식할 땐 이 골격부터 만들고, 필요해지는 순서대로 참조맵 §4 우선순위를 따라 부가 장치를 하나씩 추가한다.

### 4.1 핵심 골격 (observe → reason → act → finish)

```kotlin
class DefaultAgentService : AgentService {

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        // --- 사전 점검: 접근성 서비스가 켜져 있는지 ---
        if (ClawAccessibilityService.getInstance() == null) {
            callback.onError(0, RuntimeException("Accessibility not enabled"), 0)
            return
        }

        // --- 시스템 프롬프트 + 첫 사용자 메시지 구성 ---
        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(config.systemPrompt))
        messages.add(UserMessage.from(userPrompt))

        var iterations = 0
        var totalTokens = 0
        val maxIterations = config.maxIterations

        // ============ ReAct 루프 코어 ============
        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // [부가 장치: compressHistoryForSend(messages) — §4.3 참조. MVP는 생략]

            // ---- reason: LLM 호출 ----
            val llmResponse: LlmResponse
            try {
                llmResponse = llmClient.chat(messages, toolSpecs) // [부가 장치: chatWithRetry — §4.2]
            } catch (e: Exception) {
                callback.onError(iterations, RuntimeException("API call failed: ${e.message}"), totalTokens)
                return
            }

            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }

            // AI 응답을 히스토리에 추가
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // ---- 종료 판정 1: 툴 호출 없이 텍스트만 왔다면 = LLM이 답을 완결했다는 뜻 ----
            if (!llmResponse.hasToolExecutionRequests()) {
                val responseText = llmResponse.text ?: ""
                if (responseText.isNotEmpty()) {
                    callback.onComplete(iterations, responseText, totalTokens, null)
                    return
                }
                continue
            }

            // ---- act: 툴 호출 실행 ----
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, "Task cancelled", totalTokens, null)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val params: Map<String, Any> = parseJsonArgs(toolRequest.arguments())

                callback.onToolCall(iterations, toolName, toolName, toolRequest.arguments())
                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                callback.onToolResult(iterations, toolName, toolName, params.toString(), result)

                // ---- 종료 판정 2: finish 툴이 성공적으로 호출됨 ----
                if (toolName == "finish" && result.isSuccess) {
                    callback.onComplete(iterations, result.data ?: "Task completed", totalTokens, null)
                    return
                }

                // ---- 되먹임: 툴 실행 결과를 다음 라운드 관찰로 히스토리에 추가 ----
                messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(result)))
            }

            // [부가 장치: StuckDetector로 정체 감지 — §4.4]
        }

        // ---- 종료 판정 3: 최대 라운드 도달 (안전장치) ----
        callback.onError(iterations, RuntimeException("Max iterations ($maxIterations) reached"), totalTokens)
    }
}
```

이 골격이 참조맵이 말하는 "루프 코어는 MVP 골격과 동일"의 실체다. `while` + LLM 호출 + 툴 실행 + 히스토리 축적 + 3가지 종료 판정(텍스트만 응답 / `finish` 호출 성공 / 최대 라운드) — 이게 전부다.

### 4.2 실제 원본에서 골격에 얹힌 부가 장치들 (발췌 위치)

아래는 §4.1 골격의 각 지점에 실제로 원본이 무엇을 끼워 넣는지, 실제 코드와 함께 정리한 것이다.

**① `chatWithRetry` — LLM 호출 재시도 (지수 백오프, 최대 3회)**

```kotlin
private fun chatWithRetry(messages: List<ChatMessage>, callback: AgentCallback, iteration: Int): LlmResponse {
    var lastException: Exception? = null
    for (attempt in 0 until MAX_API_RETRIES) {
        if (cancelled.get()) throw RuntimeException("cancelled")
        try {
            return llmClient.chat(messages, toolSpecs)
        } catch (e: Exception) {
            lastException = e
            val msg = e.message ?: ""
            // 401/403/토큰 소진은 재시도해도 의미 없으므로 즉시 던진다
            if (msg.contains("401") || msg.contains("403") || msg.contains("insufficient")) {
                throw e
            }
            val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong() // 1s, 2s, 4s
            Thread.sleep(delay)
        }
    }
    throw lastException!!
}
```

**② 정체(dead-loop) 감지 — 최근 N라운드의 "화면 해시 + 툴 호출"이 전부 동일하면 정체로 판정**

```kotlin
private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
    if (history.size < LOOP_DETECT_WINDOW) return false // WINDOW = 4
    val first = history.first()
    return history.all { it == first }
}
```
> 원본은 이보다 더 정교한 `StuckDetector` 클래스(5-신호, 3-단계 복구)를 별도 파일로 갖고 있다 — 참조맵 §4의 `agent/StuckDetector.kt` 참조.

**③ 성급한 `finish` 차단 (Guard) — 예: 인앱 검색 중인데 검색을 안 하고 끝내려 하면 차단**

```kotlin
val blockedFinish = if (toolName == "finish") {
    val screenInfo = ToolRegistry.getInstance().getTool("get_screen_info")?.execute(emptyMap())?.data
    directDeviceDataGuard.maybeBlockFinish()
        ?: inAppSearchGuard.maybeBlockFinish(screenInfo)
        ?: emailComposeGuard.maybeBlockFinish(screenInfo)
} else null

if (blockedFinish != null) {
    // finish를 실행하지 않고, 대신 왜 안 되는지 알려주는 메시지를 히스토리에 추가해 계속 진행시킨다
    messages.add(UserMessage.from(blockedFinish))
    continue
}
```

**④ 토큰/비용 예산 상한**

```kotlin
when (taskBudget.check(tokenStatus.totalTokens, tokenStatus.estimatedCostUsd)) {
    TaskBudget.Status.HARD_LIMIT -> {
        callback.onComplete(iterations, "Task stopped: budget limit reached", totalTokens, null)
        return
    }
    TaskBudget.Status.SOFT_LIMIT -> {
        // 경고 메시지만 히스토리에 추가하고 계속 진행
        messages.add(UserMessage.from("[System Notice] approaching budget limit, finish efficiently"))
    }
    TaskBudget.Status.OK -> { /* 정상 진행 */ }
}
```

**⑤ 히스토리 압축 (`compressHistoryForSend`) — 오래된 라운드의 무거운 관찰 결과를 압축**

```kotlin
private val KEEP_RECENT_ROUNDS = 3 // 최근 3라운드는 그대로 보존

private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
    // get_screen_info 결과는 전역적으로 "가장 최근 것 1개"만 원본 유지, 나머지는 플레이스홀더로 치환
    val screenPlaceholder = "[screen info omitted]"
    val lastScreenIdx = messages.indexOfLast {
        it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
    }
    for (i in messages.indices) {
        val msg = messages[i]
        if (msg is ToolExecutionResultMessage && msg.toolName() == "get_screen_info" && i != lastScreenIdx) {
            messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
        }
    }
    // 보호 구간(최근 KEEP_RECENT_ROUNDS라운드) 밖의 다른 툴 결과는 한 줄 요약("✓ ..." / "✗ ...")으로 압축
    // ... 상세 로직은 원본 참조 ...
}
```

**⑥ Opt-3: 행동 툴 실행 직후 화면을 자동으로 다시 읽어 결과에 합쳐줌 (추론 라운드 절약)**

```kotlin
private val ACTION_TOOLS = setOf("tap", "tap_node", "swipe", "input_text", "open_app", /* ... */)

// 행동 툴 실행 후:
if (toolName in ACTION_TOOLS) {
    Thread.sleep(SCREEN_SETTLE_MS) // 500ms — UI 애니메이션/전환 대기
    val screenAfter = ToolRegistry.getInstance().getTool("get_screen_info")?.execute(emptyMap())
    // result.data 뒤에 "Screen after action:\n${screenAfter.data}"를 이어붙여
    // 다음 라운드에 LLM이 get_screen_info를 별도로 호출하지 않아도 되게 만든다
}
```
> MVP M4에서 "탭 후 화면 전환이 일어나면 다시 get_screen_info로 새 트리가 읽힘"(마일스톤 M2 DoD)을 만족시키는 방법이 두 가지다: **(a)** 이 Opt-3처럼 행동 툴 직후 자동으로 재관찰해 결과에 합치거나, **(b)** 단순하게 LLM이 다음 라운드에 `get_screen_info`를 직접 다시 호출하게 맡기는 것. MVP는 (b)로 시작해서 라운드 수가 아깝다고 느껴지면 (a)를 참조해 추가하는 게 쉽다.

---

## 부록 — 이 문서를 최신화하려면

```powershell
$base = "https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw"
curl.exe -s "$base/agent/DefaultAgentService.kt" -o defaultAgentService.kt
```

또는 [참조맵 §7의 빠른 경로 인덱스](./PokeClaw_참조맵.md#7-빠른-경로-인덱스-복붙용)에 있는 파일 목록을 raw prefix와 조합해 재요청한다.
