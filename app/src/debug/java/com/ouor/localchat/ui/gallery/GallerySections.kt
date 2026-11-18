package com.ouor.localchat.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.ui.components.*
import com.ouor.localchat.ui.markdown.MarkdownMessage
import com.ouor.localchat.ui.theme.LcColors
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.endpointDotColor
import kotlinx.coroutines.delay

enum class GallerySection(val title: String, val content: @Composable () -> Unit) {
    Basics("기본", { Board { basics() } }),
    Composer("입력창", { Board { composer() } }),
    Chat("채팅", { Board { chat() } }),
    Markdown("Markdown", { Board { markdown() } }),
    Browser("브라우저", { Board { browser() } }),
    Lists("목록·설정", { Board { lists() } }),
    Tokens("토큰", { Board { tokens() } }),
}

@Composable
private fun Board(content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp), content = content)
}

/** Labeled cell like the mockup boards ("1. 빈 입력 — 전송 비활성"). */
private fun LazyListScope.cell(caption: String, horizontalPadding: Boolean = true, body: @Composable ColumnScope.() -> Unit) {
    item(caption) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp).padding(horizontal = if (horizontalPadding) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(caption, style = LcTheme.text.label, color = LcTheme.colors.textSecondary, modifier = Modifier.padding(start = if (horizontalPadding) 4.dp else 20.dp))
            body()
        }
    }
}

private val noop: () -> Unit = {}

// ---------------- Basics ----------------

