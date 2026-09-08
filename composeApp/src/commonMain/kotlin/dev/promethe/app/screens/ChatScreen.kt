@file:Suppress("DEPRECATION")

package dev.promethe.app.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*
import dev.promethe.api.AgentProfile
import dev.promethe.api.ChatEvent
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import dev.promethe.app.audio.AudioEngine
import dev.promethe.api.ChatRequest
import dev.promethe.api.FeedbackRequest
import dev.promethe.app.components.AgentReasoningBlock
import dev.promethe.app.components.FeedbackBar
import dev.promethe.app.components.MessageBubble
import dev.promethe.app.components.VoiceOverlay
import dev.promethe.app.screens.viewmodel.VoiceViewModel
import dev.promethe.api.voice.VoiceSessionConfig
import dev.promethe.app.screens.viewmodel.VoiceUiState
import dev.promethe.app.navigation.PlatformStorage
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

private data class DisplayItem(
    val id: String = "${Clock.System.now().toEpochMilliseconds()}_${kotlin.random.Random.nextLong(0, Long.MAX_VALUE)}",
    val event: ChatEvent,
    val isUser: Boolean,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val hasError: Boolean = false,
    val originalMessage: String? = null,
    val isVoice: Boolean = false,
)

internal data class PendingChatApproval(
    val id: String,
    val toolName: String,
    val arguments: String,
    val sessionId: String,
    val persistentAllowed: Boolean,
    val createdAt: Long?,
)

