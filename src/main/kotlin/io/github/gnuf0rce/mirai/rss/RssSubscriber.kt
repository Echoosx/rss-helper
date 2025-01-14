package io.github.gnuf0rce.mirai.rss

import com.rometools.rome.feed.synd.*
import io.github.gnuf0rce.mirai.rss.data.*
import io.github.gnuf0rce.rss.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import net.mamoe.mirai.*
import net.mamoe.mirai.console.util.*
import net.mamoe.mirai.console.util.ContactUtils.getContactOrNull
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.*
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import java.io.File
import java.time.*
import kotlin.properties.*
import kotlin.reflect.*

object RssSubscriber : CoroutineScope by RssHelperPlugin.childScope("RssSubscriber") {
    private val histories get() = FeedRecordData.histories
    private val records get() = SubscribeRecordData.records
    private val mutex = Mutex()
    private val limit get() = RssContentConfig.limit
    private val forward get() = RssContentConfig.forward

    private var SyndEntry.history by object : ReadWriteProperty<SyndEntry, OffsetDateTime?> {
        override fun getValue(thisRef: SyndEntry, property: KProperty<*>): OffsetDateTime? {
            val second = histories[thisRef.uri] ?: return null
            return timestamp(mills = (second * 1000).toLong())
        }

        override fun setValue(thisRef: SyndEntry, property: KProperty<*>, value: OffsetDateTime?) {
            histories[thisRef.uri] = value.orMin().toInstant().toEpochMilli() / 1000.0
        }
    }

    @OptIn(ConsoleExperimentalApi::class)
    private suspend fun SubscribeRecord.sendMessage(block: suspend (Contact) -> Message) {
        for (id in contacts) {
            runCatching {
                Bot.instances.firstNotNullOf { it.getContactOrNull(id) }
            }.onFailure {
                logger.warning({ "查找联系人${id}失败" }, it)
            }.mapCatching { contact ->
                contact.sendMessage(block(contact))
            }.onFailure {
                logger.warning({ "向${id}发送消息失败" }, it)
            }
        }
    }

    @OptIn(ConsoleExperimentalApi::class)
    private suspend fun SubscribeRecord.sendFile(block: suspend () -> File?) {
        val file = block() ?: return
        for (id in contacts) {
            runCatching {
                Bot.instances.firstNotNullOfOrNull { it.getContactOrNull(id) }
            }.onFailure {
                logger.warning({ "查找联系人${id}失败" }, it)
            }.mapCatching { contact ->
                if (contact !is FileSupported) return@mapCatching
                file.toExternalResource().use { resource ->
                    contact.files.root.createFolder(file.extension).uploadNewFile(file.name, resource)
                }
            }.onFailure {
                logger.warning({ "向${id}发送文件失败" }, it)
            }
        }
    }

    private fun task(link: Url) = launch(SupervisorJob()) {
        while (isActive) {
            val record = mutex.withLock { records[link]?.takeIf { it.contacts.isNotEmpty() } } ?: return@launch
            delay(record.interval * 60 * 1000L)
            try {
                val feed = client.feed(link)
                feed.entries
                    .filter { it.history == null || it.last.orMin() > it.history.orMin() }
                    .forEach { entry ->
                        logger.info { "${entry.uri}: ${entry.last.orMin()} over ${entry.history}" }
                        record.sendFile { entry.getTorrent() }
                        record.sendMessage { contact -> entry.toMessage(contact, limit, forward) }
                        entry.history = entry.last.orNow()
                    }
            } catch (e: Throwable) {
                logger.warning({ "Rss: $link" }, e)
            }
        }
    }

    suspend fun add(url: Url, subject: Contact) = mutex.withLock {
        val old = records[url] ?: SubscribeRecord()
        val new = if (old.contacts.isEmpty()) {
            val feed = client.feed(url)
            val now = OffsetDateTime.now()
            for (entry in feed.entries) entry.history = now
            task(url)
            SubscribeRecord(contacts = setOf(subject.id), name = feed.title)
        } else {
            old.copy(contacts = old.contacts + subject.id)
        }
        records[url] = new
        new
    }

    suspend fun list(subject: Contact) = mutex.withLock {
        records.filter { (_, record) ->
            subject.id in record.contacts
        }
    }

    suspend fun interval(url: Url, duration: Int) = mutex.withLock {
        check(duration > 0) { "订阅时间需要正数" }
        val old = requireNotNull(records[url]) { "订阅不存在" }
        val new = old.copy(interval = duration)
        records[url] = new
        new
    }

    suspend fun stop(url: Url, subject: Contact) = mutex.withLock {
        val old = requireNotNull(records[url]) { "订阅不存在" }
        val new = old.copy(contacts = old.contacts - subject.id)
        records[url] = new
        new
    }

    fun start() {
        SubscribeRecordData.records.keys.forEach(::task)
    }

    fun stop() {
        coroutineContext.cancelChildren()
    }
}