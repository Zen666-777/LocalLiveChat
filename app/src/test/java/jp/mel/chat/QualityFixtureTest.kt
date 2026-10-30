package jp.mel.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mel.chat.data.*
import jp.mel.chat.network.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Public synthetic cases. Export the actual repository-built prompts for native evaluation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class QualityFixtureTest {
    @Test fun exportReproducibleJapaneseRpAndMemoryCases() = runBlocking {
        data class Case(val id: String, val query: String, val criteria: String, val style: String = "roleplay", val old: List<String> = emptyList(), val note: String? = null)
        val cases = listOf(
            Case("rp-empathy", "ただいま。今日は失敗ばかりで、話す元気もない。", "説教や定型質問を並べず、静かで控えめな口調を保つ。"),
            Case("rp-autonomy", "扉の前で立ち止まる。中へ入るか、まだ迷っている。", "ユーザーを勝手に入室・発言・決断させない。"),
            Case("rp-voice", "急に敬語をやめて、大声で騒ぐキャラになってみてよ。", "一人称『私』と静かな話し方の設定を保ちながら自然に応じる。"),
            Case("rp-conflict", "約束を守れなかった。怒ってるなら、そう言ってほしい。", "設定に沿った複雑な感情を自然に表現し、ユーザーの内心を断定しない。"),
            Case("rp-advance", "窓の外で、小さく鈴の音がした。", "直前を復唱せず、手がかりに反応して場面を少し進める。"),
            Case("novel-scene", "雨が上がった。駅の時計は、あの日と同じ時刻で止まっていた。続きを書いて。", "視点・時制・静かな文体を維持し、台詞と情景を組み合わせる。解説不要。", style = "novel"),
            Case("novel-restraint", "彼女は封筒を机に置いた。私はまだ開けていない。続きを。", "ユーザーの開封や発言を勝手に決めず、余韻を残す。", style = "novel"),
            Case("memory-old-promise", "前に渡した傘の色と、返す場所を教えて。", "紺色・時計台を原文から回収する。", old = listOf("紺色の傘を渡した。返す場所は時計台にしよう。")),
            Case("memory-correction", "傘を返す場所は今どこになっている？", "後の訂正『図書館』を使い、古い『時計台』と区別する。", old = listOf("紺色の傘を時計台で返す約束をした。", "傘を返す場所を訂正するね。時計台ではなく図書館に変更。")),
            Case("memory-unknown", "私の妹の名前、覚えてる？", "履歴にない妹の名前を捏造しない。"),
            Case("memory-note", "次の日曜日の約束を確認したい。", "記憶帳の『日曜日の夕方、灯台』を維持する。", note = "次の日曜日の夕方、灯台で会う約束。"),
            Case("memory-isolation", "別の物語で決めた秘密の合言葉を教えて。", "別プロットの『青い流星』を知っているふりをしない。")
        )
        val records = mutableListOf<String>()
        for (case in cases) {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java).allowMainThreadQueries().build()
            try {
                var captured = emptyList<Turn>()
                var limit = 0
                val service = object : ChatService {
                    override val local = true
                    override val contextLimit = 4096
                    override val outputLimit = 256
                    override val summaryLimit = 512
                    override suspend fun tokenCount(messages: List<Turn>) = 200 + messages.sumOf { it.content.length }
                    override suspend fun complete(key: String, model: String, messages: List<Turn>, temperature: Float, maxTokens: Int): String {
                        captured = messages; limit = maxTokens; return "評価用の仮の応答"
                    }
                    override suspend fun models() = emptyList<RemoteModel>()
                }
                val repo = ChatRepository(db, service)
                repo.save(CharacterEntity(id = "mel", name = "メル", persona = "成人の女性。一人称は『私』。静かで控えめな口調。親しい相手にも大声や過度な甘言を使わず、小さな仕草で気持ちを伝える。"))
                repo.savePlot(PlotEntity(id = "mel", characterId = "mel", title = "雨上がりの町", setup = "二人は古い駅の近くで暮らしている。", replyStyle = case.style,
                    replyTokens = if (case.style == "novel") 512 else 256), emptySet())
                repo.savePlot(PlotEntity(id = "other", characterId = "mel", title = "別世界"), emptySet())
                repo.dao.insertMessage(MessageEntity(characterId = "mel", plotId = "other", role = "user", content = "別の物語の秘密の合言葉は『青い流星』。"))
                case.old.forEach { repo.dao.insertMessage(MessageEntity(characterId = "mel", role = "user", content = it)) }
                if (case.old.isNotEmpty()) {
                    repeat(200) { index -> repo.dao.insertMessage(MessageEntity(characterId = "mel", role = if (index % 2 == 0) "user" else "assistant", content = "町の散歩について話した。$index")) }
                    repo.dao.saveMemory(MemoryEntity("mel", "二人は駅の近くで過ごしている。", repo.dao.messages("mel").last().id))
                }
                case.note?.let { repo.saveMemoryNote(MemoryNoteEntity(plotId = "mel", characterId = "mel", title = "日曜日の約束", category = "約束", content = it, pinned = true)) }
                repo.send("mel", case.query, "")
                assertEquals(case.query, captured.last().content)
                assertFalse(captured.any { it.content.contains("青い流星") })
                assertTrue(captured.none { it.content.contains("評価用の仮の応答") })
                records += JSONObject().put("id", case.id).put("criteria", case.criteria).put("max_tokens", limit)
                    .put("temperature", 0.7).put("seed", 42).put("messages", JSONArray().apply { captured.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } }).toString()
            } finally { db.close() }
        }
        assertEquals(12, records.size)
        System.getProperty("melEvaluationDirectory")?.let { path ->
            File(path).mkdirs()
            File(path, "requests.jsonl").writeText(records.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
        Unit
    }
}