private enum class ChatApprovalScope {
    ONCE,
    SESSION,
    PERSISTENT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    client: PrometheClient,
    onBack: () -> Unit,
) {
    val uiScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<DisplayItem>()) }
    var isStreaming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingApprovals by remember(sessionId) { mutableStateOf(emptyList<PendingChatApproval>()) }
    var resolvingApprovalIds by remember(sessionId) { mutableStateOf(emptySet<String>()) }
    var approvalResolutionErrors by remember(sessionId) { mutableStateOf(emptyMap<String, String>()) }

    val streamingScope = rememberCoroutineScope()

    // Profile selector state
    var profiles by remember { mutableStateOf(listOf<AgentProfile>()) }
    var selectedProfile by remember { mutableStateOf<AgentProfile?>(null) }
    var showProfilePicker by remember { mutableStateOf(false) }

    // Load profiles and restore persisted selection
    LaunchedEffect(Unit) {
        try {
            profiles = client.getAgentProfiles()
            // Restore saved profile for this session, fall back to default
            val savedProfileId = PlatformStorage.read("session_profile_$sessionId")
            selectedProfile = savedProfileId?.let { id -> profiles.find { it.id == id } }
                ?: profiles.find { it.id == "promethe" }
                ?: profiles.firstOrNull()
        } catch (e: Exception) {
            logger.debug(e) { "Failed to load agent profiles" }
        }
    }

    // Track if user has scrolled up
    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisible =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible < totalItems - 2
        }
    }

    // ── Voice assistant state ──
    val voiceVM = remember { VoiceViewModel(wsBaseUrl = client.wsBaseUrl, apiKey = client.getApiKey()) }
    val voiceState by voiceVM.state.collectAsState()
    val voiceTranscripts by voiceVM.transcripts.collectAsState()
    val dictationText by voiceVM.dictationText.collectAsState()

    // ── TTS playback engine (separate from voice session engine) ──
    val ttsAudioEngine = remember { AudioEngine() }
    DisposableEffect(Unit) {
        onDispose { ttsAudioEngine.release() }
    }

    // ── Voice settings from gateway config ──
    var voiceProvider by remember { mutableStateOf("gemini_live") }
    var voiceModel by remember { mutableStateOf("") }
    var voiceVoice by remember { mutableStateOf("Puck") }
    var voiceS2sEnabled by remember { mutableStateOf(true) }
    var voiceSttEnabled by remember { mutableStateOf(false) }
    var voiceTtsEnabled by remember { mutableStateOf(false) }
    var voiceTtsProvider by remember { mutableStateOf("") }
    var voiceTtsVoice by remember { mutableStateOf("") }
    var voiceSttProvider by remember { mutableStateOf("") }
    var voiceSttModel by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val env = client.getConfigEnv()
            voiceProvider = env["voice_s2s_provider"]?.jsonPrimitive?.content ?: "gemini_live"
            voiceModel = env["voice_s2s_model"]?.jsonPrimitive?.content ?: ""
            voiceVoice = env["voice_s2s_voice"]?.jsonPrimitive?.content ?: "Puck"
            voiceS2sEnabled = env["voice_s2s_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
            voiceSttEnabled = env["voice_stt_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            voiceTtsEnabled = env["voice_tts_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            voiceTtsProvider = env["voice_tts_provider"]?.jsonPrimitive?.content ?: ""
            voiceTtsVoice = env["voice_tts_voice"]?.jsonPrimitive?.content ?: ""
            voiceSttProvider = env["voice_stt_provider"]?.jsonPrimitive?.content ?: ""
            voiceSttModel = env["voice_stt_model"]?.jsonPrimitive?.content ?: ""
        } catch (_: Exception) {
        }
    }

    // ── Inject final voice transcripts into chat items ──
    // In STT/dictation mode, user transcripts go to the input field (handled below),
    // NOT as chat bubbles. Only S2S mode shows transcripts as messages.
    val addedVoiceIndices = remember { mutableSetOf<Int>() }
    LaunchedEffect(voiceTranscripts) {
        voiceTranscripts.forEachIndexed { index, transcript ->
            if (transcript.isFinal && index !in addedVoiceIndices) {
                // Skip user transcripts in STT mode — they go to the text input instead
                if (voiceState.isDictating && transcript.speaker == "user") return@forEachIndexed
                addedVoiceIndices.add(index)
                val role = if (transcript.speaker == "user") "user" else "response"
                items = items + DisplayItem(
                    event = ChatEvent(type = role, content = transcript.text),
                    isUser = transcript.speaker == "user",
                    isVoice = true,
                )
            }
        }
    }

    // Load existing messages
    LaunchedEffect(sessionId) {
        try {
            val history = client.getMessages(sessionId)
            items =
                history.map { ev ->
                    DisplayItem(
                        event = ev,
                        isUser = ev.type == "user",
                        timestamp = ev.timestamp ?: Clock.System.now().toEpochMilliseconds(),
                    )
                }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to load message history for session $sessionId" }
        }
    }

    // Approval requests are interactive and session-scoped, so keep this polling
    // loop tied to the visible chat composition.
    LaunchedEffect(sessionId, client) {
        while (true) {
            try {
                val polled = pendingChatApprovalsForSession(client.getPendingApprovals(), sessionId)
                pendingApprovals = polled.filterNot { it.id in resolvingApprovalIds }
                approvalResolutionErrors = approvalResolutionErrors.filterKeys { errorId ->
                    polled.any { it.id == errorId }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug(e) { "Failed to poll approvals for session $sessionId" }
            }
            delay(APPROVAL_POLL_INTERVAL_MS)
        }
    }

    // Group intermediate steps
    val groupedItems = remember(items, isStreaming) {
        groupChatItems(items, isStreaming)
    }
    val lastChatListIndex =
        groupedItems.size + pendingApprovals.size + (if (isStreaming) 1 else 0) - 1

    // Auto-scroll when new messages arrive (only if user is near bottom)
    LaunchedEffect(lastChatListIndex) {
        if (lastChatListIndex >= 0 && !showScrollToBottom) {
            listState.animateScrollToItem(lastChatListIndex)
        }
    }

    // Send message logic (extracted for reuse by retry)
    fun sendMessage(text: String) {
        if (text.isEmpty() || isStreaming) return
        inputText = ""
        errorMessage = null

        // Optimistic: add user message
        items = items +
            DisplayItem(
                event = ChatEvent(type = "user", content = text),
                isUser = true,
            )

        isStreaming = true
        // Use the detached streaming scope — it survives even if the user
        // navigates away from ChatScreen during the stream.
        streamingScope.launch {
            try {
                client.chatStream(
                    sessionId,
                    text,
                    profileId = selectedProfile?.id,
                    profileProvider = selectedProfile?.provider,
                    profileModel = selectedProfile?.model,
                ).collect { event ->
                    // Skip lifecycle events (done) and empty non-reasoning events
                    if (event.type == "done") return@collect
                    if (event.type == "observation") return@collect

                    // ── Auto-play TTS audio from tool pipeline ──
                    if (event.type == "audio_message") {
                        val audioData = event.metadata?.get("data")
                            ?.jsonPrimitive?.contentOrNull
                        val sampleRate = event.metadata?.get("sampleRate")
                            ?.jsonPrimitive?.intOrNull ?: 24000
                        if (audioData != null) {
                            logger.info { "Auto-playing TTS audio from A2A (${audioData.length} chars, ${sampleRate}Hz)" }
                            ttsAudioEngine.playChunk(audioData, sampleRate)
                        }
                        return@collect // Don't add audio events as chat bubbles
                    }

                    if (event.content.isNullOrBlank() &&
                        event.type !in listOf("thought", "action", "observation")
                    ) {
                        return@collect
                    }
                    items = items + DisplayItem(event = event, isUser = false)
                }
            } catch (e: CancellationException) {
                throw e // never swallow CancellationException
            } catch (e: Exception) {
                logger.warn(e) { "Chat stream failed for session $sessionId, falling back to REST" }
                try {
                    val response = client.sendMessage(ChatRequest(sessionId, text))
                    items = items + DisplayItem(event = response, isUser = false)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    errorMessage = "Connection error: ${e2.message}" // TODO: i18n
                    // Mark last user message as failed for retry
                    items =
                        items.mapIndexed { index, item ->
                            if (index == items.lastIndex && item.isUser) {
                                item.copy(hasError = true, originalMessage = text)
                            } else {
                                item
                            }
                        }
                }
            } finally {
                isStreaming = false
            }
        }
    }

    fun resolveApproval(
        approval: PendingChatApproval,
        approved: Boolean,
        scope: ChatApprovalScope,
    ) {
        if (approval.id in resolvingApprovalIds) return

        resolvingApprovalIds = resolvingApprovalIds + approval.id
        approvalResolutionErrors = approvalResolutionErrors - approval.id
        pendingApprovals = pendingApprovals.filterNot { it.id == approval.id }

        uiScope.launch {
            try {
                client.respondToApproval(approval.id, approved, scope.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to resolve approval ${approval.id}" }
                pendingApprovals = (pendingApprovals + approval).distinctBy { it.id }
                approvalResolutionErrors = approvalResolutionErrors +
                    (approval.id to e.message.orEmpty())
            } finally {
                resolvingApprovalIds = resolvingApprovalIds - approval.id
            }
        }
    }

    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.chat_session_label), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = sessionId.removePrefix("session-").takeLast(8),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        // Connection status dot
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isStreaming) {
                                            colors.secondary
                                        } else {
                                            androidx.compose.ui.graphics
                                                .Color(0xFF22C55E)
                                        },
                                    ),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.surface,
                        titleContentColor = colors.onSurface,
                    ),
            )
        },
        containerColor = colors.background,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Message list ────────────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat_messages"),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(groupedItems, key = { it.id }) { uiItem ->
                        when (uiItem) {
                            is ChatUiItem.Message -> {
                                val item = uiItem.item
                                val ev = item.event
                                MessageBubble(
                                    event = ev,
                                    isUser = item.isUser,
                                    timestamp = item.timestamp,
                                    isVoice = item.isVoice,
                                    modifier = Modifier.testTag("chat_message_${uiItem.originalIndex}"),
                                    ttsEnabled = voiceTtsEnabled,
                                    onTtsSpeak = if (voiceTtsEnabled) {
                                        { text ->
                                            uiScope.launch {
                                                try {
                                                    val result = client.postTtsSynthesize(
                                                        text = text,
                                                        provider = voiceTtsProvider.ifBlank { null },
                                                        voice = voiceTtsVoice.ifBlank { null },
                                                    )
                                                    // Parse response and play audio
                                                    val audioData = result["data"]?.jsonPrimitive?.content
                                                    val sampleRate = result["sampleRate"]?.jsonPrimitive?.int ?: 24000
                                                    if (audioData != null) {
                                                        ttsAudioEngine.playChunk(audioData, sampleRate)
                                                    }
                                                } catch (e: Exception) {
                                                    logger.warn(e) { "TTS synthesis failed" }
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )

                                // Retry button for failed messages
                                if (item.hasError && item.isUser) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                item.originalMessage?.let { msg ->
                                                    // Remove the failed message
                                                    items = items.filter { it.id != item.id }
                                                    sendMessage(msg)
                                                }
                                            },
                                            colors =
                                                ButtonDefaults.textButtonColors(
                                                    contentColor = colors.error,
                                                ),
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = stringResource(Res.string.action_retry),
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(Res.string.action_retry), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                if (ev.type == "response") {
                                    FeedbackBar(
                                        onFeedback = { score ->
                                            uiScope.launch {
                                                try {
                                                    client.submitFeedback(FeedbackRequest(sessionId, score))
                                                } catch (e: Exception) {
                                                    logger.debug(e) { "Failed to submit feedback for session $sessionId" }
                                                }
                                            }
                                        },
                                    )
                                }
                            }

                            is ChatUiItem.Reasoning -> {
                                AgentReasoningBlock(
                                    steps = uiItem.steps,
                                    isStreaming = uiItem.isStreaming,
                                )
                            }
                        }
                    }

                    items(pendingApprovals, key = { "approval_${it.id}" }) { approval ->
                        InlineApprovalCard(
                            approval = approval,
                            isResolving = approval.id in resolvingApprovalIds,
                            resolutionError = approvalResolutionErrors[approval.id],
                            onReject = {
                                resolveApproval(
                                    approval = approval,
                                    approved = false,
                                    scope = ChatApprovalScope.ONCE,
                                )
                            },
                            onApproveOnce = {
                                resolveApproval(
                                    approval = approval,
                                    approved = true,
                                    scope = ChatApprovalScope.ONCE,
                                )
                            },
                            onApproveSession = {
                                resolveApproval(
                                    approval = approval,
                                    approved = true,
                                    scope = ChatApprovalScope.SESSION,
                                )
                            },
                            onApprovePersistent = {
                                resolveApproval(
                                    approval = approval,
                                    approved = true,
                                    scope = ChatApprovalScope.PERSISTENT,
                                )
                            },
                        )
                    }

                    // Typing indicator
                    if (isStreaming) {
                        item {
                            TypingIndicator()
                        }
                    }
                }

                // ── Error banner ────────────────────────────────────────────
                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                ) {
                    errorMessage?.let { msg ->
                        Surface(
                            color = colors.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = msg,
                                color = colors.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }

                // ── Input bar ───────────────────────────────────────────────
                Surface(
                    color = colors.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        // Profile selector row
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(Res.string.chat_agent_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Box {
                                FilterChip(
                                    selected = true,
                                    onClick = { showProfilePicker = !showProfilePicker },
                                    label = {
                                        Text(
                                            text = selectedProfile?.name ?: stringResource(Res.string.chat_default_agent_name),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    modifier = Modifier.testTag("chat_agent_selector"),
                                )
                                DropdownMenu(
                                    expanded = showProfilePicker,
                                    onDismissRequest = { showProfilePicker = false },
                                ) {
                                    profiles.forEach { profile ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                                                    if (profile.isSystem) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            "🔒",
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                selectedProfile = profile
                                                showProfilePicker = false
                                                // Persist profile choice for this session
                                                PlatformStorage.write("session_profile_$sessionId", profile.id)
                                            },
                                            trailingIcon = {
                                                if (profile.id == selectedProfile?.id) {
                                                    Text("✓", color = colors.primary)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        // Message input row
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                                when {
                                                    event.isShiftPressed -> {
                                                        false
                                                    }

                                                    // Shift+Enter → new line
                                                    else -> {
                                                        // Enter or Ctrl+Enter → send
                                                        if (inputText.isNotBlank() && !isStreaming) {
                                                            sendMessage(inputText.trim())
                                                        }
                                                        true // consume to prevent newline
                                                    }
                                                }
                                            } else {
                                                false
                                            }
                                        }
                                        .testTag("chat_input"),
                                placeholder = { Text(stringResource(Res.string.chat_ask_placeholder)) },
                                singleLine = false,
                                maxLines = 4,
                                shape = RoundedCornerShape(24.dp),
                                colors =
                                    OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colors.primary,
                                        unfocusedBorderColor = colors.outline,
                                        cursorColor = colors.primary,
                                    ),
                            )
                            Spacer(Modifier.width(8.dp))
                            // ── STT dictation button (🎙️) ── visible if STT enabled
                            if (voiceSttEnabled) {
                                val sttContentDesc = if (voiceState.isDictating) stringResource(Res.string.chat_stop_dictation) else stringResource(Res.string.chat_voice_dictation)
                                IconButton(
                                    onClick = {
                                        if (voiceState.isDictating) {
                                            voiceVM.stopSession()
                                        } else {
                                            voiceVM.startDictation(
                                                VoiceSessionConfig(
                                                    provider = voiceSttProvider.takeIf { it.isNotBlank() } ?: "openai_stt",
                                                    model = voiceSttModel,
                                                    mode = "stt",
                                                ),
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("voice_stt_btn"),
                                ) {
                                    Icon(
                                        if (voiceState.isDictating) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = sttContentDesc,
                                        tint = if (voiceState.isDictating) colors.error else colors.onSurfaceVariant,
                                    )
                                }
                                // Inject final dictation text into the input field
                                if (dictationText.isNotBlank() && !voiceState.isDictating) {
                                    LaunchedEffect(dictationText) {
                                        inputText = if (inputText.isBlank()) {
                                            dictationText
                                        } else {
                                            "$inputText $dictationText"
                                        }
                                    }
                                }
                            }
                            // ── S2S agent vocal button (🔥) ── visible if S2S enabled
                            if (voiceS2sEnabled) {
                                val s2sContentDesc = if (voiceState.isActive) stringResource(Res.string.chat_stop_voice_agent) else stringResource(Res.string.chat_voice_agent)
                                IconButton(
                                    onClick = {
                                        if (voiceState.isActive) {
                                            voiceVM.stopSession()
                                        } else {
                                            voiceVM.startSession(
                                                VoiceSessionConfig(
                                                    provider = voiceProvider,
                                                    model = voiceModel.takeIf { it.isNotBlank() }
                                                        ?: "gemini-3.1-flash-live-preview",
                                                    voice = voiceVoice,
                                                    systemInstructions = selectedProfile?.systemPrompt?.takeIf { it.isNotBlank() },
                                                ),
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("voice_s2s_btn"),
                                ) {
                                    Icon(
                                        if (voiceState.isActive) Icons.Default.MicOff else Icons.Default.LocalFireDepartment,
                                        contentDescription = s2sContentDesc,
                                        tint = if (voiceState.isActive) colors.error else colors.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            FilledIconButton(
                                onClick = { sendMessage(inputText.trim()) },
                                enabled = inputText.isNotBlank() && !isStreaming,
                                shape = CircleShape,
                                colors =
                                    IconButtonDefaults.filledIconButtonColors(
                                        containerColor = colors.primary,
                                        contentColor = colors.onPrimary,
                                    ),
                                modifier = Modifier.size(48.dp).testTag("chat_send_btn"),
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = stringResource(Res.string.a11y_send_message))
                            }
                        }
                        // Keyboard shortcut hint
                        Text(
                            stringResource(Res.string.chat_keyboard_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    } // Column
                }
            }

            // ── Scroll-to-bottom FAB ────────────────────────────────────
            AnimatedVisibility(
                visible = showScrollToBottom,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 80.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        uiScope.launch {
                            if (lastChatListIndex >= 0) {
                                listState.animateScrollToItem(lastChatListIndex)
                            }
                        }
                    },
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(Res.string.chat_scroll_to_bottom))
                }
            }

            // ── Voice Overlay ─────────────────────────────────────────
            VoiceOverlay(
                voiceState = voiceState,
                transcripts = voiceTranscripts,
                onMuteToggle = { voiceVM.toggleMute() },
                onEchoSuppressionToggle = { voiceVM.toggleEchoSuppression() },
                onEndSession = { voiceVM.stopSession() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun InlineApprovalCard(
    approval: PendingChatApproval,
    isResolving: Boolean,
    resolutionError: String?,
    onReject: () -> Unit,
    onApproveOnce: () -> Unit,
    onApproveSession: () -> Unit,
    onApprovePersistent: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("chat_approval_${approval.id}"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.chat_approval_required),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(Res.string.chat_approval_tool, approval.toolName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(Res.string.chat_approval_arguments),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSecondaryContainer.copy(alpha = 0.75f),
            )
            Text(
                text = approval.arguments,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat_approval_args_${approval.id}"),
            )

            resolutionError?.let { message ->
                Text(
                    text =
                        stringResource(
                            Res.string.chat_approval_resolution_error,
                            message.ifBlank { stringResource(Res.string.error_unknown) },
                        ),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("chat_approval_error_${approval.id}"),
                )
            }

            OutlinedButton(
                onClick = onReject,
                enabled = !isResolving,
                modifier = Modifier.fillMaxWidth().testTag("chat_approval_reject_${approval.id}"),
            ) {
                Text(stringResource(Res.string.chat_approval_reject))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onApproveOnce,
                    enabled = !isResolving,
                    modifier = Modifier.weight(1f).testTag("chat_approval_once_${approval.id}"),
                ) {
                    Text(stringResource(Res.string.chat_approval_allow_once))
                }
                Button(
                    onClick = onApproveSession,
                    enabled = !isResolving,
                    modifier = Modifier.weight(1f).testTag("chat_approval_session_${approval.id}"),
                ) {
                    Text(stringResource(Res.string.chat_approval_allow_session))
                }
            }
            if (approval.persistentAllowed) {
                Button(
                    onClick = onApprovePersistent,
                    enabled = !isResolving,
                    modifier = Modifier.fillMaxWidth().testTag("chat_approval_persistent_${approval.id}"),
                ) {
                    Text(stringResource(Res.string.chat_approval_allow_persistent))
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "dot-alpha",
    )

    Row(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { i ->
            val delayed = alpha * (1f - i * 0.15f)
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = delayed.coerceIn(0.2f, 1f))),
            )
        }
    }
}

internal fun pendingChatApprovalsForSession(
    response: JsonObject,
    currentSessionId: String,
): List<PendingChatApproval> {
    val pending = response["pending"]?.jsonArray ?: return emptyList()
    return pending.mapNotNull { element ->
        val approval = element as? JsonObject ?: return@mapNotNull null
        val sessionId = approval["sessionId"]?.jsonPrimitive?.contentOrNull
        if (sessionId != currentSessionId) return@mapNotNull null

        val id = approval["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val toolName = approval["toolName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null

        PendingChatApproval(
            id = id,
            toolName = toolName,
            arguments = approval["args"]?.jsonPrimitive?.contentOrNull ?: "{}",
            sessionId = sessionId,
            persistentAllowed = approval["persistentAllowed"]?.jsonPrimitive?.booleanOrNull ?: false,
            createdAt = approval["createdAt"]?.jsonPrimitive?.longOrNull,
        )
    }
}

private const val APPROVAL_POLL_INTERVAL_MS = 750L

private sealed interface ChatUiItem {
    val id: String

    data class Message(
        val item: DisplayItem,
        val originalIndex: Int,
    ) : ChatUiItem {
        override val id: String get() = item.id
    }

    data class Reasoning(
        override val id: String,
        val steps: List<ChatEvent>,
        val isStreaming: Boolean,
    ) : ChatUiItem
}

private fun groupChatItems(
    items: List<DisplayItem>,
    isSessionStreaming: Boolean,
): List<ChatUiItem> {
    if (items.isEmpty()) return emptyList()

    val result = mutableListOf<ChatUiItem>()
    var currentGroup = mutableListOf<DisplayItem>()

    items.forEachIndexed { index, item ->
        val ev = item.event
        if (ev.type in listOf("thought", "action", "observation")) {
            currentGroup.add(item)
        } else {
            if (currentGroup.isNotEmpty()) {
                result.add(
                    ChatUiItem.Reasoning(
                        id = currentGroup.first().id,
                        steps = currentGroup.map { it.event },
                        isStreaming = false,
                    ),
                )
                currentGroup = mutableListOf()
            }
            result.add(ChatUiItem.Message(item, originalIndex = index))
        }
    }

    if (currentGroup.isNotEmpty()) {
        result.add(
            ChatUiItem.Reasoning(
                id = currentGroup.first().id,
                steps = currentGroup.map { it.event },
                isStreaming = isSessionStreaming,
            ),
        )
    }

    return result
}
