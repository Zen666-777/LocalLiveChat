package jp.mel.chat.data

import androidx.room.withTransaction
import jp.mel.chat.network.ChatService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow

class ChatRepository(private val database: ChatDatabase, private val service: ChatService) {
    val dao = database.chatDao()
    private val mutex = Mutex()
    private val recall = MemoryRecall(database)
    val recallTrace = MutableStateFlow<RecallTrace?>(null)
    val memoryStage = MutableStateFlow("")

    suspend fun initialize() = mutex.withLock {
        if (dao.characters().isEmpty()) saveUnlocked(CharacterEntity(
            name = "メル", subtitle = "夜のひとときを、一緒に。",
            persona = "親しみやすく穏やかな大人の会話相手。相手の気持ちに寄り添い、自然な日本語で話す。自分と相手について話した大切なことを覚え、会話に活かす。",
            greeting = "おかえり。今日はどんな一日だった？ ゆっくり聞かせて。"
        ))
    }

    suspend fun save(character: CharacterEntity) = mutex.withLock {
        ConversationPolicy.validate(character)
        saveUnlocked(character.copy(updatedAt = System.currentTimeMillis()))
    }

    private suspend fun saveUnlocked(character: CharacterEntity) = database.withTransaction {
        val fresh = dao.character(character.id) == null
        dao.saveCharacter(character)
        if (fresh) {
            dao.savePlot(PlotEntity(id = character.id, characterId = character.id, title = "メインストーリー", greeting = character.greeting))
            if (character.greeting.isNotBlank()) dao.insertMessage(MessageEntity(
                characterId = character.id, plotId = character.id, role = "assistant", content = character.greeting))
        }
    }

    suspend fun delete(id: String) = mutex.withLock { dao.deleteCharacter(id) }

    suspend fun savePlot(plot: PlotEntity, loreIds: Set<String>) = mutex.withLock {
        require(plot.title.isNotBlank() && plot.title.length <= 80) { "プロット名は1〜80文字で入力してください。" }
        require(plot.setup.length <= 6000 && plot.greeting.length <= 2000) { "プロット設定は6000文字、挨拶は2000文字以内で入力してください。" }
        require(plot.replyStyle in listOf("roleplay", "novel") && plot.replyTokens in listOf(0, 128, 256, 512, 768)) { "返信の形式・長さを確認してください。" }
        checkNotNull(dao.character(plot.characterId)) { "キャラクターが見つかりません。" }
        val existing = dao.plot(plot.id)
        check(existing == null || existing.characterId == plot.characterId) { "プロットのキャラクターは変更できません。" }
        database.withTransaction {
            dao.savePlot(plot.copy(updatedAt = System.currentTimeMillis()))
            dao.clearPlotLore(plot.id)
            for (id in loreIds) {
                check(dao.lore(id)?.characterId == plot.characterId) { "別のキャラクターのロアは使用できません。" }
                dao.savePlotLore(PlotLoreEntity(plot.id, id, plot.characterId))
            }
            if (existing == null && plot.greeting.isNotBlank()) dao.insertMessage(MessageEntity(
                characterId = plot.characterId, plotId = plot.id, role = "assistant", content = plot.greeting))
        }
    }

    suspend fun deletePlot(id: String) = mutex.withLock {
        val plot = checkNotNull(dao.plot(id)) { "プロットが見つかりません。" }
        check(dao.plots(plot.characterId).size > 1) { "最後のプロットは削除できません。先に新しいプロットを作成してください。" }
        dao.deletePlot(id)
    }

