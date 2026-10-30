package jp.mel.chat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.mel.chat.data.*
import kotlinx.coroutines.CancellationException

@Composable
internal fun PlotPicker(plots: List<PlotEntity>, selected: String?, busy: Boolean,
    onPick: (String) -> Unit, onNew: () -> Unit, onEdit: (PlotEntity) -> Unit, onDismiss: () -> Unit) {
    Sheet("プロットを選ぶ", onDismiss) {
        Text("プロットごとに会話と長期記憶を保存します。設定を共有しても、出来事や関係性の記憶は別々です。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(plots, key = { it.id }) { plot ->
                Surface(onClick = { onPick(plot.id) }, enabled = !busy, shape = RoundedCornerShape(16.dp),
                    color = if (plot.id == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(plot.id == selected, { onPick(plot.id) }, enabled = !busy)
                        Column(Modifier.weight(1f)) {
                            Text(plot.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(plot.setup.ifBlank { "自由な物語" }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                        }
                        IconButton({ onEdit(plot) }, enabled = !busy) { Icon(Icons.Outlined.Edit, "プロットを編集") }
                    }
                }
            }
        }
        Button(onNew, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("新しいプロット") }
    }
}

@Composable
internal fun PlotEditor(original: PlotEntity?, character: CharacterEntity, lore: List<LoreEntryEntity>, busy: Boolean,
    canDelete: Boolean, loadLoreIds: suspend (String) -> Set<String>,
    onSave: (PlotEntity, Set<String>) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val newId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val base = remember(original?.id) { original ?: PlotEntity(id = newId, characterId = character.id, greeting = character.greeting) }
    var title by rememberSaveable(base.id) { mutableStateOf(base.title) }
    var setup by rememberSaveable(base.id) { mutableStateOf(base.setup) }
    var greeting by rememberSaveable(base.id) { mutableStateOf(base.greeting) }
    var selected by rememberSaveable(base.id) { mutableStateOf(arrayListOf<String>()) }
    var loaded by rememberSaveable(base.id) { mutableStateOf(original == null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(base.id, attempt) {
        if (loaded) return@LaunchedEffect
        try { selected = ArrayList(loadLoreIds(base.id)); loaded = true; loadError = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = "選択中のロアを読み込めませんでした。" }
    }
    Sheet(if (original == null) "新しいプロット" else "プロットを編集", onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${character.name}との物語", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            OutlinedTextField(title, { if (it.length <= 80) title = it }, label = { Text("プロット名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(setup, { if (it.length <= 6000) setup = it }, label = { Text("あらすじ・舞台・開始状況・目標") },
                minLines = 5, modifier = Modifier.fillMaxWidth(), placeholder = { Text("どこで、どんな関係から始める？ 大切にしたい物語の設定を書いてください。") })
            OutlinedTextField(greeting, { if (it.length <= 2000) greeting = it }, label = { Text("最初の挨拶（このプロットの作成時に使用）") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text("このプロットで使うロア", fontWeight = FontWeight.SemiBold)
            if (!loaded && loadError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ attempt++ }) { Text("読み直す") } }
            if (lore.isEmpty()) Text("ロアブックから人物詳細や世界観を登録できます。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lore.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(entry.id in selected, { checked -> selected = ArrayList(if (checked) selected + entry.id else selected - entry.id) }, enabled = !busy && loaded)
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, fontSize = 13.sp)
                        Text(entry.category + if (entry.keywords.isBlank()) " · 常時使用" else " · キーワードで使用", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("端末モデルには文脈の上限があります。本文は短くまとめ、必要なロアを選ぶと安定します。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (original != null) {
                TextButton({ confirmDelete = true }, enabled = !busy && canDelete) { Text("このプロットを削除", color = MaterialTheme.colorScheme.error) }
                if (!canDelete) Text("最後のプロットです。削除するには先に別のプロットを作成してください。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button({ onSave(base.copy(title = title.trim(), setup = setup.trim(), greeting = greeting.trim()), selected.toSet()) },
            enabled = !busy && loaded && title.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("保存する") }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("プロットを削除しますか？") },
        text = { Text("このプロットの会話と長期記憶を削除します。ほかのプロットとロアブックは残ります。元に戻せません。") },
        confirmButton = { TextButton({ confirmDelete = false; onDelete(base.id) }, enabled = !busy) { Text("削除") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("キャンセル") } })
}

@Composable
internal fun LorebookDialog(plotTitle: String, entries: List<LoreEntryEntity>, enabledIds: Set<String>, busy: Boolean,
    onToggle: (String, Boolean) -> Unit, onNew: () -> Unit, onEdit: (LoreEntryEntity) -> Unit, onDismiss: () -> Unit) {
    Sheet("ロアブック", onDismiss) {
        Text(plotTitle, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Text("チェックした項目を、このプロットで使います。人物の容姿・過去・口調・関係性、場所や世界観を項目ごとに登録できます。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 80.dp, max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.id }) { entry ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(entry.id in enabledIds, { onToggle(entry.id, it) }, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, fontWeight = FontWeight.SemiBold)
                                Text(entry.category + if (entry.keywords.isBlank()) " · 常時" else " · ${entry.keywords}", fontSize = 10.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ onEdit(entry) }, enabled = !busy) { Icon(Icons.Outlined.Edit, "ロアを編集") }
                        }
                        Text(entry.content, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp, bottom = 5.dp))
                    }
                }
            }
            if (entries.isEmpty()) item { Text("人物詳細や世界観を追加して、物語の資料を育てましょう。", fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp)) }
        }
        Button(onNew, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("項目を追加") }
    }
}

