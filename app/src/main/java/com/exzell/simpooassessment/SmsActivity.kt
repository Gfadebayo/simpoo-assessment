package com.exzell.simpooassessment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import com.exzell.simpooassessment.data.SmsManager
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.exzell.simpooassessment.core.localRepo
import com.exzell.simpooassessment.databinding.ActivitySmsBinding
import com.exzell.simpooassessment.databinding.ItemChatBinding
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.ui.utils.viewBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SmsActivity: AppCompatActivity(R.layout.activity_sms) {

    private val binding by viewBinding { ActivitySmsBinding.bind(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.apply {
            setSupportActionBar(toolbar)

            toolbar.addMenuProvider(object: MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.sms, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    if(menuItem.itemId == R.id.action_new) {
                        showNewSms()
                    }

                    return true
                }
            })

            buttonNewSms.setOnClickListener { showNewSms() }

            layoutNewSms.buttonSend.setOnClickListener {
                val phone = layoutNewSms.textInputPhone.editText!!.text.toString()
                val message = layoutNewSms.textInputMessage.editText!!.text.toString()

                lifecycleScope.launch {
                    SmsManager.sendSMS(applicationContext, phone, message, localRepo)
                    layoutNewSms.root.isVisible = false
                }
            }

            recyclerContact.adapter = ContactAdapter().apply {
                onClick = { showChat(it) }
            }

            recyclerChat.adapter = ChatAdapter()

            localRepo.selectSenderForType(MessageType.SMS)
                .map { list -> list.map { Contact(it, it) } }
                .onEach { (recyclerContact.adapter as ContactAdapter).submitList(it) }
                .launchIn(lifecycleScope)

            recyclerContact.isVisible = true
            recyclerChat.isVisible = false
            layoutNewSms.root.isVisible = false
        }
    }

    private fun showNewSms() {
        binding.layoutNewSms.apply {
            textInputPhone.editText!!.text = null
            textInputMessage.editText!!.text = null

            root.isVisible = true
        }
    }

    private fun showChat(contact: Contact) {
        binding.recyclerChat.apply {
            isVisible = true

            localRepo.getMessageBySenderAndType(contact.id, MessageType.SMS)
                .map { it.map { message -> Chat(message._id, message.body, message.status.name, message.is_by_me) } }
                .onEach { (adapter as ChatAdapter).submitList(it) }
                .launchIn(lifecycleScope)
        }
    }
}

data class Chat(
    val id: String,
    val body: String,
    val status: String,
    val isFromUser: Boolean
)

class ChatAdapter: ListAdapter<Chat, ChatAdapter.ViewHolder>(DIFF_UTIL) {
    companion object {
        val DIFF_UTIL = object: DiffUtil.ItemCallback<Chat>() {
            override fun areItemsTheSame(oldItem: Chat, newItem: Chat): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Chat, newItem: Chat): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false).run {
            ViewHolder(this)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.apply {
            val chat = currentList[position]

            textStatus.text = chat.status
            textChat.text = chat.body
            cardChat.updateLayoutParams<FrameLayout.LayoutParams> {
                this.gravity = if(chat.isFromUser) GravityCompat.END else GravityCompat.START
            }
        }
    }

    inner class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val binding = ItemChatBinding.bind(view).apply {

        }
    }
}