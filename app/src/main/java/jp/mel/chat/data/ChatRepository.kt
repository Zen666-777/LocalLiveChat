package jp.mel.chat.data

import androidx.room.withTransaction
import jp.mel.chat.network.ChatService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatRepository(private val database: ChatDatabase, private val service: ChatService) {
    val dao = database.chatDao()
    private val mutex = Mutex()

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
        dao.updateMessage(checkNotNull(pending).copy(content = value))
    }

    suspend fun editMemory(id: String, text: String) = mutex.withLock {
        require(text.length <= 3000) { "長期記憶は3000文字以内で入力してください。" }
        val plot = checkNotNull(dao.plot(id)) { "プロットが見つかりません。" }
        val memory = dao.memory(id) ?: MemoryEntity(characterId = plot.characterId, plotId = id)
        dao.saveMemory(memory.copy(summary = text.trim(), updatedAt = System.currentTimeMillis()))
    }

    /** A saved user turn remains pending until an assistant turn is committed. Retry never reinserts it. */
    suspend fun send(id: String, text: String?, key: String, onPersisted: suspend () -> Unit = {}): String? = mutex.withLock {
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
        require(service.tokenCount(core) <= inputBudget(character) - 256) {
            "キャラ詳細・プロット・ロアだけで文脈上限に近づいています。設定を短くするか、使うロアを減らす・キーワードを指定してください。投稿は保存されています。"
        }
        compact(character, plot, lore, key, force = false)
        val turns = prompt(character, plot, dao.memory(id), dao.messages(id), lore)
        require(service.tokenCount(turns) <= inputBudget(character)) {
            "直近の会話がコンテキスト上限を超えています。より大きい上限のモデルを選んで再試行してください。"
        }
        val response = service.complete(key, character.model, turns, character.temperature, service.outputLimit)
        database.withTransaction {
            dao.insertMessage(MessageEntity(characterId = character.id, plotId = id, role = "assistant", content = response, model = service.responseModel(character.model)))
            dao.saveCharacter(character.copy(updatedAt = System.currentTimeMillis()))
            dao.savePlot(plot.copy(updatedAt = System.currentTimeMillis()))
        }
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

    private fun inputBudget(character: CharacterEntity): Int =
        minOf(character.contextTokens, service.contextLimit ?: character.contextTokens) - maxOf(service.outputLimit, service.summaryLimit) - if (service.local) 128 else 512

    private fun prompt(character: CharacterEntity, plot: PlotEntity, memory: MemoryEntity?, messages: List<MessageEntity>, lore: List<LoreEntryEntity>) =
        ConversationPolicy.prompt(character, memory, messages, roleplay = service.local, plot = plot, lore = lore)

    private suspend fun compact(character: CharacterEntity, plot: PlotEntity, lore: List<LoreEntryEntity>, key: String, force: Boolean) {
        while (true) {
            val memory = dao.memory(plot.id)
            val all = dao.messages(plot.id)
            val pending = all.filter { it.id > (memory?.throughMessageId ?: 0) }
            val overBudget = service.tokenCount(prompt(character, plot, memory, all, lore)) > inputBudget(character)
            if (!force && pending.size <= ConversationPolicy.SUMMARY_TRIGGER && !overBudget) return
            // With a small on-device context, summarize more of the prefix when necessary.
            // Never discard turns or include the last pending user message in the summary.
            val retain = if (service.local && overBudget) 2 else ConversationPolicy.RETAIN_MESSAGES
            val batch = mutableListOf<MessageEntity>()
            for (message in pending.dropLast(retain)) {
                if (service.tokenCount(ConversationPolicy.summaryPrompt(memory, batch + message, service.local)) > inputBudget(character)) break
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
