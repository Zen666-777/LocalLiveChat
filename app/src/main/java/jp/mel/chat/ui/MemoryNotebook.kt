package jp.mel.chat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.mel.chat.data.*
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
internal fun MemoryNotebook(plot: PlotEntity, memory: MemoryEntity?, notes: List<MemoryNoteEntity>, trace: RecallTrace?, busy: Boolean, offline: Boolean,
    onSaveSummary: (String, () -> Unit) -> Unit, onSummarize: () -> Unit,
    onSaveNote: (MemoryNoteEntity, () -> Unit) -> Unit, onDeleteNote: (String, () -> Unit) -> Unit,
    onSearch: suspend (String, String) -> List<RecalledMessage>, onDismiss: () -> Unit) {
    var tab by rememberSaveable(plot.id) { mutableIntStateOf(0) }
    var summary by rememberSaveable(plot.id, memory?.updatedAt) { mutableStateOf(memory?.summary.orEmpty()) }
    var editing by rememberSaveable(plot.id) { mutableStateOf(false) }
    var editingId by rememberSaveable(plot.id) { mutableStateOf<String?>(null) }
    var sourceId by rememberSaveable(plot.id) { mutableStateOf<Long?>(null) }
    var draft by rememberSaveable(plot.id) { mutableStateOf("") }
    var query by rememberSaveable(plot.id) { mutableStateOf("") }
    var submitted by rememberSaveable(plot.id) { mutableStateOf("") }
    var attempt by rememberSaveable(plot.id) { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var results by remember(plot.id) { mutableStateOf(emptyList<RecalledMessage>()) }
    var viewing by remember(plot.id) { mutableStateOf<MessageEntity?>(null) }
    LaunchedEffect(plot.id, submitted, attempt) {
        if (submitted.isBlank()) return@LaunchedEffect
        searching = true; searchError = null; results = emptyList()
        try { results = onSearch(plot.id, submitted) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { searchError = "会話を検索できませんでした。もう一度試してください。" }
        finally { searching = false }
    }
    Sheet("${plot.title}の記憶", onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("記憶帳", "会話を探す", "要約").forEachIndexed { index, label ->
                FilterChip(tab == index, { tab = index }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        when (tab) {
            0 -> {
                Text("人物・関係・約束・伏線を個別に保存します。「常に参照」は毎回使い、それ以外は会話との一致で選びます。自動要約では上書きしません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (notes.isEmpty()) item { Text("残しておきたいことを追加するか、「会話を探す」から原文を記憶帳へ取り込めます。", fontSize = 13.sp) }
                    items(notes, key = { it.id }) { note ->
                        Surface(onClick = { editingId = note.id; sourceId = note.sourceMessageId; draft = ""; editing = true }, enabled = !busy && !searching,
                            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(note.title, fontWeight = FontWeight.SemiBold)
                                Text(note.category + " · " + if (!note.enabled) "参照しない" else if (note.pinned) "常に参照" else "関連時に参照", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                Text(note.content, fontSize = 13.sp, maxLines = 5)
                                if (note.sourceMessageId != null) Text("過去の会話から作成した記憶", fontSize = 10.sp)
                            }
                        }
                    }
                }
                Button({ editingId = null; sourceId = null; draft = ""; editing = true }, enabled = !busy && !searching,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("覚えておくことを追加") }
            }
            1 -> {
                Text("過去の原文を日本語の文字列で検索します。会話中も、要約済みの履歴から関連する原文を自動で探します。初回は検索の準備に時間がかかる場合があります。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(query, { if (it.length <= 300) query = it }, label = { Text("名前・場所・約束など") },
                    singleLine = true, enabled = !busy && !searching, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton({ submitted = query.trim(); attempt++ }, enabled = !busy && !searching && query.isNotBlank(), modifier = Modifier.align(Alignment.End)) { Text("このプロットを検索") }
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                searchError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                val visible = if (submitted.isBlank()) trace?.sources.orEmpty() else results
                if (submitted.isBlank()) {
                    Text("直近の返信で参照した原文：${visible.size}件", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    if (trace?.limited == true) Text("入力上限に合わせ、一部の検索結果は今回の返信に含めていません。", fontSize = 11.sp)
                } else if (!searching && searchError == null) Text("検索結果：${results.size}件（最大30件）", fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visible, key = { it.message.id }) { hit ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(messageLabel(hit.message), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                SelectionContainer { Text(hit.excerpt, fontSize = 13.sp) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton({ viewing = hit.message }) { Text("原文") }
                                    TextButton({ editingId = null; sourceId = hit.message.id; draft = hit.excerpt.take(1500); editing = true }, enabled = !busy && !searching) { Text("記憶帳へ") }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("物語の現在地を短くまとめた自動要約です。細かな約束などは記憶帳にも残せます。元の会話は消さずに保持します。", fontSize = 12.sp)
                    OutlinedTextField(summary, { if (it.length <= 3000) summary = it }, label = { Text("物語の要約") }, minLines = 6,
                        enabled = !busy, supportingText = { Text("${summary.length} / 3000文字") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onSummarize, enabled = !busy && !searching, modifier = Modifier.fillMaxWidth()) { Text(if (offline) "端末内で要約を更新" else "APIで要約を更新") }
                }
                Button({ onSaveSummary(summary) {} }, enabled = !busy && !searching, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("要約を保存") }
            }
        }
    }
    val original = notes.find { it.id == editingId }
    if (editing && (editingId == null || original != null)) MemoryNoteEditor(plot, original, sourceId, draft, busy,
        onSave = { note -> onSaveNote(note) { editing = false } }, onDelete = { id -> onDeleteNote(id) { editing = false } }, onDismiss = { editing = false })
    viewing?.let { message ->
        Sheet("元の会話", { viewing = null }) {
            Text(messageLabel(message), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                SelectionContainer { Text(message.content, fontSize = 14.sp) }
            }
        }
    }
}

private fun messageLabel(message: MessageEntity): String = SimpleDateFormat("yyyy/M/d HH:mm", Locale.JAPAN).format(Date(message.createdAt)) + " · " + if (message.role == "user") "あなた" else "キャラクター"

@Composable
private fun MemoryNoteEditor(plot: PlotEntity, original: MemoryNoteEntity?, sourceId: Long?, initial: String, busy: Boolean,
    onSave: (MemoryNoteEntity) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val newId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val base = remember(original?.id, newId) { original ?: MemoryNoteEntity(id = newId, plotId = plot.id, characterId = plot.characterId, content = initial, sourceMessageId = sourceId) }
    var title by rememberSaveable(base.id) { mutableStateOf(base.title) }
    var category by rememberSaveable(base.id) { mutableStateOf(base.category) }
    var content by rememberSaveable(base.id) { mutableStateOf(base.content) }
    var keywords by rememberSaveable(base.id) { mutableStateOf(base.keywords) }
    var pinned by rememberSaveable(base.id) { mutableStateOf(base.pinned) }
    var enabled by rememberSaveable(base.id) { mutableStateOf(base.enabled) }
    var deleting by rememberSaveable(base.id) { mutableStateOf(false) }
    Sheet(if (original == null) "覚えておくこと" else "記憶を編集", onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { if (it.length <= 80) title = it }, label = { Text("名前") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConversationPolicy.memoryCategories.forEach { value -> FilterChip(category == value, { category = value }, label = { Text(value) }, enabled = !busy) }
            }
            OutlinedTextField(content, { if (it.length <= 1500) content = it }, label = { Text("覚えておく内容・いつの出来事か") },
                enabled = !busy, minLines = 5, supportingText = { Text("${content.length} / 1500文字") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(keywords, { if (it.length <= 300) keywords = it }, label = { Text("検索の手がかり（別名・関連語など）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(pinned, { pinned = it }, enabled = !busy); Spacer(Modifier.width(10.dp)); Text("常に参照する（有効な記憶で8件まで）", fontSize = 12.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(enabled, { enabled = it }, enabled = !busy); Spacer(Modifier.width(10.dp)); Text("この記憶を使う", fontSize = 12.sp) }
            Text("設定変更や約束の達成後は、ここで内容を更新できます。長い記憶を常時参照すると入力上限に達する場合があります。", fontSize = 11.sp)
            if (original != null) TextButton({ deleting = true }, enabled = !busy) { Text("この記憶を削除", color = MaterialTheme.colorScheme.error) }
        }
        Button({ onSave(base.copy(title = title, category = category, content = content, keywords = keywords, pinned = pinned, enabled = enabled)) },
            enabled = !busy && title.isNotBlank() && content.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("保存") }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("この記憶を削除しますか？") }, text = { Text("記憶帳の項目を削除します。元の会話と要約は保持します。") },
        confirmButton = { TextButton({ onDelete(base.id) }, enabled = !busy) { Text("削除") } }, dismissButton = { TextButton({ deleting = false }) { Text("キャンセル") } })
}
