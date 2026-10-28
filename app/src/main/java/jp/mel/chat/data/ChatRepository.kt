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
        if (fresh && character.greeting.isNotBlank()) dao.insertMessage(MessageEntity(
            characterId = character.id, role = "assistant", content = character.greeting))
    }

    suspend fun delete(id: String) = mutex.withLock { dao.deleteCharacter(id) }

    suspend fun editMemory(id: String, text: String) = mutex.withLock {
        require(text.length <= 3000) { "長期記憶は3000文字以内で入力してください。" }
        checkNotNull(dao.character(id)) { "キャラクターが見つかりません。" }
        val memory = dao.memory(id) ?: MemoryEntity(id)
        dao.saveMemory(memory.copy(summary = text.trim(), updatedAt = System.currentTimeMillis()))
    }

    /** A saved user turn remains pending until an assistant turn is committed. Retry never reinserts it. */
    suspend fun send(id: String, text: String?, key: String, onPersisted: suspend () -> Unit = {}): String? = mutex.withLock {
        val character = checkNotNull(dao.character(id)) { "キャラクターが見つかりません。" }
        val history = dao.messages(id)
        if (text != null) {
            val value = text.trim()
            require(value.isNotEmpty() && value.length <= 6000) { "メッセージは1〜6000文字で入力してください。" }
            check(history.lastOrNull()?.role != "user") { "未回答のメッセージがあります。「再試行」を押してください。" }
            dao.insertMessage(MessageEntity(characterId = id, role = "user", content = value))
        } else check(history.lastOrNull()?.role == "user") { "再試行するメッセージがありません。" }
        onPersisted()

        compact(character, key, force = false)
        val turns = ConversationPolicy.prompt(character, dao.memory(id), dao.messages(id))
        require(ConversationPolicy.cost(turns) <= ConversationPolicy.inputBudget(character)) {
            "直近の会話がコンテキスト上限を超えています。より大きい上限のモデルを選んで再試行してください。"
        }
        val response = service.complete(key, character.model, turns, character.temperature, ConversationPolicy.OUTPUT_TOKENS)
        database.withTransaction {
            dao.insertMessage(MessageEntity(characterId = id, role = "assistant", content = response, model = character.model))
            dao.saveCharacter(character.copy(updatedAt = System.currentTimeMillis()))
        }
        try {
            compact(character, key, force = false)
            null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "返信は保存済みです。自動要約に失敗しました。記憶画面から再試行できます。" }
    }

    suspend fun summarize(id: String, key: String) = mutex.withLock {
        compact(checkNotNull(dao.character(id)), key, force = true)
    }

    private suspend fun compact(character: CharacterEntity, key: String, force: Boolean) {
        while (true) {
            val memory = dao.memory(character.id)
            val all = dao.messages(character.id)
            val pending = all.filter { it.id > (memory?.throughMessageId ?: 0) }
            val overBudget = ConversationPolicy.cost(ConversationPolicy.prompt(character, memory, all)) > ConversationPolicy.inputBudget(character)
            if (!force && pending.size <= ConversationPolicy.SUMMARY_TRIGGER && !overBudget) return
            val batch = ConversationPolicy.summaryBatch(character, memory, pending)
            if (batch.isEmpty()) {
                if (overBudget) error("モデルのコンテキストが不足しています。キャラクターの設定で上限の大きいモデルを選んでください。")
                return
            }
            val summary = service.complete(key, character.model, ConversationPolicy.summaryPrompt(memory, batch), 0.2f, 768)
            require(summary.isNotBlank() && summary.length <= 3000) { "要約が長すぎます。別のモデルで再試行してください。" }
            // Summary and cursor are committed together; full history is never removed.
            dao.saveMemory(MemoryEntity(character.id, summary, batch.last().id))
        }
    }
}