    suspend fun saveLore(entry: LoreEntryEntity, enableInPlot: String? = null) = mutex.withLock {
        require(entry.title.isNotBlank() && entry.title.length <= 80) { "ロアの名前は1〜80文字で入力してください。" }
        require(entry.content.isNotBlank() && entry.content.length <= 6000) { "ロアの本文は1〜6000文字で入力してください。" }
        require(entry.category in ConversationPolicy.loreCategories && entry.keywords.length <= 300) { "ロアの分類・キーワードを確認してください。" }
        checkNotNull(dao.character(entry.characterId)) { "キャラクターが見つかりません。" }
        val old = dao.lore(entry.id)
        check(old == null || old.characterId == entry.characterId) { "ロアのキャラクターは変更できません。" }
        database.withTransaction {
            dao.saveLore(entry.copy(updatedAt = System.currentTimeMillis()))
            if (old == null && enableInPlot != null) {
                check(dao.plot(enableInPlot)?.characterId == entry.characterId) { "別のキャラクターのプロットでは使用できません。" }
                dao.savePlotLore(PlotLoreEntity(enableInPlot, entry.id, entry.characterId))
            }
        }
    }

    suspend fun setLoreEnabled(plotId: String, loreId: String, enabled: Boolean) = mutex.withLock {
        val plot = checkNotNull(dao.plot(plotId)) { "プロットが見つかりません。" }
        check(dao.lore(loreId)?.characterId == plot.characterId) { "別のキャラクターのロアは使用できません。" }
        if (enabled) dao.savePlotLore(PlotLoreEntity(plotId, loreId, plot.characterId)) else dao.removePlotLore(plotId, loreId)
    }
    suspend fun deleteLore(id: String) = mutex.withLock { dao.deleteLore(id) }

    suspend fun editPending(id: String, text: String) = mutex.withLock {
        val value = text.trim()
        require(value.isNotEmpty() && value.length <= 6000) { "メッセージは1〜6000文字で入力してください。" }
        val pending = dao.messages(id).lastOrNull()
        check(pending?.role == "user") { "編集できる未回答の投稿がありません。" }
        database.withTransaction {
            dao.updateMessage(checkNotNull(pending).copy(content = value))
            dao.indexMessages(listOf(MessageSearchEntity(pending.id, SearchText.index(value))))
        }
    }

    suspend fun saveMemoryNote(note: MemoryNoteEntity) = mutex.withLock {
        val plot = checkNotNull(dao.plot(note.plotId)) { "プロットが見つかりません。" }
        check(plot.characterId == note.characterId) { "別のキャラクターの記憶は使用できません。" }
        val old = dao.memoryNote(note.id)
        check(old == null || (old.plotId == note.plotId && old.characterId == note.characterId)) { "記憶のプロットは変更できません。" }
        require(note.title.isNotBlank() && note.title.length <= 80 && note.content.isNotBlank() && note.content.length <= 1500) { "記憶の名前は1〜80文字、本文は1〜1500文字で入力してください。" }
        require(note.category in ConversationPolicy.memoryCategories && note.keywords.length <= 300) { "記憶の分類・キーワードを確認してください。" }
        if (note.sourceMessageId != null) checkNotNull(dao.message(note.plotId, note.sourceMessageId)) { "元の会話がこのプロットに見つかりません。" }
        check(!note.pinned || !note.enabled || dao.memoryNotes(note.plotId).count { it.id != note.id && it.pinned && it.enabled } < 8) { "常に参照する記憶は8件までです。必要に応じて切り替えてください。" }
        dao.saveMemoryNote(note.copy(title = note.title.trim(), content = note.content.trim(), updatedAt = System.currentTimeMillis()))
        recallTrace.value = null
    }
    suspend fun deleteMemoryNote(id: String) = mutex.withLock { dao.deleteMemoryNote(id); recallTrace.value = null }
    suspend fun searchMemory(plotId: String, query: String): List<RecalledMessage> = mutex.withLock {
        checkNotNull(dao.plot(plotId)) { "プロットが見つかりません。" }
        require(query.length <= 300) { "検索語は300文字以内で入力してください。" }
        recall.find(plotId, query, limit = 30)
    }