private fun LazyListScope.basics() {
    cell("LcButton · primary / tonal / outline / text / danger / agent") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LcButton("저장", noop)
            LcButton("취소", noop, variant = ButtonVariant.Tonal)
            LcButton("다시 확인", noop, variant = ButtonVariant.Outline)
            LcButton("모델 바꾸기", noop, variant = ButtonVariant.Text)
            LcButton("삭제", noop, variant = ButtonVariant.Danger)
            LcButton("AI에게 돌려주기", noop, variant = ButtonVariant.Agent, icon = Icons.Outlined.Web)
            LcButton("sm", noop, size = ButtonSize.Sm)
            LcButton("lg 연결 확인", noop, size = ButtonSize.Lg)
            LcButton("확인 중", noop, loading = true)
            LcButton("비활성", noop, enabled = false)
        }
    }
    cell("LcIconButton · ghost / tonal / raised / filled / inverse / badge / disabled") {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            LcIconButton(Icons.Rounded.Menu, "menu", noop)
            LcIconButton(Icons.Outlined.Web, "browser", noop, badge = true)
            LcIconButton(Icons.Rounded.MoreVert, "more", noop, variant = IconButtonVariant.Tonal, size = IconButtonSize.Sm)
            LcIconButton(Icons.Rounded.MoreVert, "more", noop, variant = IconButtonVariant.Raised, size = IconButtonSize.Sm)
            LcIconButton(Icons.Outlined.Code, "send", noop, variant = IconButtonVariant.Filled, size = IconButtonSize.Sm)
            LcIconButton(Icons.Outlined.Code, "stop", noop, variant = IconButtonVariant.Inverse, size = IconButtonSize.Sm)
            LcIconButton(Icons.Outlined.Code, "off", noop, variant = IconButtonVariant.Filled, size = IconButtonSize.Sm, enabled = false)
        }
    }
    cell("LcChip · outline / tonal / suggest / preset(selected)") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LcChip("Outline", noop)
            LcChip("Tonal", noop, variant = ChipVariant.Tonal)
            LcChip("이번 주 일정 정리해 줘", noop, variant = ChipVariant.Suggest)
            LcChip("DeepSeek", noop, variant = ChipVariant.Preset, selected = true)
            LcChip("OpenAI", noop, variant = ChipVariant.Preset)
        }
    }
    cell("ToggleChip · off / on / browser on / disabled+reason / locked / icon-only") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(ToggleKind.Reasoning, "추론", false, noop)
            ToggleChip(ToggleKind.Reasoning, reasoningChipLabel("high", "추론"), true, noop)
            ToggleChip(ToggleKind.Search, "검색", true, noop)
            ToggleChip(ToggleKind.Browser, "브라우저", true, noop)
            ToggleChip(ToggleKind.Browser, "브라우저", false, noop, enabled = false, disabledReason = "이 모델은 도구 호출을 지원하지 않아요.", reasonActionLabel = "모델 설정", onReasonAction = noop)
            ToggleChip(ToggleKind.Reasoning, "추론", true, noop, locked = true)
            ToggleChip(ToggleKind.Search, "검색", false, noop, iconOnly = true)
        }
    }
    cell("ModelChip · rail / missing / loading") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelChip("DeepSeek", "flash", endpointDotColor("DeepSeek"), noop)
            ModelChip("Ollama", "llama3.2", endpointDotColor("Ollama"), noop, state = ModelChipState.Missing)
            ModelChip("Ollama", "qwen3:8b", endpointDotColor("Ollama"), noop, state = ModelChipState.Loading)
        }
    }
    cell("LcBadge · neutral / ai / ai-soft / me / ok / warn / err / accent") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BadgeVariant.entries.forEach { LcBadge(it.name, variant = it) }
            LcBadge("승인 대기", variant = BadgeVariant.Warn)
        }
    }
    cell("ContextGauge · normal / estimate / warn / high / unknown · RetryStatus") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ContextGauge(12, noop)
            ContextGauge(18, noop, estimate = true)
            ContextGauge(71, noop)
            ContextGauge(86, noop)
            ContextGauge(null, noop, tokensText = "1.2k 토큰")
            RetryStatus(2, 5)
        }
    }
    cell("ContextUsageCard (A-07 상세)") {
        Box(Modifier.fillMaxWidth().background(LcTheme.colors.surfaceRaised, com.ouor.localchat.ui.theme.LcRadius.lg)) {
            ContextUsageCard(86, "입력 851.2k · 출력 9.1k · 캐시 412k", "/ 1M", "80%를 넘으면 다음 전송 때 오래된 기록을 자동으로 요약해요.", noop)
        }
    }
    cell("LcTextField · text / focus·error / secret / url+warn / search pill") {
        var a by remember { mutableStateOf("DeepSeek") }
        var k by remember { mutableStateOf("sk-1234567890abcd") }
        var u by remember { mutableStateOf("http://192.168.0.10:11434/v1") }
        var q by remember { mutableStateOf("") }
        LcTextField(a, { a = it }, label = "이름", placeholder = "예: 회사 서버")
        LcTextField("", {}, label = "Base URL", placeholder = "https://", helper = "URL 형식이 아니에요", helperTone = HelperTone.Error, kind = TextFieldKind.Url)
        LcTextField(k, { k = it }, label = "API 키", kind = TextFieldKind.Secret, onPaste = noop, helper = "끝 4자리 abcd 저장됨")
        LcTextField(u, { u = it }, label = "Base URL", kind = TextFieldKind.Url, helper = "http는 암호화되지 않아요", helperTone = HelperTone.Warn)
        SearchPill(q, { q = it }, "대화 검색")
    }
    cell("Switch / Checkbox / Radio / SegmentedControl / Select menu") {
        var on by remember { mutableStateOf(true) }
        var seg by remember { mutableIntStateOf(1) }
        var menu by remember { mutableStateOf(false) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LcSwitch(on, { on = it }); LcSwitch(false, null, enabled = false)
            LcCheckbox(on, { on = it }); LcCheckbox(false, {})
            LcRadio(true, {}); LcRadio(false, {})
        }
        SegmentedControl(listOf("시스템", "라이트", "다크"), seg, { seg = it })
        Box {
            LcButton("추론 강도 메뉴 열기", { menu = true }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
            LcSelectMenu(menu, { menu = false }, listOf(MenuOption("끔"), MenuOption("low"), MenuOption("high", "기본"), MenuOption("max")), 2, { menu = false })
        }
    }
    cell("Banner · info / warn(+actions) / err / agent / success") {
        Banner("이 대화는 기기에만 저장돼요.", BannerVariant.Info)
        Banner("v4-pro는 이미지를 읽지 못해요. 이미지 2개는 보낼 수 없어요.", BannerVariant.Warn, actions = listOf(BannerAction("이미지 빼고 보내기", noop), BannerAction("모델 바꾸기", noop)), stackedActions = true)
        Banner("저장되지 않음 · 변경 내용이 기기에 저장되지 않았어요", BannerVariant.Err, actions = listOf(BannerAction("재시도", noop)))
        Banner("AI가 브라우저를 쓰는 중이에요", BannerVariant.Agent)
        Banner("백업을 저장했어요", BannerVariant.Success)
    }
    cell("Snackbar · Tooltip(비활성 사유 탭) · Progress · Spinner") {
        LcSnackbar("직접 조작으로 전환할까요? AI는 이 탭에서 기다려요.", icon = Icons.Outlined.AdsClick, actionLabel = "전환", onAction = noop)
        LcTooltip("이 모델은 도구 호출을 지원하지 않아요.", "모델 설정", noop) { show -> LcButton("사유 보기", show, variant = ButtonVariant.Tonal, size = ButtonSize.Sm) }
        LcLinearProgress(0.42f)
        LcLinearProgress(null)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { LcSpinner(); LcSpinner(color = LcTheme.colors.agent, size = 20.dp) }
    }
    cell("EmptyState · SectionLabel · DividerLabel") {
        EmptyState(Icons.Outlined.ChatBubbleOutline, "대화가 없어요", "새 대화를 시작해 보세요.", actionLabel = "새 대화", onAction = noop)
        SectionLabel("오늘")
        DividerLabel("이전 대화 요약됨")
    }
    cell("LcTopBar · chat / detail") {
        LcTopBar("강남 전세 시세 조사", navigationIcon = { LcIconButton(Icons.Rounded.Menu, "목록", noop) }, actions = {
            LcIconButton(Icons.Outlined.Web, "브라우저", noop, badge = true)
            LcIconButton(Icons.Rounded.MoreVert, "메뉴", noop)
        }, windowInsets = androidx.compose.foundation.layout.WindowInsets(0))
        LcTopBar("엔드포인트", subtitle = "DeepSeek", scrolled = true, windowInsets = androidx.compose.foundation.layout.WindowInsets(0))
    }
    cell("LcDialog (destructive) · LcBottomSheet 헤더") {
        var open by remember { mutableStateOf(false) }
        var sheet by remember { mutableStateOf(false) }
        LcDialogSurface("대화를 삭제할까요?", "이 대화와 첨부가 기기에서 지워져요. 되돌릴 수 없어요.", "삭제", noop, DialogKind.Destructive, "취소")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcButton("다이얼로그 열기", { open = true }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
            LcButton("시트 열기", { sheet = true }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
        }
        if (open) LcDialog("이름 변경", { open = false }, "저장", { open = false }) { LcTextField("리포트 초안", {}) }
        if (sheet) LcBottomSheet({ sheet = false }, "모델 선택") { Text("시트 내용", Modifier.padding(20.dp)); Box(Modifier.height(200.dp)) }
        Box(Modifier.fillMaxWidth().background(LcTheme.colors.surfaceRaised, com.ouor.localchat.ui.theme.LcRadius.xl)) { LcSheetHeader("모델 선택", noop) }
    }
}

// ---------------- Composer (composer-states.png) ----------------

@Composable
private fun Toggles(reasoning: String = "추론", r: Boolean = false, s: Boolean = false, b: Boolean = false, enabled: Boolean = true, locked: Boolean = false, iconOnlyOff: Boolean = false) {
    ToggleChip(ToggleKind.Reasoning, reasoning, r, noop, enabled = enabled, locked = locked, iconOnly = iconOnlyOff && !r)
    ToggleChip(ToggleKind.Search, "검색", s, noop, enabled = enabled, iconOnly = iconOnlyOff && !s, disabledReason = "이 모델은 도구 호출을 지원하지 않아요.")
    ToggleChip(ToggleKind.Browser, "브라우저", b, noop, enabled = enabled, iconOnly = iconOnlyOff && !b, disabledReason = "이 모델은 도구 호출을 지원하지 않아요.")
}

@Composable
private fun Rail(ep: String, model: String, percent: Int?, estimate: Boolean = false, tokens: String? = null, retry: Boolean = false) {
    ComposerRail(start = { ModelChip(ep, model, endpointDotColor(ep), noop) }, end = {
        if (estimate) Text("추정", style = LcTheme.text.caption, color = LcTheme.colors.textTertiary)
        if (retry) RetryStatus(2, 5)
        ContextGauge(percent, noop, estimate = estimate, tokensText = tokens)
    })
}

private fun LazyListScope.composer() {
    cell("1. 빈 입력 — 전송 비활성") {
        Column { ComposerPanel("", {}, noop, noop, noop, noop) { Toggles() }; Rail("DeepSeek", "flash", 12) }
    }
    cell("2. 입력 중(여러 줄, 최대 6줄) — 전송 활성") {
        Column { ComposerPanel("이 표를 월별 합계로 정리하고\n전월 대비 증감률도 붙여줘", {}, noop, noop, noop, noop) { Toggles() }; Rail("DeepSeek", "flash", 18, estimate = true) }
    }
    cell("3. 강도(high) · 검색 · 브라우저 켬") {
        Column { ComposerPanel("", {}, noop, noop, noop, noop) { Toggles("high", r = true, s = true, b = true) }; Rail("DeepSeek", "flash", 22) }
    }
    cell("3b. ALWAYS_ON 모델 — 잠김") {
        Column { ComposerPanel("", {}, noop, noop, noop, noop) { Toggles(locked = true) }; Rail("Ollama", "qwen3:8b", 4) }
    }
    cell("4. 모델 기능 없음 → 토글 비활성 + 사유(탭)") {
        Column { ComposerPanel("", {}, noop, noop, noop, noop) { Toggles(enabled = false) }; Rail("Ollama", "llama3.2", null, tokens = "1.2k 토큰") }
    }
    cell("5. 첨부: 준비 중 · 실패(탭하여 재시도) · 완료 · 원본 없음") {
        Column {
            ComposerPanel("", {}, noop, noop, noop, noop, canSend = false, attachments = {
                AttachmentTile("photo.jpg", TileKind.Image, TileState.Ready, thumbnail = DEMO_IMG, onRemove = noop)
                AttachmentTile("보고서.docx", TileKind.Document, TileState.Preparing, progress = 64)
                AttachmentTile("scan.pdf", TileKind.Document, TileState.Failed, onRetry = noop, onRemove = noop)
                AttachmentTile("main.kt", TileKind.Code, TileState.Ready, onRemove = noop)
                AttachmentTile("old.xls", TileKind.Document, TileState.Missing)
            }) { Toggles() }
            Row { Text("파일 1개를 준비하지 못했어요", style = LcTheme.text.caption, color = LcTheme.colors.dangerText, modifier = Modifier.weight(1f)); Text("준비 중이면 전송 대기", style = LcTheme.text.caption, color = LcTheme.colors.textTertiary) }
        }
    }
    cell("6. 이미지 미지원 모델로 전송 시(I-03)") {
        Banner("v4-pro는 이미지를 읽지 못해요. 이미지 2개는 보낼 수 없어요.", BannerVariant.Warn, actions = listOf(BannerAction("이미지 빼고 보내기", noop), BannerAction("모델 바꾸기", noop)), stackedActions = true)
        ComposerPanel("이 두 사진 차이점?", {}, noop, noop, noop, noop, attachments = {
            AttachmentTile("a.jpg", TileKind.Image, TileState.Ready, thumbnail = DEMO_IMG, onRemove = noop)
            AttachmentTile("b.jpg", TileKind.Image, TileState.Ready, thumbnail = DEMO_IMG, onRemove = noop)
        }) { Toggles(r = true, reasoning = "추론") }
    }
    cell("7. 음성 입력 중(I-08)") {
        val levels = remember { listOf(.3f, .5f, .8f, .4f, 1f, .6f, .3f, .7f, .5f, .9f, .4f, .2f, .6f, .3f, .2f, .1f) }
        ComposerPanel("", {}, noop, noop, noop, noop, mode = ComposerMode.Voice, voice = { VoiceRecorderRow("0:07", levels, "듣는 중 · 한국어") }) { Toggles() }
    }
    cell("8. 생성 중 → 중지(M-02) · 재시도 중 표시(P-09)") {
        Column { ComposerPanel("", {}, noop, noop, noop, noop, mode = ComposerMode.Running) { Toggles(r = true) }; Rail("DeepSeek", "flash", 40, retry = true) }
    }
    cell("9. 태블릿 드래그 앤 드롭(I-02)") {
        ComposerPanel("", {}, noop, noop, noop, noop, mode = ComposerMode.DragOver) { Toggles() }
    }
    cell("12. 좁은 폭 — 꺼진 토글 아이콘 전용") {
        Box(Modifier.fillMaxWidth(0.86f)) { ComposerPanel("", {}, noop, noop, noop, noop) { Toggles("high", r = true, iconOnlyOff = true) } }
    }
    cell("13. FB-1b 옵션 접힘(대화 중) / 펼침(빈 대화)") {
        ComposerPanel("", {}, noop, noop, noop, noop) { LcIconButton(Icons.Rounded.Tune, "옵션 펼치기", noop, size = IconButtonSize.Sm) }
        Column {
            ComposerPanel("", {}, noop, noop, noop, noop) { LcIconButton(Icons.Rounded.Tune, "옵션 접기", noop, variant = IconButtonVariant.Tonal, size = IconButtonSize.Sm) }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { Toggles() }
            Rail("DeepSeek-아주-긴-프로바이더-이름", "deepseek-v4-pro-long-model-id-2026", 41, tokens = "31.6k 토큰")
        }
    }
}

// ---------------- Chat (chat-main.png) ----------------

private val demoSources = listOf(
    Source(1, "https://news.example.com/a", "강남 전세 동향", "3개월 기준 보합", "Naver"),
    Source(2, "https://kb.example.com/b", "KB 시세", null, "KB"),
    Source(3, "https://r.example.com/c", "학군 수요", null, "Realty"),
    Source(4, "https://h.example.com/d", "입주 물량", null, "hankyung"),
)

private fun LazyListScope.chat() {
    cell("A. 완료 응답 — 사용자 말풍선 · 단계 접힘 · 사고 · 인용 본문 · 출처 · 동작") {
        UserMessage("강남구 아파트 전세 시세 요즘 어때?\n출처도 알려줘")
        var stepsOpen by remember { mutableStateOf(false) }
        var thinkOpen by remember { mutableStateOf(false) }
        AssistantMessage {
            StepList(StepListState.Done, "4단계 완료", demoSteps(done = true), stepsOpen, { stepsOpen = !stepsOpen }, caption = "검색 2 · 페이지 2")
            ThinkingBlock(ThinkingState.Done, "12초", thinkOpen, { thinkOpen = !thinkOpen }, "통계청 자료와 민간 전망을 비교해야 한다. 차단된 페이지는 제외하고…")
            MarkdownMessage(
                "최근 3개월 기준으로 강남구 아파트 전세가는 **보합~소폭 상승** 흐름입니다.[1]\n\n" +
                    "- 84㎡ 평균 전세가는 약 **11억 2천만 원**으로 전월 대비 0.4% 올랐어요.[2]\n" +
                    "- 대치·도곡동은 학군 수요로 상승 폭이 컸습니다.[1][3]\n" +
                    "- 신규 입주 물량이 많은 개포동은 하락 전환했어요.[4]\n\n" +
                    "계약 전에는 같은 단지의 최근 실거래가를 함께 확인하세요.",
                demoSources, false, {}, {},
            )
            SourceStack(demoSources.map { SourceSite(it.siteName ?: "") }, 4, noop)
            MessageActionBar(noop, onReadAloud = noop, onRegenerate = noop, branch = { BranchSwitcher(2, 2, noop, noop) }, modelName = "deepseek-v4-pro", onModelLongPress = noop)
        }
    }
    cell("B. 에이전트 진행 중 — 현재 단계 강조 · 생각 중 쉬머 · 미리보기") {
        UserMessage("사내 위키에서 연차 이월 규정 찾아서 요약해줘")
        AssistantMessage {
            StepList(StepListState.Running, "브라우저 작업 중", demoSteps(done = false), true, noop, caption = "4단계 · 25 중")
            ThinkingBlock(ThinkingState.Thinking, "3.4s", false, noop, "")
        }
        BrowserStatusStrip("인사 규정 - 사내 위키", "wiki.corp.example", noop, noop)
        Column { ComposerPanel("", {}, noop, noop, noop, noop, mode = ComposerMode.Running) { Toggles(b = true) }; Rail("OpenAI", "gpt-5.1", 71) }
    }
    cell("C. 펼친 단계(실패 포함)·요약 구분선·오류 카드·맨 아래로") {
        Banner("저장되지 않음 · 변경 내용이 기기에 저장되지 않았어요", BannerVariant.Err, actions = listOf(BannerAction("재시도", noop)))
        DividerLabel("이전 대화 요약됨")
        StepList(StepListState.FailedSome, "3단계 · 1개 실패", listOf(
            StepItemData(toolVisual("web_search").icon, "검색 중:", StepState.Done, "2026 소비자물가 전망", "8개 웹페이지 찾음"),
            StepItemData(toolVisual("page_text").icon, "페이지 읽기", StepState.Failed, "news.example.com", "민감 URL 차단: 읽지 않음"),
            StepItemData(toolVisual("page_text").icon, "페이지 읽기", StepState.Done, "kostat.go.kr", thumbnail = DEMO_IMG),
        ), true, noop)
        ThinkingBlock(ThinkingState.Done, "8초", true, noop, "통계청 자료와 민간 전망을 비교해야 한다. 차단된 페이지는 제외하고…")
        Box {
            ChatErrorCard(ChatError(ChatErrorKind.RATE_LIMITED, 429), {})
            ScrollToBottomButton(noop, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 8.dp), hasNew = true)
        }
    }
    cell("ErrorCard 변형 · 거절 · 편집 중 말풍선 · 첨부 메시지") {
        ChatErrorCard(ChatError(ChatErrorKind.AUTH, 401), {})
        ChatErrorCard(ChatError(ChatErrorKind.BALANCE, 402), {})
        ChatErrorCard(ChatError(ChatErrorKind.BAD_REQUEST, 400), {}, usedOptionalFeatures = true)
        ChatErrorCard(ChatError(ChatErrorKind.CONTEXT_OVERFLOW), {})
        ChatErrorCard(ChatError(ChatErrorKind.MODEL_UNAVAILABLE), {})
        ChatErrorCard(ChatError(ChatErrorKind.CONFIG), {})
        RefusalCard(noop)
        UserMessageEditor("이 표를 월별 합계로 정리해줘", {}, noop, noop)
        UserMessage("이 파일 요약해줘", attachments = { FileCard("계약서.pdf", "PDF · 1.2MB", "pdf") })
        MessageActionBar(noop, onReadAloud = noop, reading = true, onContinue = noop, onMore = noop, modelName = "gpt-5.1")
    }
}

