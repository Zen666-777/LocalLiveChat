@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package jp.mel.chat.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.mel.chat.data.CharacterEntity
import jp.mel.chat.data.MemoryEntity
import jp.mel.chat.data.MessageEntity
import jp.mel.chat.network.RemoteModel
import jp.mel.chat.local.ModelState
import jp.mel.chat.local.LocalModelStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF0D0D16)
private val Panel = Color(0xFF171721)
private val Lavender = Color(0xFFC2A4FF)
private val Muted = Color(0xFFA6A2B7)
private val Mint = Color(0xFF88DBCD)
private val MelColors = darkColorScheme(primary = Lavender, secondary = Mint, background = Ink,
    surface = Panel, surfaceVariant = Color(0xFF242332), onSurface = Color(0xFFF1EDF9), onBackground = Color(0xFFF1EDF9))

@Composable
fun MelApp(vm: ChatViewModel) {
    val characters by vm.characters.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val memory by vm.memory.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val loadingModels by vm.loadingModels.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val deviceModels by vm.deviceModels.collectAsStateWithLifecycle()
    val ready = if (offline) deviceModels.active != null else hasKey
    val character = characters.find { it.id == selected }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CharacterEntity?>(null) }
    var showMemory by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { vm.notice.value = null; snackbars.showSnackbar(it) } }

    MaterialTheme(colorScheme = MelColors) {
        Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbars) },
            modifier = Modifier.safeDrawingPadding().imePadding()) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Lavender, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("MEL", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    Text("  Chat", fontSize = 21.sp, color = Muted, modifier = Modifier.weight(1f))
                    Surface(color = Mint.copy(alpha = 0.09f), shape = CircleShape) {
                        Text(if (offline) "ON DEVICE" else "CLOUD", color = Mint, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "設定") }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                BoxWithConstraints(Modifier.weight(1f)) {
                    val wide = maxWidth >= 740.dp
                    BackHandler(enabled = showChat && !wide) { showChat = false }
                    Row(Modifier.fillMaxSize()) {
                        if (wide || !showChat) {
                            CharacterList(characters, selected, wide, ready, offline,
                                modifier = if (wide) Modifier.width(292.dp) else Modifier.fillMaxWidth(),
                                onSelect = { vm.select(it); showChat = true },
                                onCreate = { editing = null; showEditor = true }, onSettings = { showSettings = true })
                        }
                        if (wide) VerticalDivider(color = Color.White.copy(alpha = 0.07f))
                        if (wide || showChat) {
                            if (character == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("キャラクターを選んで、物語をはじめよう。", color = Muted)
                            } else ChatPane(character, messages.filter { it.characterId == character.id },
                                memory?.takeIf { it.characterId == character.id }, busy, ready, wide, offline, deviceModels.active?.name,
                                onBack = { showChat = false }, onEdit = { editing = character; showEditor = true },
                                onMemory = { showMemory = true }, onModel = { if (offline) showSettings = true else showModel = true }, onSettings = { showSettings = true },
                                onSend = { text, accepted -> vm.send(character.id, text, accepted) },
                                onRetry = { vm.send(character.id, null) }, onStop = vm::cancel)
                        }
                    }
                }
            }
        }
        if (showSettings) SettingsDialog(hasKey, busy, offline, deviceModels, vm, vm::saveKey, vm::clearKey) { showSettings = false }
        if (showEditor) CharacterEditor(editing, busy, models, loadingModels, vm::refreshModels, offline,
            onSave = { vm.save(it) { showEditor = false; showChat = true } },
            onDelete = { id -> vm.delete(id) { showEditor = false; showChat = false } }, onDismiss = { showEditor = false })
        if (showMemory && character != null) MemoryDialog(character.id, memory?.takeIf { it.characterId == character.id }, busy, offline,
            onSave = { vm.saveMemory(character.id, it) { showMemory = false } },
            onSummarize = { vm.summarize(character.id) }, onDismiss = { showMemory = false })
        if (showModel && character != null) ModelPicker(models, loadingModels, vm::refreshModels,
            onPick = { model -> vm.save(character.copy(model = model.id, contextTokens = model.contextTokens)) { showModel = false } },
            onManual = { showModel = false; editing = character; showEditor = true }, onDismiss = { showModel = false }, enabled = !busy)
    }
}