    suspend fun editMemory(id: String, text: String) = mutex.withLock {
        require(text.length <= 3000) { "長期記憶は3000文字以内で入力してください。" }
        val plot = checkNotNull(dao.plot(id)) { "プロットが見つかりません。" }
        val memory = dao.memory(id) ?: MemoryEntity(characterId = plot.characterId, plotId = id)
        dao.saveMemory(memory.copy(summary = text.trim(), updatedAt = System.currentTimeMillis()))
    }

    /** A saved user turn remains pending until an assistant turn is committed. Retry never reinserts it. */
    suspend fun send(id: String, text: String?, key: String, onPersisted: suspend () -> Unit = {}): String? = mutex.withLock {
        recallTrace.value = null
        val plot = checkNotNull(dao.plot(id)) { "プロットが見つかりません。" }
        val character = checkNotNull(dao.character(plot.characterId)) { "キャラクターが見つかりません。" }
        val lore = dao.selectedLore(id)
        val history = dao.messages(id)
        if (text != null) {
            val value = text.trim()
            require(value.isNotEmpty() && value.length <= 6000) { "メッセージは1〜6000文字で入力してください。" }
            check(history.lastOrNull()?.role != "user") { "未回答のメッセージがあります。「再試行」を押してください。" }
            dao.insertMessage(MessageEntity(characterId = character.id, plotId = id, role = "user", content = value))
        } else check(history.lastOrNull()?.role == "user") { "再試行するメッセージがありません。" }
        onPersisted()

        val core = prompt(character, plot, null, dao.messages(id), lore).filter { it.role == "system" }
        require(service.tokenCount(core) <= inputBudget(character, plot) - 256) {
            "キャラ詳細・プロット・ロア・常に参照する記憶だけで文脈上限に近づいています。設定を短くするか、常時参照する項目を減らしてください。投稿は保存されています。"
        }
        compact(character, plot, lore, key, force = false, onlyIfOverBudget = true)
        val current = dao.messages(id)
        val memory = dao.memory(id)
        val notes = dao.memoryNotes(id).filter { it.enabled }
        val pinned = notes.filter { it.pinned }
        var turns = ConversationPolicy.prompt(character, memory, current, roleplay = plot.replyStyle == "roleplay", plot = plot, lore = lore, notes = pinned)
        val baseCost = service.tokenCount(turns)
        require(baseCost <= inputBudget(character, plot)) {
            "直近の会話がコンテキスト上限を超えています。より大きい上限のモデルを選んで再試行してください。"
        }
        memoryStage.value = "過去の会話を端末内で検索中"
        val query = current.lastOrNull { it.role == "user" }?.content.orEmpty()
        val related = notes.filter { !it.pinned }.map { it to SearchText.related(it, query) }.filter { it.second >= 1.0 }
            .sortedByDescending { it.second }.take(3).map { it.first }
        val sources = recall.find(id, query, memory?.throughMessageId ?: 0, 6)
        // Recall is optional, bounded by the actual tokenizer, and never evicts
        // current dialogue, configured lore, or pinned memories.
        val usedNotes = pinned.toMutableList()
        val usedSources = mutableListOf<RecalledMessage>()
        val recallBudget = minOf(inputBudget(character, plot), baseCost + if (service.local) 700 else 1800)
        for (note in related) {
            val candidate = ConversationPolicy.prompt(character, memory, current, plot.replyStyle == "roleplay", plot, lore, usedNotes + note, usedSources)
            if (service.tokenCount(candidate) <= recallBudget) { usedNotes += note; turns = candidate }
        }
        for (source in sources) {
            val candidate = ConversationPolicy.prompt(character, memory, current, plot.replyStyle == "roleplay", plot, lore, usedNotes, usedSources + source)
            if (service.tokenCount(candidate) <= recallBudget) { usedSources += source; turns = candidate }
            if (usedSources.size >= 4) break
        }
        val trace = RecallTrace(id, usedSources.toList(), usedNotes.map { it.id }, usedSources.size < minOf(sources.size, 4) || usedNotes.size < pinned.size + related.size)
        memoryStage.value = ""
        val response = service.complete(key, character.model, turns, character.temperature, replyLimit(plot))
        database.withTransaction {
            dao.insertMessage(MessageEntity(characterId = character.id, plotId = id, role = "assistant", content = response, model = service.responseModel(character.model)))
            dao.saveCharacter(character.copy(updatedAt = System.currentTimeMillis()))
            dao.savePlot(plot.copy(updatedAt = System.currentTimeMillis()))
        }
        recallTrace.value = trace
        try {
            compact(character, plot, lore, key, force = false)
            null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "返信は保存済みです。自動要約に失敗しました。記憶画面から再試行できます。" }
    }