@Composable
private fun demoSteps(done: Boolean): List<StepItemData> {
    fun v(n: String) = toolVisual(n)
    return listOf(
        StepItemData(v("open_tab").icon, "탭 열기", StepState.Done, "wiki.corp.example/hr", agent = true),
        StepItemData(v("request_handoff").icon, "로그인 넘겨받기", StepState.Done, result = "사용자가 로그인 후 돌려줌", agent = true),
        StepItemData(v("snapshot").icon, "페이지 구조 읽기", StepState.Done, "snapshot · 142개 요소", agent = true),
        StepItemData(v("click").icon, "클릭", if (done) StepState.Done else StepState.Running, "\"연차·휴가 규정\" 링크", if (done) null else "브라우저에서 보기 ›", agent = true),
    )
}

// ---------------- Markdown ----------------

private const val MD_SAMPLE = """## 결과 요약

통계는 **세 가지** 출처를 비교했습니다.[1][2] 인라인 수식 ${'$'}E=mc^2${'$'}, ${'$'}\sum_{i=1}^{n} i = \frac{n(n+1)}{2}${'$'}, 그리고 깨진 수식 ${'$'}\frac{a}{${'$'} 는 원문으로 보입니다. 가격 $5 와 $10 은 수식이 아닙니다.

${'$'}${'$'}f(x)=\begin{cases} x^2 & x\ge 0 \\ -x & x<0 \end{cases}${'$'}${'$'}

| 지역 | 전세가 | 변화 | 비고 |
|---|---|---|---|
| 대치 | 12.1억 | +0.8% | 학군 |
| 개포 | 9.4억 | -0.3% | 입주 |

| 항목 | 설명 |
|---|---|
| 연차 이월 | 미사용 연차는 다음 해 3월 31일까지 최대 10일을 이월할 수 있으며, 이월분은 신규 연차보다 먼저 차감됩니다.[1] |
|  |  |

| A | B | C | D | E | F | G | H | I | J |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | https://very-long-domain-name.example.com/path/to/a/really/long/resource?query=without-spaces-at-all |

> 인용문은 보조 텍스트 색으로 표시됩니다.

```kotlin
fun greet(name: String): String {
    // 인사
    return "Hello, ${'$'}name" // a very long line that should scroll horizontally instead of wrapping in the code block
}
```

```mermaid
graph TD
  A[질문] --> B{도구 필요?}
  B -->|예| C[검색]
  B -->|아니오| D[답변]
```

1. 첫째 항목
2. 둘째 항목 `inline code`

- 글머리 항목
- 다크 모드에서도 점이 보여야 해요

• 줄바꿈 한 번으로 쓴 목록
• 각 줄이 따로 보여야 해요

[링크 예시](https://example.com)를 누르면 앱 내 브라우저로 열려요."""

