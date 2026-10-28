package jp.mel.chat

import android.app.Application
import androidx.room.Room
import jp.mel.chat.data.ChatDatabase
import jp.mel.chat.data.ChatRepository
import jp.mel.chat.data.SecureApiKeyStore
import jp.mel.chat.network.OpenRouterClient

class MelApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, ChatDatabase::class.java, "mel-chat.db").build() }
    val api by lazy { OpenRouterClient() }
    val keys by lazy { SecureApiKeyStore(this) }
    val repository by lazy { ChatRepository(database, api) }
}
