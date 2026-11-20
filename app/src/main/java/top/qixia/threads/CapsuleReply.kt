package top.qixia.threads

import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver

/** 仅向本次启动传入的 Binder 回报；请求编号防止旧服务结果污染新请求。 */
internal class CapsuleReply private constructor(val request: String, val pkg: String, private val receiver: ResultReceiver) {
    fun send(state: String, reason: String = "") {
        runCatching { receiver.send(0, Bundle().apply {
            putString("request_id", request)
            putString("target_pkg", pkg)
            putString("state", state)
            putString("reason", reason.take(240))
        }) }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun from(intent: Intent?): CapsuleReply? = runCatching {
            val request = intent?.getStringExtra("request_id").orEmpty()
            val pkg = intent?.getStringExtra("target_pkg").orEmpty()
            val receiver = intent?.getParcelableExtra<ResultReceiver>("result_receiver")
            if (request.length !in 16..64 || !pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) || receiver == null) null
            else CapsuleReply(request, pkg, receiver)
        }.getOrNull()
    }
}
