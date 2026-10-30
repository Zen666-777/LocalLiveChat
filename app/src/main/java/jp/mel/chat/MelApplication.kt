package jp.mel.chat

import android.app.Application
import androidx.room.Room
import jp.mel.chat.data.ChatDatabase
import jp.mel.chat.data.ChatRepository
import jp.mel.chat.data.SecureApiKeyStore
import jp.mel.chat.network.OpenRouterClient
import jp.mel.chat.local.*

class MelApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, ChatDatabase::class.java, "mel-chat.db").addMigrations(ChatDatabase.MIGRATION_1_2, ChatDatabase.MIGRATION_2_3).build() }
    val api by lazy { OpenRouterClient() }
    val keys by lazy { SecureApiKeyStore(this) }
    val backend by lazy { BackendSettings(this) }
    val deviceModels by lazy { LocalModelStore(this) }
    val device by lazy { LocalChatService(this, deviceModels) }
    val service by lazy { RoutedChatService({ backend.offline.value }, device, api) }
    val repository by lazy { ChatRepository(database, service) }
}
