package com.exzell.simpooassessment.local

import android.content.Context
import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.local.model.SendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class LocalRepository(context: Context) {

    private val database by lazy {
        val driver = AndroidSqliteDriver(Database.Schema, context, name = "simpoo_assessment")
        Database(driver, Message.Adapter(EnumColumnAdapter(), EnumColumnAdapter()))
    }

    private val messageQueries by lazy { database.messageQueries }

    fun getMessagesForType(type: MessageType): Flow<List<Message>> {
        return messageQueries.allMessagesForType(type)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    fun selectSenderForType(type: MessageType): Flow<List<String>> {
        return messageQueries.senderForType(type).asFlow().mapToList(Dispatchers.IO)
    }

    fun getMessageBySenderAndType(sender: String, type: MessageType): Flow<List<Message>> {
        return messageQueries.allMessageBySenderWithType(type, sender)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    suspend fun saveMessage(message: Message, update: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (update) {
                messageQueries.update(
                    who = message.who,
                    type = message.type,
                    status = message.status,
                    body = message.body
                )
            } else {
                messageQueries.insertMessage(
                    _id = UUID.randomUUID().toString(),
                    who = message.who,
                    body = message.body,
                    type = message.type,
                    status = message.status,
                    is_by_me = message.is_by_me
                )
            }
        }
    }
}