@Composable
private fun CharacterList(characters: List<CharacterEntity>, selected: String?, wide: Boolean, hasKey: Boolean, offline: Boolean,
    modifier: Modifier, onSelect: (String) -> Unit, onCreate: () -> Unit, onSettings: () -> Unit) {
    Column(modifier.fillMaxHeight().background(if (wide) Color(0xFF11111B) else Ink).padding(20.dp)) {
        Spacer(Modifier.height(if (wide) 8.dp else 18.dp))
        Text("YOUR LITTLE UNIVERSE", color = Lavender, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        Text("あなたの、特別な相手。", fontSize = if (wide) 21.sp else 26.sp, fontWeight = FontWeight.Bold)
        Text("話すほど、ふたりの記憶になる。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        if (!hasKey) Surface(onClick = onSettings, color = Lavender.copy(alpha = 0.09f), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, null, tint = Lavender)
                Text(if (offline) "端末モデルを取得してはじめる" else "APIキーを設定してはじめる", fontSize = 12.sp, color = Lavender, modifier = Modifier.padding(start = 10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("キャラクター", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${characters.size}", color = Muted, fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(characters, key = { it.id }) { character ->
                Surface(onClick = { onSelect(character.id) }, shape = RoundedCornerShape(20.dp),
                    color = if (wide && selected == character.id) Color(0xFF2A223C) else Panel,
                    border = BorderStroke(1.dp, if (wide && selected == character.id) Lavender.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.04f))) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(character.name, 48)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(character.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(character.subtitle.ifBlank { "あなた専用のキャラクター" }, fontSize = 11.sp, color = Muted,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            if (characters.isEmpty()) item { Text("名前も性格も、あなたの自由に。\n最初のキャラクターを作成しましょう。", color = Muted, fontSize = 13.sp) }
        }
        Button(onCreate, Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("キャラクターを作成")
        }
        Text("端末に保存  ·  OpenRouterで会話", color = Muted.copy(alpha = 0.6f), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp))
    }
}

@Composable
private fun Avatar(name: String, size: Int) {
    Box(Modifier.size(size.dp).background(Brush.linearGradient(listOf(Color(0xFF574378), Color(0xFF29273D))), RoundedCornerShape((size * 0.34f).dp)), contentAlignment = Alignment.Center) {
        Text(name.take(1).ifBlank { "M" }, color = Color(0xFFE4D7FF), fontSize = (size * 0.4).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChatPane(character: CharacterEntity, messages: List<MessageEntity>, memory: MemoryEntity?, busy: Boolean,
    hasKey: Boolean, wide: Boolean, offline: Boolean, localModelName: String?, onBack: () -> Unit, onEdit: () -> Unit, onMemory: () -> Unit,
    onModel: () -> Unit, onSettings: () -> Unit, onSend: (String, () -> Unit) -> Unit, onRetry: () -> Unit, onStop: () -> Unit) {
    var draft by rememberSaveable(character.id) { mutableStateOf("") }
    val pending = messages.lastOrNull()?.role == "user"
    val scroll = rememberLazyListState()
    LaunchedEffect(character.id, messages.size, busy) { if (messages.isNotEmpty()) scroll.animateScrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF181321), Ink, Ink)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wide) IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "キャラクター一覧") }
            else Spacer(Modifier.width(12.dp))
            Avatar(character.name, 42)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(character.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (offline) "●  MEL RP · 端末内" else "●  OpenRouter", fontSize = 10.sp, color = Mint, modifier = Modifier.padding(top = 3.dp))
            }
            IconButton(onMemory, enabled = !busy) { Icon(Icons.Outlined.Psychology, "長期記憶", tint = Lavender) }
            IconButton(onEdit, enabled = !busy) { Icon(Icons.Outlined.Edit, "キャラクターを編集") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = onModel, enabled = !busy, label = { Text(if (offline) localModelName ?: "端末モデル未設定" else character.model, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(14.dp)) }, modifier = Modifier.weight(1f, fill = false))
            TextButton(onMemory, enabled = !busy) { Text(if (memory == null) "記憶を育てる" else "記憶あり", fontSize = 10.sp, color = Mint) }
        }
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(messages, key = { it.id }) { message -> MessageBubble(message, character.name) }
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Forum, null, Modifier.size(40.dp), tint = Lavender)
                    Text("ふたりの会話をはじめよう。", color = Muted, modifier = Modifier.padding(top = 16.dp))
                }
            }
            if (busy) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Lavender, strokeWidth = 2.dp)
                    Text("会話・記憶を処理しています…", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        if (pending && !busy) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("未回答のメッセージを保存しています", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onRetry) { Text("再試行") }
        }
        if (!hasKey) TextButton(onSettings, Modifier.align(Alignment.CenterHorizontally)) { Text(if (offline) "モデルを取得して会話する" else "APIキーを設定して会話する") }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(draft, onValueChange = { if (it.length <= 6000) draft = it },
                placeholder = { Text("${character.name}にメッセージ…", fontSize = 13.sp) },
                shape = RoundedCornerShape(22.dp), maxLines = 5, enabled = !busy && !pending,
                modifier = Modifier.weight(1f), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFF373045), unfocusedContainerColor = Panel))
            Spacer(Modifier.width(10.dp))
            FilledIconButton(onClick = { if (busy) onStop() else onSend(draft) { draft = "" } },
                enabled = busy || (hasKey && draft.isNotBlank() && !pending), modifier = Modifier.size(54.dp)) {
                if (busy) Icon(Icons.Outlined.Stop, "停止") else Icon(Icons.AutoMirrored.Outlined.Send, "送信")
            }
        }
        Text(if (offline) "会話・要約・記憶はこの端末の中で処理します" else "会話と要約は選択したモデルへ送信されます", fontSize = 9.sp, color = Muted.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, name: String) {
    val user = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "あなた" else name, fontSize = 10.sp, color = Muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp))
        Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = if (user) 5.dp else 20.dp, bottomStart = if (user) 20.dp else 5.dp),
            color = if (user) Color(0xFF47335F) else Color(0xFF20202D), modifier = Modifier.widthIn(max = 560.dp)) {
            SelectionContainer { Text(message.content, fontSize = 15.sp, lineHeight = 24.sp, modifier = Modifier.padding(16.dp)) }
        }
        Text(SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(message.createdAt)) +
            if (!user && message.model.isNotBlank()) "  ·  ${message.model}" else "",
            color = Muted.copy(alpha = 0.65f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))
    }
}

