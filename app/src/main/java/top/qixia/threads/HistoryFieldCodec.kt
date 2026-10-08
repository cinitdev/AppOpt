package top.qixia.threads

import java.io.ByteArrayOutputStream

/** 可逆编码历史文件中的名称字段，避免名称中的分隔符破坏记录结构。 */
object HistoryFieldCodec {
    private const val ENCODED_PREFIX = "e1:"
    private const val LEGACY_DETAILS_PREFIX = "v2:"
    private const val ENCODED_DETAILS_PREFIX = "v3:"
    private const val PROCESS_DETAILS_PREFIX = "v3p:"

    data class ChildDetailsPayload(
        val body: String,
        val encodedNames: Boolean,
        val processAggregate: Boolean
    )

    fun encodeName(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return buildString(ENCODED_PREFIX.length + bytes.size) {
            append(ENCODED_PREFIX)
            for (byte in bytes) {
                val unsigned = byte.toInt() and 0xff
                if (isSafeVisibleAscii(unsigned)) {
                    append(unsigned.toChar())
                } else {
                    append('%')
                    append(HEX[unsigned ushr 4])
                    append(HEX[unsigned and 0x0f])
                }
            }
        }
    }

    fun decodeName(value: String): String {
        if (!value.startsWith(ENCODED_PREFIX)) return value
        val encoded = value.substring(ENCODED_PREFIX.length)
        val output = ByteArrayOutputStream(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val ch = encoded[index]
            if (ch == '%' && index + 2 < encoded.length) {
                val high = encoded[index + 1].digitToIntOrNull(16)
                val low = encoded[index + 2].digitToIntOrNull(16)
                if (high != null && low != null) {
                    output.write((high shl 4) or low)
                    index += 3
                    continue
                }
            }
            val codePoint = encoded.codePointAt(index)
            val plainBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            output.write(plainBytes)
            index += Character.charCount(codePoint)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    /**
     * 解析历史记录的子线程详情头。v3p 与 v3 使用相同的名称编码，区别仅在于
     * v3p 明确标记当前顶层记录是进程聚合，即使正文为空也不能当作普通线程。
     */
    fun parseChildDetails(value: String): ChildDetailsPayload? = when {
        value.startsWith(PROCESS_DETAILS_PREFIX) -> ChildDetailsPayload(
            body = value.removePrefix(PROCESS_DETAILS_PREFIX),
            encodedNames = true,
            processAggregate = true
        )
        value.startsWith(ENCODED_DETAILS_PREFIX) -> ChildDetailsPayload(
            body = value.removePrefix(ENCODED_DETAILS_PREFIX),
            encodedNames = true,
            processAggregate = false
        )
        value.startsWith(LEGACY_DETAILS_PREFIX) -> ChildDetailsPayload(
            body = value.removePrefix(LEGACY_DETAILS_PREFIX),
            encodedNames = false,
            processAggregate = false
        )
        else -> null
    }

    fun isProcessAggregateDetails(value: String): Boolean =
        value.startsWith(PROCESS_DETAILS_PREFIX)

    fun isProcessAggregateRecord(baseOwner: String, name: String, details: String): Boolean {
        if (isProcessAggregateDetails(details)) return true
        if (details.isBlank()) return false
        // v2/v3 和早期逗号列表没有显式类型，仅对包名/子进程名沿用旧推断。
        return (name == baseOwner || name.startsWith("$baseOwner:")) && !name.contains('{')
    }

    private fun isSafeVisibleAscii(value: Int): Boolean =
        value in 0x20..0x7e &&
            value != '%'.code && value != '|'.code && value != ','.code && value != ';'.code

    private const val HEX = "0123456789ABCDEF"
}