private fun LazyListScope.markdown() {
    cell("MarkdownMessage — 제목·인용 배지·수식(인라인/블록/실패)·표·코드·Mermaid·링크") {
        var active by remember { mutableStateOf<Int?>(null) }
        MarkdownMessage(MD_SAMPLE, demoSources.take(2), false, {}, { active = it.n }, activeCitation = active)
    }
    cell("스트리밍(100ms 스로틀, 완료 블록 고정) — 재생") {
        var shown by remember { mutableIntStateOf(0) }
        var run by remember { mutableIntStateOf(0) }
        LaunchedEffect(run) {
            shown = 0
            while (shown < MD_SAMPLE.length) { delay(16); shown = (shown + 6).coerceAtMost(MD_SAMPLE.length) }
        }
        LcButton("다시 재생", { run++ }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
        MarkdownMessage(MD_SAMPLE.take(shown), demoSources.take(2), shown < MD_SAMPLE.length, {}, {})
    }
}

// ---------------- Browser (browser-panel.png) ----------------

@Composable
private fun FakePage(mini: Boolean = false, highlight: Boolean = false) {
    val h = if (mini) 12.dp else 48.dp
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(h).background(Color(0xFF0F3D6E)).padding(horizontal = 14.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
            if (!mini) Text("CorpWiki", color = Color.White, style = LcTheme.text.bodyStrong)
        }
        if (!mini) {
            val density = LocalDensity.current
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("인사 · HR", color = Color(0xFF1A1A1A), style = LcTheme.text.bodyStrong)
                    Text("복리후생 안내", color = Color(0xFF0F62FE), style = LcTheme.text.body)
                    Text("연차·휴가 규정", color = Color(0xFF0F62FE), style = LcTheme.text.body)
                    Text("출장비 정산", color = Color(0xFF0F62FE), style = LcTheme.text.body)
                }
                if (highlight) {
                    var key by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) { while (true) { delay(1600); key++ } }
                    val r = with(density) { Rect(14.dp.toPx(), 82.dp.toPx(), 116.dp.toPx(), 104.dp.toPx()) }
                    ElementHighlightLayer(r, "클릭", toolVisual("click").icon, key)
                }
            }
        }
    }
}

