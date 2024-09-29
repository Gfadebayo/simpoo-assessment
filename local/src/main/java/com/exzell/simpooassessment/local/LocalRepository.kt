package com.exzell.simpooassessment.local

import android.content.Context
import android.util.Log
import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.local.model.SendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class LocalRepository(context: Context, private val cryptoManager: CryptographyManager) {

    private val database by lazy {
        val driver = AndroidSqliteDriver(Database.Schema, context, name = "simpoo_assessment")
        Database(driver, Message.Adapter(EnumColumnAdapter(), EnumColumnAdapter()))
    }

    private val messageQueries by lazy { database.messageQueries }

    fun getMessagesForType(type: MessageType): Flow<List<Message>> {
        return messageQueries.allMessagesForType(type)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { it.decrypt() }
    }

    fun selectSenderForType(type: MessageType): Flow<List<String>> {
        return messageQueries.senderForType(type)
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    fun getMessageBySenderAndType(sender: String, type: MessageType, decrypt: Boolean = true): Flow<List<Message>> {
        return messageQueries.allMessageBySenderWithType(type, sender)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { if(decrypt) it.decrypt() else it }
    }

    suspend fun updateStatus(status: SendStatus, id: String) {
        withContext(Dispatchers.IO) {
            messageQueries.updateStatus(status, id)
        }
    }

    suspend fun saveMessage(message: Message, update: Boolean = false): String {
        return withContext(Dispatchers.IO) {
            Log.d("LocalRepo", "Saving: $message")

            val encBody = cryptoManager.encryptData(message.body)

            if (update) {
                messageQueries.update(
                    who = message.who,
                    type = message.type,
                    status = message.status,
                    body = encBody
                )

                message._id
            }
            else {
                val id = UUID.randomUUID().toString()
                messageQueries.insertMessage(
                    _id = id,
                    who = message.who,
                    body = encBody,
                    type = message.type,
                    status = message.status,
                    is_by_me = message.is_by_me
                )

                id
            }
        }
    }

    private fun List<Message>.decrypt(): List<Message> {
        return map { it.decrypt() }
    }

    private fun Message.decrypt(): Message {
//        val decWho = String(cryptoManager.decryptData(who))
        val decBody = String(cryptoManager.decryptData(body))

        return this.copy(
            body = decBody
        )
    }
}