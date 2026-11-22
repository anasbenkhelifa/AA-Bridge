package dev.caraalink.aa

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Records what a channel sends, in order. */
class FakeLink : AaLink {
    data class Sent(val channelId: Int, val control: Boolean, val messageId: Int, val body: ByteArray)

    val sent = ArrayList<Sent>()

    override fun sendControlEncrypted(messageId: Int, body: ByteArray) {
        sent += Sent(AaFraming.CHANNEL_CONTROL, false, messageId, body)
    }

    override fun sendChannelControl(channelId: Int, messageId: Int, body: ByteArray) {
        sent += Sent(channelId, true, messageId, body)
    }

    override fun sendChannelSpecific(channelId: Int, messageId: Int, body: ByteArray) {
        sent += Sent(channelId, false, messageId, body)
    }

    fun ids(): List<Int> = sent.map { it.messageId }
}

/** Runs every task on the calling thread, so a test sees the result at once. */
class DirectExecutor : AbstractExecutorService() {
    private var down = false
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { down = true }
    override fun shutdownNow(): MutableList<Runnable> { down = true; return ArrayList() }
    override fun isShutdown() = down
    override fun isTerminated() = down
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

/** First varint value of [field] in a serialized message, or null. */
fun ByteArray.field(field: Int): Long? = AaProtobuf.parse(this).firstOrNull { it.number == field }?.varint