private fun LazyListScope.browser() {
    cell("OwnershipBar · AI / 나 / 넘겨받음(대기)", horizontalPadding = false) {
        OwnershipBar(BrowserOwner.Ai, toolName = "click")
        OwnershipBar(BrowserOwner.Ai, idle = true)
        OwnershipBar(BrowserOwner.Me)
        OwnershipBar(BrowserOwner.Waiting)
    }
    cell("BrowserStatusStrip (FB-1c, 패널 Peek) · 긴 제목") {
        BrowserStatusStrip("인사 규정", "wiki.corp.example", noop, noop)
        BrowserStatusStrip("아주 긴 페이지 제목이 들어와도 한 줄로 말줄임되고 버튼은 항상 보여요", "a-very-long-host-name.corp.example.com", noop, noop)
    }
    cell("B. 절반 — 툴바 · 소유 바 · 요소 강조(B-09, 1초 후 페이드 반복)", horizontalPadding = false) {
        BrowserPanelFrame(
            toolbar = { BrowserToolbar("wiki.corp.example/hr", 2, noop, noop, noop, noop, null, noop, progress = 0.6f) },
            ownership = { OwnershipBar(BrowserOwner.Ai, toolName = "click") },
            modifier = Modifier.height(380.dp),
        ) { FakePage(highlight = true) }
    }
    cell("C. 넘겨받기(B-05) — 배너 + 하단 '나' 바 · http 경고", horizontalPadding = false) {
        BrowserPanelFrame(
            toolbar = { BrowserToolbar("sso.corp.example/login", 2, noop, noop, noop, noop, {}, noop, secure = false) },
            ownership = { OwnershipBar(BrowserOwner.Waiting) },
            banner = { HandoffBanner("로그인이 필요합니다", "AI가 기다리는 중이에요. 로그인을 마치면 돌려주세요. 비밀번호는 AI가 볼 수 없어요.", noop, noop) },
            modifier = Modifier.height(330.dp),
        ) { FakePage() }
    }
    cell("D. 사용자 터치 → 직접 조작 제안 스낵바") {
        LcSnackbar("직접 조작으로 전환할까요? AI는 이 탭에서 기다려요.", icon = toolVisual("request_handoff").icon, actionLabel = "전환", onAction = noop)
    }
    cell("E. 탭 카드 — AI / 나 / 다른 대화") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TabCard("연차·휴가 규정", "wiki.corp.example/hr/leave", TabOwner.Ai, noop, Modifier.weight(1f), onClose = noop) { FakePage(mini = true) }
            TabCard("검색 결과", "duckduckgo.com", TabOwner.Me, noop, Modifier.weight(1f), onClose = noop) { FakePage(mini = true) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TabCard("다른 대화의 탭", "news.example.com", TabOwner.OtherChat, noop, Modifier.weight(1f)) { FakePage(mini = true) }
            Box(Modifier.weight(1f))
        }
    }
    cell("ApprovalSheet(SC-13) — click 제출 / execute 코드 전문") {
        Column(Modifier.background(LcTheme.colors.surfaceRaised, com.ouor.localchat.ui.theme.LcRadius.dialog)) {
            LcSheetHeader(null, null)
            ApprovalContent(toolVisual("click").icon, "'제출' 버튼을 눌러도 될까요?", "되돌릴 수 없는 동작일 수 있어요. 폼이 서버로 전송됩니다.", "forms.corp.example/leave", noop, noop)
        }
        Column(Modifier.background(LcTheme.colors.surfaceRaised, com.ouor.localchat.ui.theme.LcRadius.dialog)) {
            LcSheetHeader(null, null)
            ApprovalContent(toolVisual("execute").icon, "스크립트를 실행할까요?", "AI가 이 페이지에서 아래 코드를 실행하려고 해요.", "wiki.corp.example/hr", noop, noop,
                code = "document.querySelectorAll('table tr')\n  .forEach(r => console.log(r.innerText))", allowLabel = "실행")
        }
    }
}