@Composable
internal fun LoreEditor(original: LoreEntryEntity?, characterId: String, busy: Boolean,
    onSave: (LoreEntryEntity) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val newId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val base = remember(original?.id) { original ?: LoreEntryEntity(id = newId, characterId = characterId) }
    var title by rememberSaveable(base.id) { mutableStateOf(base.title) }
    var category by rememberSaveable(base.id) { mutableStateOf(base.category) }
    var content by rememberSaveable(base.id) { mutableStateOf(base.content) }
    var keywords by rememberSaveable(base.id) { mutableStateOf(base.keywords) }
    var confirmDelete by remember { mutableStateOf(false) }
    Sheet(if (original == null) "ロアを追加" else "ロアを編集", onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { if (it.length <= 80) title = it }, label = { Text("項目名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConversationPolicy.loreCategories.forEach { value -> FilterChip(category == value, { category = value }, label = { Text(value, fontSize = 11.sp) }) }
            }
            OutlinedTextField(content, { if (it.length <= 6000) content = it }, label = { Text("詳細・設定") }, minLines = 6,
                placeholder = { Text("容姿・一人称・話し方・過去・人物関係など、覚えてほしい設定を記入してください。") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(keywords, { if (it.length <= 300) keywords = it }, label = { Text("キーワード（任意・カンマ区切り）") },
                supportingText = { Text("空欄なら選択したプロットで常時使用。指定すると、プロット設定や直近6件の会話に含まれる時だけ使用します。") }, modifier = Modifier.fillMaxWidth())
            Text(if (original == null) "新しい項目は現在のプロットで使う設定になります。ロアブックのチェックで、プロットごとに切り替えられます。" else "本文の変更は、この項目を使うほかのプロットにも反映されます。会話から作る長期記憶はプロットごとに別です。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (original != null) TextButton({ confirmDelete = true }, enabled = !busy) { Text("この項目を削除", color = MaterialTheme.colorScheme.error) }
        }
        Button({ onSave(base.copy(title = title.trim(), category = category, content = content.trim(), keywords = keywords.trim())) },
            enabled = !busy && title.isNotBlank() && content.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("保存する") }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("ロアを削除しますか？") },
        text = { Text("この項目を使っている全プロットの設定から外れます。会話履歴と長期記憶は残ります。元に戻せません。") },
        confirmButton = { TextButton({ confirmDelete = false; onDelete(base.id) }, enabled = !busy) { Text("削除") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("キャンセル") } })
}