@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(26.dp), color = Panel, modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.94f).heightIn(max = 760.dp).imePadding()) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onDismiss) { Icon(Icons.Outlined.Close, "閉じる") }
                }
                content()
            }
        }
    }
}

@Composable
private fun SettingsDialog(hasKey: Boolean, busy: Boolean, offline: Boolean, deviceModels: ModelState, vm: ChatViewModel,
    onSave: (String, () -> Unit) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Sheet("接続の設定", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("端末内で動かす", fontWeight = FontWeight.SemiBold)
                    Text(if (offline) "APIキー不要 · クラウド送信なし" else "OpenRouterへ会話を送信します", color = Mint, fontSize = 11.sp)
                }
                Switch(offline, vm::setOffline, enabled = !busy)
            }
            if (offline) {
                Text("MEL RP · ローカルモデル", color = Lavender, fontSize = 13.sp)
                Text("初回だけモデルを取得します。その後は機内モードでも会話・自動要約ができます。キャラの口調と物語の連続性を重視した会話設定を使います。追加学習済みの独自モデルではありません。", color = Muted, fontSize = 12.sp)
                Text("推奨: ${LocalModelStore.NAME}\n約2.5GB · Apache 2.0 · Wi-Fiで取得\n端末での速度・発熱はモデルと空きメモリで変わります。", color = Muted, fontSize = 12.sp)
                if (deviceModels.downloading) {
                    LinearProgressIndicator(progress = { deviceModels.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(deviceModels.progress * 100).toInt()}%  ${deviceModels.status}", fontSize = 11.sp, color = Mint)
                    TextButton(vm::cancelModelDownload, enabled = !busy) { Text("取得を中止") }
                } else Button(vm::downloadModel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (deviceModels.models.any { it.sha256 == LocalModelStore.SHA }) "推奨モデルを選択" else "推奨モデルを取得（Wi-Fi・約2.5GB）")
                }
                deviceModels.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                if (!deviceModels.downloading && deviceModels.status.isNotBlank()) Text(deviceModels.status, color = Mint, fontSize = 11.sp)
                OutlinedButton({ importer.launch(arrayOf("*/*")) }, enabled = !busy && !deviceModels.downloading, modifier = Modifier.fillMaxWidth()) { Text("別のGGUFモデルを読み込む") }
                Text("Qwen系など対応するテキスト用Instruct GGUFを選択できます。RP用に追加学習したモデルも、対応形式へ変換すればここから読み込めます。", color = Muted, fontSize = 11.sp)
                deviceModels.models.forEach { model ->
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF242332)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(model.id == deviceModels.activeId, onClick = { vm.selectModel(model.id) }, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(model.name, fontSize = 12.sp, maxLines = 2)
                                Text(String.format(Locale.JAPAN, "%.2f GB · SHA %s", model.bytes / 1e9, model.sha256.take(12)), fontSize = 9.sp, color = Muted)
                            }
                            IconButton({ deleting = model.id }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "モデルを削除") }
                        }
                    }
                }
                TextButton(vm::releaseModel, enabled = !busy && deviceModels.active != null) { Text("モデルのメモリを解放") }
                Text("文脈は4096トークン、返信は最大512トークン。設定や長い投稿が上限を超えた場合は、履歴を残して停止します。アプリを閉じると生成は中断することがあります。", color = Muted, fontSize = 11.sp)
            } else {
            Text("OpenRouter API", color = Lavender, fontSize = 13.sp)
            Text("${if (hasKey) "✓ キーは保存済みです。変更する場合のみ入力してください。" else "OpenRouterで取得したAPIキーを登録してください。"}\nキーはAndroid Keystoreで暗号化し、この端末に保存します。", color = Muted, fontSize = 12.sp)
            OutlinedTextField(key, { key = it }, label = { Text("APIキー") }, placeholder = { Text("sk-or-…") }, visualTransformation = PasswordVisualTransformation(),
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSave(key) { key = ""; onDismiss() } }, enabled = !busy && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("暗号化して保存") }
            if (hasKey) TextButton(onClear, enabled = !busy, modifier = Modifier.align(Alignment.End)) { Text("保存したキーを削除", color = MaterialTheme.colorScheme.error) }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Text("モデルについて", fontWeight = FontWeight.SemiBold)
            Text("モデル一覧から自由に選択、またはIDを直接入力できます。会話と自動要約はAPI利用料が発生する場合があります。RPへの対応や出力条件は、モデル・提供元の規約に従います。", fontSize = 12.sp, color = Muted)
            }
            Text("プライバシー", fontWeight = FontWeight.SemiBold)
            Text(if (offline) "会話・キャラクター・記憶・モデルは端末に保存します。応答生成と要約にインターネットは使いません。推奨モデルの取得時だけHugging Faceへ接続します。クラウドへの自動切替はありません。アプリのバックアップは無効です。" else "会話・キャラクター・記憶は端末内のRoomに保存します。応答生成と要約の際は会話の文脈がOpenRouterとモデル提供元へ送信されます。アプリのクラウドバックアップは無効です。", fontSize = 12.sp, color = Muted)
        }
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("モデルを削除しますか？") },
        text = { Text("モデルファイルを削除して空き容量を増やします。キャラクター・履歴・記憶は残ります。") },
        confirmButton = { TextButton({ deleting = null; vm.deleteModel(id) }, enabled = !busy) { Text("削除") } },
        dismissButton = { TextButton({ deleting = null }) { Text("キャンセル") } }) }
}