// ---------------- Lists & settings ----------------

private fun LazyListScope.lists() {
    cell("SessionListItem · 선택 / 응답 중 / 저장 실패 / 검색 일치", horizontalPadding = false) {
        SectionLabel("오늘")
        SessionListItem("강남 전세 시세 조사", noop, selected = true)
        SessionListItem("사내 위키 휴가 규정", noop, running = true)
        SessionListItem("리포트 초안", noop, saveFailed = true)
        SessionListItem("2026 소비자물가 전망 정리", noop, query = "물가")
    }
    cell("ModelRow + CapabilityIcons · EndpointGroupHeader", horizontalPadding = false) {
        EndpointGroupHeader("DeepSeek", endpointDotColor("DeepSeek"), 2)
        ModelRow("deepseek-v4-flash", noop, subtitle = "1M 컨텍스트", selected = true) { CapabilityIcons(true, false, true) }
        ModelRow("deepseek-v4-pro", noop, subtitle = "1M 컨텍스트") { CapabilityIcons(true, true, true) }
        EndpointGroupHeader("Ollama (집)", endpointDotColor("Ollama"), 1, expanded = false, onToggle = noop)
        ModelRow("llama3.2", noop, enabled = false, notListed = true) { CapabilityIcons(false, false, false) }
    }
    cell("SettingsGroup + SettingsRow · chevron / value / switch / stepper / badge+switch / danger", horizontalPadding = false) {
        var sw by remember { mutableStateOf(true) }
        SettingsGroup {
            SettingsRow("테마", value = "시스템", leadingIcon = Icons.Outlined.Palette, onClick = noop) { SettingsChevron() }
            SettingsRow("API 키", subtitle = "끝 4자리 abcd", leadingIcon = Icons.Outlined.Key, divider = true, onClick = noop) { SettingsChevron() }
            SettingsRow("도구 호출", divider = true) { SourceBadge(CapSource.SERVER); LcSwitch(sw, { sw = it }) }
            SettingsRow("최대 단계", subtitle = "한 응답에서 도구를 쓸 수 있는 횟수", divider = true) { Stepper("25", noop, noop) }
            SettingsRow("모든 대화 삭제", danger = true, divider = true, onClick = noop)
        }
    }
    cell("PresetRow (SC-01)", horizontalPadding = false) {
        SettingsGroup {
            PresetRow("DeepSeek", "https://api.deepseek.com", endpointDotColor("DeepSeek"), noop)
            PresetRow("OpenAI", "https://api.openai.com/v1", endpointDotColor("OpenAI"), noop, divider = true)
            PresetRow("Ollama", "http://localhost:11434/v1", endpointDotColor("Ollama"), noop, divider = true)
        }
    }
    cell("ConnectionStatus · checking / ok / error · SourceBadge") {
        ConnectionStatus(ConnectionUi.Checking, noop, noop)
        ConnectionStatus(ConnectionUi.Ok(12), noop, noop)
        ConnectionStatus(ConnectionUi.Error("401 · 키가 올바르지 않아요"), noop, noop)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { CapSource.entries.forEach { SourceBadge(it) } }
    }
    cell("SourceCard (SC-09) · SummaryTiles (SC-18)", horizontalPadding = false) {
        demoSources.take(2).forEach { SourceCard(it.n, it.title, SourceSite(it.siteName ?: ""), it.url, noop, snippet = it.snippet) }
        SummaryTiles(listOf("대화" to "128", "메시지" to "3,412", "첨부" to "56 · 240MB", "프로바이더" to "3"), Modifier.padding(horizontal = 16.dp))
    }
}

