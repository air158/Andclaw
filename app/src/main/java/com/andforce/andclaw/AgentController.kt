package com.andforce.andclaw

import android.accessibilityservice.AccessibilityService
import android.app.DownloadManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.andforce.andclaw.model.AgentUiState
import com.andforce.andclaw.model.AiAction
import com.andforce.andclaw.model.ApiConfig
import com.andforce.andclaw.model.ChatMessage
import com.afwsamples.testdpc.common.Util
import com.andforce.andclaw.db.ChatMessageDao
import com.andforce.andclaw.db.ChatMessageEntity
import com.google.gson.Gson
import com.andforce.andclaw.bridge.RemoteOutboundHelper
import com.base.services.BridgeStatus
import com.base.services.FeishuInboundMessage
import com.base.services.IAiConfigService
import com.base.services.IRemoteBridgeService
import com.base.services.IRemoteChannelConfigService
import com.base.services.ITgBridgeService
import com.base.services.RemoteChannel
import com.base.services.RemoteIncomingMessage
import com.base.services.RemoteSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AgentController : ITgBridgeService, IAiConfigService {

    private const val TAG = "AgentController"
    private const val PREFS_NAME = "agent_config"

    private lateinit var appContext: Context
    private lateinit var remoteBridge: IRemoteBridgeService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gson = Gson()

    private val channelConfig: IRemoteChannelConfigService
        get() = remoteBridge as IRemoteChannelConfigService

    /** 远程任务上下文；本地 [startAgent] 会置空，避免误向远程回传。 */
    private var _activeRemoteSession: RemoteSession? = null
    val activeRemoteSession: RemoteSession?
        get() = _activeRemoteSession

    /** 过渡期：与 Telegram 相关的旧代码仍可能读取；由 [activeRemoteSession] 同步。 */
    var tgActiveChatId: Long = 0L
        private set

    override val bridgeStatus: StateFlow<BridgeStatus>
        get() = remoteBridge.telegramStatus

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    var config = ApiConfig(apiKey = BuildConfig.KIMI_KEY)
        private set
    var isAgentRunning = false
        private set
    private var agentJob: Job? = null
    private val recentFingerprints = ArrayDeque<String>()
    private val recentScreenHashes = ArrayDeque<Int>()
    private val bannedFingerprints = mutableSetOf<String>()
    private var loopRetryCount = 0
    private var consecutiveFailCount = 0
    private var uiState = AgentUiState()

    private val dpmBridge by lazy { DpmBridge(appContext) }
    private lateinit var chatDao: ChatMessageDao

    private fun screenshotSuccessMessage(session: RemoteSession?, fileName: String): String {
        val base = "截图已保存：Pictures/Andclaw/$fileName"
        return base + when (session?.channel) {
            RemoteChannel.TELEGRAM -> "（远程已发送到 Telegram）"
            RemoteChannel.CLAWBOT -> "（ClawBot 暂不支持图片远程回传；本地已保存，应用将尝试向远端发送文本说明）"
            RemoteChannel.FEISHU -> "（飞书暂不支持图片远程回传；本地已保存）"
            else -> ""
        }
    }

    /** 拍照/录像/录音/录屏等远程回传后的补充说明（与 [RemoteBridgeManager] 媒体策略一致）。 */
    private fun appendRemoteBinaryMediaNote(session: RemoteSession?, base: String): String {
        val suffix = when (session?.channel) {
            RemoteChannel.TELEGRAM -> "（已发送到 Telegram）"
            RemoteChannel.CLAWBOT -> "（已保存到本地；ClawBot 暂不支持该类型远程回传，应用将尝试向远端发送文本说明）"
            RemoteChannel.FEISHU -> "（已保存到本地；飞书暂不支持该类型远程回传）"
            else -> ""
        }
        return base + suffix
    }

    fun init(context: Context, dao: ChatMessageDao, bridge: IRemoteBridgeService) {
        appContext = context.applicationContext
        chatDao = dao
        remoteBridge = bridge
        remoteBridge.setTelegramInboundHandler { msg ->
            handleTelegramCommand(msg.chatId, msg.messageId, msg.text)
        }
        remoteBridge.setClawBotInboundHandler { msg ->
            handleClawBotCommand(msg)
        }
        remoteBridge.setFeishuInboundHandler { msg ->
            handleFeishuCommand(msg)
        }
        migrateOldProviderKeys()
        restoreConfig()
        loadHistory()
    }

    private fun loadHistory() {
        scope.launch(Dispatchers.IO) {
            val entities = chatDao.getAll()
            val msgs = entities.map { e ->
                val action = e.actionJson?.let {
                    try { gson.fromJson(it, AiAction::class.java) } catch (_: Exception) { null }
                }
                ChatMessage(role = e.role, content = e.content, action = action, timestamp = e.timestamp, id = e.id)
            }
            _messages.value = msgs
        }
    }

    private fun migrateOldProviderKeys() {
        val oldPrefs = appContext.getSharedPreferences("ai_provider_keys", Context.MODE_PRIVATE)
        val allEntries = oldPrefs.all
        if (allEntries.isEmpty()) return

        val prefs = getPrefs()
        val editor = prefs.edit()
        for ((key, value) in allEntries) {
            if (!prefs.contains(key)) {
                editor.putString(key, value as? String ?: "")
            }
        }
        editor.apply()
        oldPrefs.edit().clear().apply()
        Log.d(TAG, "migrateOldProviderKeys: migrated ${allEntries.size} entries")
    }

    private fun restoreConfig() {
        val prefs = getPrefs()
        val savedProvider = prefs.getString("ai_provider", null)
        val apiKey = if (savedProvider != null) {
            prefs.getString("ai_api_key", null)
                ?: loadProviderKey(savedProvider)
        } else {
            loadProviderKey("Kimi Code").ifEmpty { config.apiKey }
        }
        config = ApiConfig(
            provider = savedProvider ?: config.provider,
            apiKey = apiKey,
            apiUrl = prefs.getString("ai_api_url", config.apiUrl) ?: config.apiUrl,
            model = prefs.getString("ai_model", config.model) ?: config.model
        )
        Log.d(TAG, "restoreConfig: provider=${config.provider}, apiKey=${Utils.maskKey(config.apiKey)}")
    }

    private fun persistConfig() {
        getPrefs().edit()
            .putString("ai_provider", config.provider)
            .putString("ai_api_key", config.apiKey)
            .putString("ai_api_url", config.apiUrl)
            .putString("ai_model", config.model)
            .apply()
    }

    override val provider: String get() = config.provider
    override val apiUrl: String get() = config.apiUrl
    override val apiKey: String get() = config.apiKey
    override val model: String get() = config.model
    override val defaultApiKey: String get() = BuildConfig.KIMI_KEY

    override fun updateConfig(provider: String, apiUrl: String, apiKey: String, model: String) {
        config = config.copy(provider = provider, apiUrl = apiUrl, apiKey = apiKey, model = model)
        persistConfig()
    }

    override fun saveProviderKey(provider: String, key: String) {
        if (provider.isNotBlank() && key.isNotBlank()) {
            getPrefs().edit().putString("api_key_$provider", key).apply()
        }
    }

    override fun loadProviderKey(provider: String): String {
        return getPrefs().getString("api_key_$provider", "") ?: ""
    }

    fun getPrefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun syncLegacyTgChatIdFromSession(session: RemoteSession?) {
        tgActiveChatId = when {
            session == null -> 0L
            session.channel == RemoteChannel.TELEGRAM -> session.sessionKey.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    // --- ITgBridgeService ---

    override fun startBridge() {
        remoteBridge.startEligibleBridges()
    }

    override fun stopBridge() {
        remoteBridge.stopTelegramBridge()
    }

    private suspend fun handleTelegramCommand(chatId: Long, msgId: Long, text: String) {
        val telegramSession = RemoteSession(
            channel = RemoteChannel.TELEGRAM,
            sessionKey = chatId.toString(),
            messageId = msgId.toString(),
        )
        when (text) {
            "/status" -> {
                val allowedId = channelConfig.getTgChatId()
                val accessInfo = if (allowedId == 0L) "⚠️ 未设置 Chat ID 白名单" else "✅ Chat ID 已锁定"
                val agentInfo = if (isAgentRunning) "▶️ Agent 运行中: ${uiState.userInput}" else "⏸ Agent 空闲"
                val body = "Andclaw 状态\n$agentInfo\n$accessInfo\n你的 Chat ID: $chatId"
                RemoteOutboundHelper.sendText(
                    remoteBridge, telegramSession, body, replyToMessageId = msgId
                )
            }
            "/stop" -> {
                withContext(Dispatchers.Main) { stopAgent() }
                RemoteOutboundHelper.sendText(
                    remoteBridge, telegramSession, "✅ 已停止当前任务", replyToMessageId = msgId
                )
            }
            else -> {
                val busy = withContext(Dispatchers.Main) { isAgentRunning to uiState.userInput }
                if (busy.first) {
                    RemoteOutboundHelper.sendText(
                        remoteBridge, telegramSession,
                        "⏳ Agent 正在执行上一任务，不会开始新任务。请稍后或发送 /stop 停止。进行中的任务：${busy.second}",
                        replyToMessageId = msgId
                    )
                    return
                }
                RemoteOutboundHelper.sendTyping(remoteBridge, telegramSession)
                withContext(Dispatchers.Main) { startAgent(text, remoteSession = telegramSession) }
            }
        }
    }

    private suspend fun handleClawBotCommand(msg: RemoteIncomingMessage) {
        val clawSession = RemoteSession(
            channel = RemoteChannel.CLAWBOT,
            sessionKey = msg.sessionKey,
            replyToken = msg.replyToken,
            userId = msg.senderId,
            messageId = msg.messageId,
            accountId = msg.accountId,
        )
        when (msg.text.trim()) {
            "/status" -> {
                val agentInfo = if (isAgentRunning) "▶️ Agent 运行中: ${uiState.userInput}" else "⏸ Agent 空闲"
                val body = "Andclaw 状态\n$agentInfo\n会话: ${msg.sessionKey}"
                RemoteOutboundHelper.sendText(remoteBridge, clawSession, body, replyToMessageId = null)
            }
            "/stop" -> {
                withContext(Dispatchers.Main) { stopAgent() }
                RemoteOutboundHelper.sendText(
                    remoteBridge, clawSession, "✅ 已停止当前任务", replyToMessageId = null
                )
            }
            else -> {
                val busy = withContext(Dispatchers.Main) { isAgentRunning to uiState.userInput }
                if (busy.first) {
                    RemoteOutboundHelper.sendText(
                        remoteBridge, clawSession,
                        "⏳ Agent 正在执行上一任务，不会开始新任务。请稍后或发送 /stop 停止。进行中的任务：${busy.second}",
                        replyToMessageId = null
                    )
                    return
                }
                RemoteOutboundHelper.sendTyping(remoteBridge, clawSession)
                withContext(Dispatchers.Main) { startAgent(msg.text, remoteSession = clawSession) }
            }
        }
    }

    private suspend fun handleFeishuCommand(msg: FeishuInboundMessage) {
        Log.d(TAG, "Feishu message received: ${msg.text.take(100)}, chatId=${msg.chatId}, sender=${msg.senderId}")
        val feishuSession = RemoteSession(
            channel = RemoteChannel.FEISHU,
            sessionKey = msg.chatId,
            replyToken = msg.parentMessageId ?: msg.messageId,
            userId = msg.senderId,
            messageId = msg.messageId,
            displayName = msg.senderType
        )
        when (msg.text.trim()) {
            "/status" -> {
                val agentInfo = if (isAgentRunning) "▶️ Agent 运行中: ${uiState.userInput}" else "⏸ Agent 空闲"
                val body = "Andclaw 状态\n$agentInfo\n会话: ${msg.chatId}"
                RemoteOutboundHelper.sendText(remoteBridge, feishuSession, body, replyToMessageId = null)
            }
            "/stop" -> {
                withContext(Dispatchers.Main) { stopAgent() }
                RemoteOutboundHelper.sendText(
                    remoteBridge, feishuSession, "✅ 已停止当前任务", replyToMessageId = null
                )
            }
            else -> {
                val busy = withContext(Dispatchers.Main) { isAgentRunning to uiState.userInput }
                if (busy.first) {
                    RemoteOutboundHelper.sendText(
                        remoteBridge, feishuSession,
                        "⏳ Agent 正在执行上一任务，不会开始新任务。请稍后或发送 /stop 停止。进行中的任务：${busy.second}",
                        replyToMessageId = null
                    )
                    return
                }
                RemoteOutboundHelper.sendTyping(remoteBridge, feishuSession)
                withContext(Dispatchers.Main) { startAgent(msg.text, remoteSession = feishuSession) }
            }
        }
    }

    // --- Agent Logic ---

    fun startAgent(input: String, remoteSession: RemoteSession? = null) {
        _activeRemoteSession = remoteSession
        syncLegacyTgChatIdFromSession(remoteSession)

        addMessage("user", input)
        isAgentRunning = true
        uiState = uiState.copy(isRunning = true, userInput = input)
        recentFingerprints.clear()
        recentScreenHashes.clear()
        bannedFingerprints.clear()
        loopRetryCount = 0
        consecutiveFailCount = 0

        Log.d(TAG, "startAgent: provider=${config.provider}, model=${config.model}, apiUrl=${config.apiUrl}, apiKey=${Utils.maskKey(config.apiKey)}")

        agentJob = scope.launch {
            executeAgentStep(input)
        }
    }

    fun stopAgent() {
        isAgentRunning = false
        uiState = uiState.copy(isRunning = false, status = "Agent Stopped.")
        agentJob?.cancel()
        _activeRemoteSession = null
        syncLegacyTgChatIdFromSession(null)
    }

    private suspend fun executeAgentStep(
        userInput: String,
        screenshotBase64: String? = null,
        loopWarning: String? = null,
    ) {
        if (!isAgentRunning) return

        RemoteOutboundHelper.sendTyping(remoteBridge, activeRemoteSession)

        val svc = AgentAccessibilityService.instance
        val baseScreenData = svc?.captureScreenHierarchy() ?: "Screen data inaccessible"

        // Track UI state hashes to detect "agent keeps returning to the same screen"
        // regardless of which actions it used to get there.
        val screenHash = baseScreenData.hashCode()
        recentScreenHashes.addLast(screenHash)
        if (recentScreenHashes.size > 10) recentScreenHashes.removeFirst()
        val screenRepeatCount = recentScreenHashes.count { it == screenHash }
        val screenLoopWarning = if (screenRepeatCount >= 3) {
            loopRetryCount++
            "你已经第${screenRepeatCount}次回到了完全相同的界面（界面状态没有变化），" +
                "说明你之前的操作没有产生持续效果，一直在原地打转。" +
                "请彻底放弃当前路径，用完全不同的思路完成任务。"
        } else null

        val effectiveWarning = listOfNotNull(loopWarning, screenLoopWarning)
            .joinToString("\n").ifEmpty { null }
        val screenData = if (effectiveWarning != null) {
            "$baseScreenData\n\n[系统警告] $effectiveWarning"
        } else {
            baseScreenData
        }

        if (screenLoopWarning != null) {
            addMessage("system", "[界面重复] $screenLoopWarning")
            // Always retry with a new path instead of stopping
        }

        var finalScreenshot = screenshotBase64
        if (finalScreenshot == null && svc?.isWebViewContext() == true) {
            finalScreenshot = captureScreenBase64()
        }

        val currentMessages = _messages.value
        val historyContext = currentMessages.takeLast(20).mapNotNull {
            when (it.role) {
                "user" -> mapOf("role" to "user", "content" to it.content)
                "ai" -> it.action?.let { action ->
                    mapOf("role" to "assistant", "content" to gson.toJson(action))
                }
                "system" -> {
                    val content = it.content
                    val shouldKeep = content.startsWith("Intent failed:") ||
                        content.startsWith("Loop detected") ||
                        content.startsWith("Execution Exception:") ||
                        content.startsWith("Error occurred:") ||
                        content.startsWith("AI Request Failed:") ||
                        content.startsWith("Action failed") ||
                        content.startsWith("Action success.") ||
                        content.startsWith("点击") ||
                        content.startsWith("[点击无效") ||
                        content.startsWith("[循环检测") ||
                        content.startsWith("[界面重复")
                    if (shouldKeep) {
                        mapOf("role" to "user", "content" to "System feedback: $content")
                    } else {
                        null
                    }
                }
                else -> null
            }
        }

        try {
            val isDeviceOwner = Util.isDeviceOwner(appContext)
            Log.d(TAG, "executeAgentStep: calling LLM, provider=${config.provider}, apiKey=${Utils.maskKey(config.apiKey)}, historySize=${historyContext.size}, hasScreenshot=${finalScreenshot != null}, hasLoopWarning=${loopWarning != null}")
            var response = Utils.callLLMWithHistory(
                userInput, screenData, historyContext, config, appContext,
                isDeviceOwner = isDeviceOwner,
                screenshotBase64 = finalScreenshot
            )
            var action = Utils.parseAction(response)

            if (action.type == "error" && action.reason?.contains("Failed to parse") == true) {
                Log.w(TAG, "LLM returned non-JSON, retrying with correction prompt")
                val retryHistory = historyContext.toMutableList().apply {
                    add(mapOf("role" to "assistant", "content" to response))
                    add(mapOf("role" to "user", "content" to "Invalid response. Output a single JSON object only, no other text."))
                }
                response = Utils.callLLMWithHistory(
                    userInput, screenData, retryHistory, config, appContext,
                    isDeviceOwner = isDeviceOwner
                )
                action = Utils.parseAction(response)
            }

            if (action.type == "error") {
                addMessage("system", "Error occurred: ${action.reason}")
                stopAgent()
            } else {
                withContext(Dispatchers.Main) {
                    val aiDisplayMessage = "[Progress: ${action.progress ?: "Executing"}]\n${action.reason ?: "Thinking..."}"
                    addMessage("ai", aiDisplayMessage, action)
                    handleAction(action)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                addMessage("system", "AI Request Failed: ${e.message}")
                stopAgent()
            }
        }
    }

    private fun handleAction(action: AiAction) {
        if (!isAgentRunning) return

        val fingerprint = when (action.type) {
            AiAction.TYPE_HTTP_REQUEST -> "${action.type}_${action.data}_${action.httpMethod}"
            else -> "${action.type}_${action.x}_${action.y}"
        }
        // Check banned fingerprints before adding to history
        if (fingerprint in bannedFingerprints) {
            val actionDesc = describeRepeatedAction(action)
            val bannedList = bannedFingerprints.joinToString("；")
            scope.launch {
                val screenshot = captureScreenBase64()
                val warning = "你尝试执行的动作【$actionDesc】已被列入禁止列表，因为你之前重复执行过它导致循环。" +
                    "当前所有禁止动作：$bannedList。请根据截图选择一个全新的、从未执行过的动作来推进任务。"
                addMessage("system", "[禁止动作拦截] $warning", screenshotBase64 = screenshot)
                executeAgentStep(uiState.userInput, screenshotBase64 = screenshot, loopWarning = warning)
            }
            return
        }

        recentFingerprints.addLast(fingerprint)
        if (recentFingerprints.size > 12) recentFingerprints.removeFirst()

        val loopDescription = detectLoop(recentFingerprints, fingerprint)
        if (loopDescription != null) {
            // Add all fingerprints involved in the loop to the banned set
            recentFingerprints.toSet().forEach { bannedFingerprints.add(it) }

            loopRetryCount++
            val actionDesc = describeRepeatedAction(action)
            val bannedList = bannedFingerprints.joinToString("；")
            val isFirstLoop = loopRetryCount == 1
            scope.launch {
                val screenshot = if (!isFirstLoop) captureScreenBase64() else null
                val warning = if (isFirstLoop) {
                    "你陷入了循环（$loopDescription），检测到的重复动作已被加入禁止列表：$bannedList。" +
                        "请停止当前路径，重新分析界面并尝试完全不同的方法，禁止执行上述任何动作。"
                } else {
                    "你在收到循环警告后依然陷入了循环（$loopDescription），当前禁止动作列表：$bannedList。" +
                        "请根据截图彻底换一条路径，这些动作已永久禁止，不得再次执行。"
                }
                val tag = if (isFirstLoop) "[循环检测]" else "[循环检测-第${loopRetryCount}次]"
                addMessage("system", "$tag $warning", screenshotBase64 = screenshot)
                executeAgentStep(uiState.userInput, screenshotBase64 = screenshot, loopWarning = warning)
            }
            return
        }

        when (action.type) {
            AiAction.TYPE_INTENT -> {
                addMessage("ai", action.reason ?: "I will use a system shortcut.", action)
                executeIntent(action)

                val isTerminal = action.action?.let {
                    it.contains("ALARM") || it.contains("SEND")
                } ?: false
                if (isTerminal) {
                    addMessage("system", "Task dispatched via system.")
                    stopAgent()
                } else {
                    addMessage("system", "App opened, checking next step...")
                    isAgentRunning = true
                    scope.launch {
                        AgentAccessibilityService.instance?.waitForUiSettle(2500L) ?: delay(2500)
                        executeAgentStep(uiState.userInput)
                    }
                }
            }

            AiAction.TYPE_CLICK,
            AiAction.TYPE_SWIPE,
            AiAction.TYPE_LONG_PRESS,
            AiAction.TYPE_TEXT_INPUT,
            AiAction.TYPE_GLOBAL_ACTION,
            AiAction.TYPE_SCREENSHOT,
            AiAction.TYPE_DOWNLOAD,
            AiAction.TYPE_HTTP_REQUEST,
            AiAction.TYPE_CAMERA,
            AiAction.TYPE_SCREEN_RECORD,
            AiAction.TYPE_VOLUME,
            AiAction.TYPE_AUDIO_RECORD,
            AiAction.TYPE_WAKE_SCREEN -> {
                performConfirmedAction(action)
            }

            AiAction.TYPE_DPM -> {
                val dpmAction = action.dpmAction
                if (dpmAction.isNullOrEmpty()) {
                    addMessage("system", "DPM action name missing")
                    stopAgent()
                    return
                }
                performConfirmedAction(action)
            }

            AiAction.TYPE_WAIT -> {
                val waitMs = if (action.duration > 0) action.duration.coerceAtMost(5000) else 1500L
                addMessage("system", "Waiting for UI update (max ${waitMs}ms)...")
                scope.launch {
                    AgentAccessibilityService.instance?.waitForUiSettle(waitMs) ?: delay(waitMs)
                    executeAgentStep(uiState.userInput)
                }
            }

            AiAction.TYPE_FINISH -> {
                addMessage("system", "Finished.")
                stopAgent()
            }

            AiAction.TYPE_ERROR -> {
                addMessage("system", "AI Error: ${action.reason}")
                stopAgent()
            }

            else -> {
                val warning = "你返回了未知动作类型：${action.type}。请只使用支持的动作类型，重新分析界面并选择正确的动作。"
                addMessage("system", "[未知动作] $warning")
                scope.launch { executeAgentStep(uiState.userInput, loopWarning = warning) }
            }
        }
    }

    fun performConfirmedAction(action: AiAction) {
        if (!isAgentRunning) return

        scope.launch(Dispatchers.IO) {
            var success = false
            var outputMsg: String? = null
            try {
                when (action.type) {
                    AiAction.TYPE_CLICK -> {
                        val svc = AgentAccessibilityService.instance
                        val beforeHash = svc?.captureScreenHierarchy()?.hashCode()
                        withContext(Dispatchers.Main) {
                            svc?.click(action.x, action.y)
                        }
                        AgentAccessibilityService.instance?.waitForUiSettle(1500L) ?: delay(1500)
                        val afterHash = svc?.captureScreenHierarchy()?.hashCode()
                        if (beforeHash != null && beforeHash == afterHash) {
                            // UI unchanged: always try to capture screenshot; fall back gracefully.
                            val screenshot = captureScreenBase64()
                            consecutiveFailCount++
                            val actionDesc = describeRepeatedAction(action)
                            val hasScreenshot = screenshot != null
                            Log.d(TAG, "click no effect #$consecutiveFailCount, screenshot=${if (hasScreenshot) "ok" else "null"}")
                            if (consecutiveFailCount >= 4) {
                                val loopWarning = "你已经连续${consecutiveFailCount}次点击无效（$actionDesc）。" +
                                    "彻底放弃点击该位置，必须换一种完全不同的方式来完成任务，例如滚动、返回、使用其他入口等。"
                                withContext(Dispatchers.Main) {
                                    addMessage("system", "[点击无效-强制换路] $loopWarning", screenshotBase64 = screenshot)
                                }
                                executeAgentStep(uiState.userInput, screenshotBase64 = screenshot, loopWarning = loopWarning)
                            } else if (consecutiveFailCount >= 3) {
                                // Third failure: embed a strong loopWarning in the UI tree context.
                                val loopWarning = "你已经连续3次执行了同一个无效动作：$actionDesc。" +
                                    "这个位置点击没有任何效果，" +
                                    (if (hasScreenshot) "请根据截图重新观察界面，" else "请重新分析当前UI界面，") +
                                    "找到正确的元素或换一种完全不同的方式来完成任务。"
                                withContext(Dispatchers.Main) {
                                    addMessage("system", "[点击无效-第3次] $loopWarning", screenshotBase64 = screenshot)
                                }
                                executeAgentStep(uiState.userInput, screenshotBase64 = screenshot, loopWarning = loopWarning)
                            } else {
                                val loopWarning = "你刚才执行的动作没有任何效果（第${consecutiveFailCount}次）：$actionDesc。" +
                                    "禁止再次点击坐标 (${action.x}, ${action.y})。" +
                                    if (hasScreenshot) "请根据截图重新观察界面，找到正确的元素位置。"
                                    else "请根据UI树重新分析界面，找到正确的元素或换一种方法。"
                                val failNote = "点击 (${action.x},${action.y}) 没有可见效果（第${consecutiveFailCount}次）。$actionDesc"
                                withContext(Dispatchers.Main) {
                                    addMessage("system", failNote, screenshotBase64 = screenshot)
                                }
                                executeAgentStep(uiState.userInput, screenshotBase64 = screenshot, loopWarning = loopWarning)
                            }
                            return@launch
                        } else {
                            success = true
                        }
                    }

                    AiAction.TYPE_SWIPE -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val dur = if (action.duration > 0) action.duration else 300L
                            withContext(Dispatchers.Main) {
                                svc.swipe(action.x, action.y, action.endX, action.endY, dur)
                            }
                            success = true
                        }
                    }

                    AiAction.TYPE_LONG_PRESS -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val dur = if (action.duration > 0) action.duration else 1000L
                            withContext(Dispatchers.Main) {
                                svc.longPress(action.x, action.y, dur)
                            }
                            success = true
                        }
                    }

                    AiAction.TYPE_TEXT_INPUT -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else if (action.text.isNullOrEmpty()) {
                            outputMsg = "text field is empty"
                        } else {
                            val result = withContext(Dispatchers.Main) {
                                svc.inputText(action.text)
                            }
                            success = true
                            // Read the field's actual content to verify the text was really written.
                            // ACTION_SET_TEXT can return true at the framework level while the app
                            // silently ignores it (e.g. Bilibili comment box, WebView inputs).
                            val actualText = withContext(Dispatchers.Main) { svc.getEditableText() }
                            val textVerified = !actualText.isNullOrBlank()
                            outputMsg = when {
                                textVerified -> "text_input success. Input field now contains: \"$actualText\""
                                result -> "text_input API reported success but the input field appears empty or unreadable (app may use custom input handling). Current field content: \"${actualText ?: "unreadable"}\". Try: 1) click the field first then retry, 2) check if a different input method is needed."
                                else -> "text_input failed: no editable field found or focus failed. Try clicking the input field first or use a different approach."
                            }
                        }
                    }

                    AiAction.TYPE_GLOBAL_ACTION -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val actionId = when (action.globalAction) {
                                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                                "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                                else -> {
                                    outputMsg = "Unknown global_action: ${action.globalAction}"
                                    -1
                                }
                            }
                            if (actionId >= 0) {
                                withContext(Dispatchers.Main) { svc.globalAction(actionId) }
                                success = true
                            }
                        }
                    }

                    AiAction.TYPE_SCREENSHOT -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val latch = CountDownLatch(1)
                            var bitmap: Bitmap? = null
                            withContext(Dispatchers.Main) {
                                svc.captureScreenshot { bmp ->
                                    bitmap = bmp
                                    latch.countDown()
                                }
                            }
                            latch.await(5, TimeUnit.SECONDS)
                            if (bitmap != null) {
                                val fileName = "screenshot_${System.currentTimeMillis()}.png"
                                val values = ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Andclaw")
                                }
                                appContext.contentResolver.insert(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                                )?.let { uri ->
                                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                                        bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                }

                                activeRemoteSession?.let { session ->
                                    val baos = ByteArrayOutputStream()
                                    bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                    RemoteOutboundHelper.sendPhoto(
                                        remoteBridge, session,
                                        baos.toByteArray(), caption = fileName, fileName = fileName
                                    )
                                }

                                success = true
                                outputMsg = screenshotSuccessMessage(activeRemoteSession, fileName)
                            } else {
                                outputMsg = "Screenshot failed (API 30+ required)"
                            }
                        }
                    }

                    AiAction.TYPE_DOWNLOAD -> {
                        if (action.data.isNullOrEmpty()) {
                            outputMsg = "Download URL (data) is empty"
                        } else {
                            try {
                                val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                val fileName = action.data.substringAfterLast("/")
                                    .substringBefore("?")
                                    .ifEmpty { "download_${System.currentTimeMillis()}" }
                                val request = DownloadManager.Request(
                                    Uri.parse(action.data)
                                ).apply {
                                    setTitle("Andclaw Download")
                                    setDescription(fileName)
                                    setNotificationVisibility(
                                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                    )
                                    setDestinationInExternalPublicDir("Download", fileName)
                                }
                                val downloadId = dm.enqueue(request)
                                success = true
                                outputMsg = "Download started: $fileName (ID=$downloadId)"
                            } catch (e: Exception) {
                                outputMsg = "Download failed: ${e.message}"
                            }
                        }
                    }

                    AiAction.TYPE_HTTP_REQUEST -> {
                        val (ok, msg) = Utils.executeHttpRequest(action)
                        success = ok
                        outputMsg = msg
                    }

                    AiAction.TYPE_DPM -> {
                        val dpmResult = dpmBridge.execute(action.dpmAction ?: "", action.extras)
                        success = dpmResult.success
                        outputMsg = "DPM ${action.dpmAction}: ${dpmResult.message}"
                    }

                    AiAction.TYPE_CAMERA -> {
                        val cameraAction = action.cameraAction
                        if (cameraAction.isNullOrEmpty()) {
                            outputMsg = "camera_action field is empty"
                        } else {
                            CameraActivity.lastResult = null
                            val cameraIntent = Intent(appContext, CameraActivity::class.java).apply {
                                putExtra(CameraActivity.EXTRA_CAMERA_ACTION, cameraAction)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            appContext.startActivity(cameraIntent)

                            if (cameraAction == CameraActivity.ACTION_START_VIDEO) {
                                delay(3000)
                                success = true
                                outputMsg = CameraActivity.lastResult ?: "Video recording started"
                            } else {
                                var waited = 0L
                                while (CameraActivity.lastResult == null && waited < 15000) {
                                    delay(500)
                                    waited += 500
                                }
                                val result = CameraActivity.lastResult
                                if (result != null) {
                                    success = true
                                    outputMsg = result

                                    activeRemoteSession?.let { session ->
                                        when (cameraAction) {
                                            CameraActivity.ACTION_TAKE_PHOTO -> {
                                                val uri = CameraActivity.lastPhotoUri
                                                if (uri != null) {
                                                    try {
                                                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                            RemoteOutboundHelper.sendPhoto(
                                                                remoteBridge, session,
                                                                input.readBytes(), caption = null, fileName = "photo.jpg"
                                                            )
                                                            outputMsg = appendRemoteBinaryMediaNote(session, result)
                                                        }
                                                    } catch (_: Exception) { }
                                                }
                                            }
                                            CameraActivity.ACTION_STOP_VIDEO -> {
                                                val uri = CameraActivity.lastVideoUri
                                                if (uri != null) {
                                                    try {
                                                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                            RemoteOutboundHelper.sendVideo(
                                                                remoteBridge, session,
                                                                input.readBytes(), caption = null, fileName = "video.mp4"
                                                            )
                                                            outputMsg = appendRemoteBinaryMediaNote(session, result)
                                                        }
                                                    } catch (_: Exception) { }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    outputMsg = "Camera operation timed out"
                                }
                            }
                        }
                    }

                    AiAction.TYPE_AUDIO_RECORD -> {
                        val recordAction = action.audioRecordAction
                        if (recordAction.isNullOrEmpty()) {
                            outputMsg = "audio_record_action field is empty"
                        } else {
                            AudioRecordActivity.lastResult = null
                            val recordIntent = Intent(appContext, AudioRecordActivity::class.java).apply {
                                putExtra(AudioRecordActivity.EXTRA_AUDIO_RECORD_ACTION, recordAction)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            appContext.startActivity(recordIntent)

                            if (recordAction == AudioRecordActivity.ACTION_START_RECORD) {
                                delay(3000)
                                success = true
                                outputMsg = AudioRecordActivity.lastResult ?: "Audio recording started"
                            } else {
                                var waited = 0L
                                while (AudioRecordActivity.lastResult == null && waited < 15000) {
                                    delay(500)
                                    waited += 500
                                }
                                val result = AudioRecordActivity.lastResult
                                if (result != null) {
                                    success = true
                                    outputMsg = result

                                    activeRemoteSession?.let { session ->
                                        val uri = AudioRecordActivity.lastAudioUri
                                        if (uri != null) {
                                            try {
                                                appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                    RemoteOutboundHelper.sendAudio(
                                                        remoteBridge, session,
                                                        input.readBytes(), caption = null, fileName = "audio.m4a"
                                                    )
                                                    outputMsg = appendRemoteBinaryMediaNote(session, result)
                                                }
                                            } catch (_: Exception) { }
                                        }
                                    }
                                } else {
                                    outputMsg = "Audio record operation timed out"
                                }
                            }
                        }
                    }

                    AiAction.TYPE_SCREEN_RECORD -> {
                        val recordAction = action.screenRecordAction
                        if (recordAction.isNullOrEmpty()) {
                            outputMsg = "screen_record_action field is empty"
                        } else if (recordAction == ScreenRecordActivity.ACTION_STOP) {
                            if (!ScreenRecordService.isRecording) {
                                outputMsg = "当前没有在录屏"
                            } else {
                                val stopIntent = Intent(appContext, ScreenRecordService::class.java)
                                stopIntent.action = "STOP"
                                appContext.startService(stopIntent)
                                delay(2000)
                                success = true
                                val filePath = ScreenRecordService.lastRecordedFile
                                val stoppedMsg = "录屏已停止, 文件: ${filePath ?: "unknown"}"
                                outputMsg = stoppedMsg

                                if (filePath != null) {
                                    activeRemoteSession?.let { session ->
                                        try {
                                            val file = File(filePath)
                                            if (file.exists()) {
                                                RemoteOutboundHelper.sendVideo(
                                                    remoteBridge, session,
                                                    file.readBytes(), caption = null, fileName = file.name
                                                )
                                                outputMsg = appendRemoteBinaryMediaNote(session, stoppedMsg)
                                            }
                                        } catch (_: Exception) { }
                                    }
                                }
                            }
                        } else {
                            if (ScreenRecordService.isRecording) {
                                success = true
                                outputMsg = "录屏已在进行中"
                            } else {
                                ScreenRecordActivity.lastResult = null
                                val recordIntent = Intent(appContext, ScreenRecordActivity::class.java).apply {
                                    putExtra(ScreenRecordActivity.EXTRA_RECORD_ACTION, recordAction)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                appContext.startActivity(recordIntent)
                                delay(1500)
                                success = true
                                outputMsg = "录屏授权对话框已弹出，请在下一步点击「立即开始」按钮完成授权"
                            }
                        }
                    }

                    AiAction.TYPE_VOLUME -> {
                        val volumeAction = action.volumeAction
                        if (volumeAction.isNullOrEmpty()) {
                            outputMsg = "volume_action field is empty"
                        } else {
                            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val streamType = when (action.extras?.get("stream")?.toString()) {
                                "ring" -> AudioManager.STREAM_RING
                                "notification" -> AudioManager.STREAM_NOTIFICATION
                                "alarm" -> AudioManager.STREAM_ALARM
                                "system" -> AudioManager.STREAM_SYSTEM
                                else -> AudioManager.STREAM_MUSIC
                            }
                            val streamName = action.extras?.get("stream")?.toString() ?: "music"
                            when (volumeAction) {
                                "set" -> {
                                    val maxVol = audioManager.getStreamMaxVolume(streamType)
                                    val level = when (val v = action.extras?.get("level")) {
                                        is Number -> v.toInt()
                                        is String -> v.toIntOrNull() ?: 50
                                        else -> 50
                                    }
                                    val vol = (level * maxVol / 100).coerceIn(0, maxVol)
                                    audioManager.setStreamVolume(streamType, vol, 0)
                                    success = true
                                    outputMsg = "音量已设置: $streamName $vol/$maxVol ($level%)"
                                }
                                "adjust_up" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0)
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    success = true
                                    outputMsg = "音量已调高: $streamName $cur/$max"
                                }
                                "adjust_down" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0)
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    success = true
                                    outputMsg = "音量已调低: $streamName $cur/$max"
                                }
                                "mute" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                                    success = true
                                    outputMsg = "已静音: $streamName"
                                }
                                "unmute" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    success = true
                                    outputMsg = "已取消静音: $streamName $cur/$max"
                                }
                                "get" -> {
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    val pct = if (max > 0) cur * 100 / max else 0
                                    val muted = audioManager.isStreamMute(streamType)
                                    success = true
                                    outputMsg = "当前音量: $streamName $cur/$max ($pct%)${if (muted) " [已静音]" else ""}"
                                }
                                else -> outputMsg = "Unknown volume_action: $volumeAction"
                            }
                        }
                    }

                    AiAction.TYPE_WAKE_SCREEN -> {
                        val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        @Suppress("DEPRECATION")
                        val wakeLock = pm.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "andclaw:wakeup"
                        )
                        wakeLock.acquire(3000L)
                        wakeLock.release()
                        success = true
                        outputMsg = "屏幕已唤醒"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addMessage("system", "Execution Exception: ${e.message}") }
            }

            val finalMsg = outputMsg
            if (success && isAgentRunning) {
                consecutiveFailCount = 0
                withContext(Dispatchers.Main) {
                    val msg = if (finalMsg != null) "Action success.\n$finalMsg" else "Action success. Waiting for UI refresh..."
                    addMessage("system", msg)
                }
                // click already waited inside its handler; other actions still need the settle wait
                if (action.type != AiAction.TYPE_CLICK) {
                    AgentAccessibilityService.instance?.waitForUiSettle(2000L) ?: delay(2000)
                }
                executeAgentStep(uiState.userInput)
            } else if (!isAgentRunning) {
                // agent was stopped externally, do nothing
            } else {
                consecutiveFailCount++
                if (consecutiveFailCount >= 3) {
                    withContext(Dispatchers.Main) {
                        addMessage("system", "Action failed 3 times in a row. Stopping agent. Last error: $finalMsg")
                        stopAgent()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        addMessage("system", "Action failed (attempt $consecutiveFailCount/3): $finalMsg. Retrying with a different approach...")
                    }
                    AgentAccessibilityService.instance?.waitForUiSettle(1500L) ?: delay(1500)
                    executeAgentStep(uiState.userInput)
                }
            }
        }
    }

    private fun executeIntent(action: AiAction) {
        try {
            val pkg = action.packageName
            val cls = action.className
            // When only package is given, prefer getLaunchIntentForPackage which correctly
            // resolves the LAUNCHER activity (plain setPackage without LAUNCHER category fails).
            val intent: Intent = if (!pkg.isNullOrEmpty() && cls.isNullOrEmpty()) {
                val launchIntent = appContext.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    if (!action.data.isNullOrEmpty()) launchIntent.data = action.data.toUri()
                    action.fillIntentExtras(launchIntent)
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launchIntent
                } else {
                    // Package not installed — report clearly and hint at discovery
                    addMessage(
                        "system",
                        "Intent failed: Package \"$pkg\" not found on this device. " +
                            "Use dpm action \"getInstalledPackages\" (extras: {\"filter\":\"user\"}) to list installed apps and find the correct package name."
                    )
                    return
                }
            } else {
                Intent(action.action).also { intent ->
                    if (!action.data.isNullOrEmpty()) intent.data = action.data.toUri()
                    if (!pkg.isNullOrEmpty() && !cls.isNullOrEmpty()) {
                        intent.component = ComponentName(pkg, cls)
                    } else if (!pkg.isNullOrEmpty()) {
                        intent.setPackage(pkg)
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    action.fillIntentExtras(intent)
                }
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            addMessage("system", "Intent failed: ${e.message}")
        }
    }

    // --- Helpers ---

    /**
     * Returns a human-readable loop description if a loop is detected, null otherwise.
     * Detects:
     *  - Single action repeated 4+ times: A A A A
     *  - Period-2 cycle repeated 3+ times: A B A B A B
     *  - Period-3 cycle repeated 2+ times: A B C A B C
     *  - Low-diversity: ≤3 distinct actions in last 10 steps (agent spinning in place)
     */
    private fun detectLoop(fingerprints: ArrayDeque<String>, current: String): String? {
        val list = fingerprints.toList()
        val n = list.size

        // Single action repeated 4+ times in window
        if (list.count { it == current } >= 4) {
            return "同一动作重复${list.count { it == current }}次"
        }

        // Period-2: last 6 entries follow A B A B A B pattern
        if (n >= 6) {
            val tail = list.takeLast(6)
            if (tail[0] == tail[2] && tail[2] == tail[4] &&
                tail[1] == tail[3] && tail[3] == tail[5] &&
                tail[0] != tail[1]
            ) {
                return "两动作交替循环：${tail[0]} ↔ ${tail[1]}"
            }
        }

        // Period-3: last 6 entries follow A B C A B C pattern
        if (n >= 6) {
            val tail = list.takeLast(6)
            if (tail[0] == tail[3] && tail[1] == tail[4] && tail[2] == tail[5] &&
                setOf(tail[0], tail[1], tail[2]).size == 3
            ) {
                return "三动作循环：${tail[0]} → ${tail[1]} → ${tail[2]}"
            }
        }

        // Low-diversity: ≤3 distinct actions across last 10 steps
        if (n >= 10) {
            val tail = list.takeLast(10)
            val distinct = tail.toSet()
            if (distinct.size <= 3) {
                return "最近10步只有${distinct.size}种不同动作，没有实质进展"
            }
        }

        return null
    }

    private fun describeRepeatedAction(action: AiAction): String {
        val detail = when (action.type) {
            AiAction.TYPE_CLICK -> "点击坐标 (${action.x}, ${action.y})"
            AiAction.TYPE_SWIPE -> "滑动：(${action.x}, ${action.y}) → (${action.endX}, ${action.endY})"
            AiAction.TYPE_LONG_PRESS -> "长按坐标 (${action.x}, ${action.y})"
            AiAction.TYPE_TEXT_INPUT -> "输入文本「${action.text}」"
            AiAction.TYPE_GLOBAL_ACTION -> "系统操作「${action.globalAction}」"
            AiAction.TYPE_INTENT -> "启动「${action.packageName ?: action.action}」"
            AiAction.TYPE_DPM -> "DPM 操作「${action.dpmAction}」"
            AiAction.TYPE_DOWNLOAD -> "下载「${action.data}」"
            AiAction.TYPE_HTTP_REQUEST -> "HTTP ${action.httpMethod} 请求「${action.data}」"
            AiAction.TYPE_SCREENSHOT -> "截图"
            AiAction.TYPE_WAIT -> "等待 ${action.duration}ms"
            AiAction.TYPE_CAMERA -> "相机操作「${action.cameraAction}」"
            AiAction.TYPE_SCREEN_RECORD -> "录屏操作「${action.screenRecordAction}」"
            AiAction.TYPE_VOLUME -> "音量操作「${action.volumeAction}」"
            else -> "动作类型「${action.type}」"
        }
        val reason = action.reason?.takeIf { it.isNotBlank() }
        return if (reason != null) "$detail（你的理由：$reason）" else detail
    }

    private suspend fun captureScreenBase64(): String? {
        val svc = AgentAccessibilityService.instance ?: return null
        // takeScreenshot() must be called from the main thread; withContext ensures this
        // even when captureScreenBase64 is invoked from an IO-dispatcher coroutine.
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                svc.captureScreenshot { bitmap ->
                    if (bitmap != null) {
                        val scaled = scaleBitmapToMaxWidth(bitmap, 720)
                        val baos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                        if (scaled !== bitmap) scaled.recycle()
                        cont.resume(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                    } else {
                        cont.resume(null)
                    }
                }
            }
        }
    }

    private fun scaleBitmapToMaxWidth(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val scale = maxWidth.toFloat() / bitmap.width
        val newHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

    fun addMessage(role: String, content: String, action: AiAction? = null, screenshotBase64: String? = null) {
        val msg = ChatMessage(role, content, action, screenshotBase64 = screenshotBase64)
        _messages.update { current -> current + msg }
        Log.d(TAG, "[$role]: $content")

        scope.launch(Dispatchers.IO) {
            val entity = ChatMessageEntity(
                role = msg.role,
                content = msg.content,
                actionJson = action?.let { gson.toJson(it) },
                timestamp = msg.timestamp
            )
            val id = chatDao.insert(entity)
            _messages.update { list ->
                list.map { if (it.timestamp == msg.timestamp && it.role == msg.role && it.id == 0L) it.copy(id = id) else it }
            }
        }

        val sessionSnapshot = activeRemoteSession
        if (RemoteOutboundHelper.shouldAttemptRemoteEcho(role, sessionSnapshot)) {
            scope.launch(Dispatchers.IO) {
                RemoteOutboundHelper.sendText(
                    remoteBridge, sessionSnapshot, "[$role] $content"
                )
            }
        }
    }

    fun deleteMessages(ids: List<Long>) {
        scope.launch(Dispatchers.IO) {
            chatDao.deleteByIds(ids)
            _messages.update { list -> list.filter { it.id !in ids } }
        }
    }

    fun clearAllMessages() {
        scope.launch(Dispatchers.IO) {
            chatDao.deleteAll()
            _messages.value = emptyList()
        }
    }
}