@Composable
private fun CharacterEditor(original: CharacterEntity?, busy: Boolean, models: List<RemoteModel>, loading: Boolean,
    onRefresh: () -> Unit, offline: Boolean, onSave: (CharacterEntity) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val base = remember { original ?: CharacterEntity() }
    var name by remember { mutableStateOf(base.name) }
    var subtitle by remember { mutableStateOf(base.subtitle) }
    var persona by remember { mutableStateOf(base.persona) }
    var greeting by remember { mutableStateOf(base.greeting) }
    var model by remember { mutableStateOf(base.model) }
    var context by remember { mutableStateOf(base.contextTokens.toString()) }
    var temperature by remember { mutableFloatStateOf(base.temperature) }
    var picker by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    Sheet(if (original == null) "新しいキャラクター" else "キャラクターを編集", onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subtitle, { if (it.length <= 120) subtitle = it }, label = { Text("ひとこと紹介") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(persona, { if (it.length <= 6000) persona = it }, label = { Text("性格・話し方・世界観") }, minLines = 4,
                placeholder = { Text("大切にしてほしい設定を、自由に。") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(greeting, { if (it.length <= 2000) greeting = it }, label = { Text("最初の挨拶（作成時のみ使用）") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            if (!offline) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("会話モデル", modifier = Modifier.weight(1f), color = Lavender, fontSize = 13.sp)
                TextButton(onClick = { picker = true }, enabled = !busy) { Text("一覧から選ぶ") }
            }
            OutlinedTextField(model, { model = it.trim() }, label = { Text("OpenRouter モデルID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(context, { if (it.length <= 7 && it.all(Char::isDigit)) context = it }, label = { Text("コンテキスト上限（トークン）") },
                supportingText = { Text("モデルの実際の上限以下に設定。ID直接入力時は確認してください。") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else Text("端末モデルは接続の設定で選びます。端末内の文脈上限は4096トークンです。", color = Mint, fontSize = 12.sp)
            Text("創造性  ${String.format(Locale.JAPAN, "%.2f", temperature)}", color = Muted, fontSize = 12.sp)
            Slider(temperature, { temperature = it }, valueRange = 0f..2f)
            if (original != null) TextButton(onClick = { deleteConfirmation = true }, enabled = !busy) { Text("キャラクターを削除", color = MaterialTheme.colorScheme.error) }
        }
        Button(onClick = { onSave(base.copy(name = name.trim(), subtitle = subtitle.trim(), persona = persona.trim(), greeting = greeting.trim(), model = model, contextTokens = context.toIntOrNull() ?: 0, temperature = temperature)) },
            enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("保存する") }
    }
    if (picker) ModelPicker(models, loading, onRefresh, onPick = { model = it.id; context = it.contextTokens.toString(); picker = false }, onManual = { picker = false }, onDismiss = { picker = false })
    if (deleteConfirmation) AlertDialog(onDismissRequest = { deleteConfirmation = false }, title = { Text("削除しますか？") },
        text = { Text("このキャラクターの会話履歴と長期記憶も削除されます。元に戻せません。") },
        confirmButton = { TextButton(onClick = { deleteConfirmation = false; onDelete(base.id) }, enabled = !busy) { Text("削除") } },
        dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("キャンセル") } })
}

@Composable
private fun ModelPicker(models: List<RemoteModel>, loading: Boolean, onRefresh: () -> Unit, onPick: (RemoteModel) -> Unit,
    onManual: () -> Unit, onDismiss: () -> Unit, enabled: Boolean = true) {
    var search by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (models.isEmpty()) onRefresh() }
    Sheet("会話モデルを選ぶ", onDismiss) {
        OutlinedTextField(search, { search = it }, label = { Text("モデル名・IDで検索") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onManual) { Text("IDを直接入力") }
            TextButton(onRefresh, enabled = !loading) { Text(if (loading) "取得中…" else "一覧を更新") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val filtered = models.filter { it.name.contains(search, true) || it.id.contains(search, true) }
        LazyColumn(Modifier.heightIn(min = 120.dp, max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.id }) { model ->
                Surface(onClick = { onPick(model) }, enabled = enabled, color = Color(0xFF232130), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(model.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(model.id, fontSize = 10.sp, color = Muted)
                        val input = model.promptPrice.toDoubleOrNull()?.times(1_000_000)
                        val output = model.completionPrice.toDoubleOrNull()?.times(1_000_000)
                        Text("文脈 ${model.contextTokens} tokens  ·  " + if (input != null && output != null)
                            String.format(Locale.US, "入 $%.2f / 出 $%.2f / 100万tokens", input, output) else "料金は提供元で確認", color = Mint, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
            if (filtered.isEmpty() && !loading) item { Text("一覧がない場合は、IDを直接入力できます。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun MemoryDialog(id: String, memory: MemoryEntity?, busy: Boolean, offline: Boolean, onSave: (String) -> Unit, onSummarize: () -> Unit, onDismiss: () -> Unit) {
    var text by remember(id, memory?.updatedAt) { mutableStateOf(memory?.summary.orEmpty()) }
    Sheet("ふたりの長期記憶", onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("会話が24件を超えると、以前の内容を自動で要約します。直近8件はそのまま文脈に残し、元の履歴もすべて保存します。", color = Muted, fontSize = 12.sp)
            Text(if (memory == null) "まだ要約はありません" else "更新: " + SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(memory.updatedAt)), color = Mint, fontSize = 11.sp)
            OutlinedTextField(text, { if (it.length <= 3000) text = it }, label = { Text("覚えておいてほしいこと") }, minLines = 7,
                supportingText = { Text("${text.length} / 3000  ·  編集内容は次の自動要約に統合します") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onSummarize, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (offline) "端末内で今すぐ要約する" else "今すぐ要約する（APIを使用）") }
        }
        Button(onClick = { onSave(text) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("記憶を保存") }
    }
}