// ---------------- Tokens ----------------

private fun LazyListScope.tokens() {
    cell("Colors (semantic)") {
        val c = LcTheme.colors
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            swatches(c).forEach { (name, color) ->
                Column(Modifier.size(84.dp, 64.dp).background(c.surfaceRaised, com.ouor.localchat.ui.theme.LcRadius.sm).padding(4.dp)) {
                    Box(Modifier.fillMaxWidth().height(32.dp).background(color, com.ouor.localchat.ui.theme.LcRadius.xs).let { if (color == c.surfaceRaised) it.background(c.border) else it })
                    Text(name, style = LcTheme.text.caption, color = c.textSecondary, maxLines = 1)
                }
            }
        }
    }
    cell("Typography") {
        val t = LcTheme.text
        listOf("display 26" to t.display, "titleLarge 20" to t.titleLarge, "title 17" to t.title, "titleSmall 15" to t.titleSmall,
            "bodyLarge 16/26 메시지 본문" to t.bodyLarge, "body 14" to t.body, "bodyStrong 14" to t.bodyStrong, "label 13" to t.label,
            "caption 12" to t.caption, "micro 11" to t.micro, "code 13 mono" to t.code).forEach { (n, s) -> Text(n, style = s, color = LcTheme.colors.textPrimary) }
    }
}

private fun swatches(c: LcColors) = listOf(
    "surface" to c.surface, "raised" to c.surfaceRaised, "sunken" to c.surfaceSunken, "nav" to c.surfaceNav, "inverse" to c.surfaceInverse,
    "textPri" to c.textPrimary, "textSec" to c.textSecondary, "textTer" to c.textTertiary, "border" to c.border, "borderStr" to c.borderStrong,
    "accent" to c.accent, "accentTxt" to c.accentText, "accentCont" to c.accentContainer, "agent" to c.agent, "agentCont" to c.agentContainer,
    "success" to c.success, "warning" to c.warning, "danger" to c.danger, "dangerCont" to c.dangerContainer, "codeBg" to c.codeBg,
)
private val DEMO_IMG = com.ouor.localchat.R.mipmap.ic_launcher