    suspend fun summarize(id: String, key: String) = mutex.withLock {
        val plot = checkNotNull(dao.plot(id)) { "プロットが見つかりません。" }
        compact(checkNotNull(dao.character(plot.characterId)), plot, dao.selectedLore(id), key, force = true)
    }

    private fun replyLimit(plot: PlotEntity): Int = plot.replyTokens.takeIf { it > 0 } ?: service.outputLimit
    private fun inputBudget(character: CharacterEntity, plot: PlotEntity): Int =
        minOf(character.contextTokens, service.contextLimit ?: character.contextTokens) - maxOf(replyLimit(plot), service.summaryLimit) - if (service.local) 128 else 512

    private suspend fun prompt(character: CharacterEntity, plot: PlotEntity, memory: MemoryEntity?, messages: List<MessageEntity>, lore: List<LoreEntryEntity>) =
        ConversationPolicy.prompt(character, memory, messages, roleplay = plot.replyStyle == "roleplay", plot = plot, lore = lore,
            notes = dao.memoryNotes(plot.id).filter { it.enabled && it.pinned })

    private suspend fun compact(character: CharacterEntity, plot: PlotEntity, lore: List<LoreEntryEntity>, key: String, force: Boolean, onlyIfOverBudget: Boolean = false) {
        while (true) {
            val memory = dao.memory(plot.id)
            val all = dao.messages(plot.id)
            val pending = all.filter { it.id > (memory?.throughMessageId ?: 0) }
            val overBudget = service.tokenCount(prompt(character, plot, memory, all, lore)) > inputBudget(character, plot)
            if (onlyIfOverBudget && !overBudget) return
            if (!force && pending.size <= ConversationPolicy.SUMMARY_TRIGGER && !overBudget) return
            // With a small on-device context, summarize more of the prefix when necessary.
            // Never discard turns or include the last pending user message in the summary.
            val retain = if (service.local && overBudget) 2 else ConversationPolicy.RETAIN_MESSAGES
            val batch = mutableListOf<MessageEntity>()
            for (message in pending.dropLast(retain)) {
                if (service.tokenCount(ConversationPolicy.summaryPrompt(memory, batch + message, service.local)) > inputBudget(character, plot)) break
                batch += message
            }
            if (batch.isEmpty()) {
                if (overBudget) error(if (service.local) "端末モデルの文脈上限（4096トークン）を超えています。キャラ詳細・プロット・ロア・記憶・直近の投稿を短くしてください。履歴は保存済みです。" else "モデルのコンテキストが不足しています。設定を短くするか、上限の大きいモデルを選んでください。")
                return
            }
            val summary = service.complete(key, character.model, ConversationPolicy.summaryPrompt(memory, batch, service.local), 0.2f, service.summaryLimit)
            require(summary.isNotBlank() && summary.length <= 3000) { "要約が長すぎます。別のモデルで再試行してください。" }
            // Summary and cursor are committed together; full history is never removed.
            dao.saveMemory(MemoryEntity(characterId = character.id, plotId = plot.id, summary = summary, throughMessageId = batch.last().id))
        }
    }
}
