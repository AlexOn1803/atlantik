package ua.madeisy.atlantic.core

/**
 * Мінімальний JSON-парсер/серіалізатор без залежностей.
 * Об'єкти -> LinkedHashMap<String, Any?>, масиви -> List<Any?>,
 * числа -> Long або Double, рядки -> String, true/false -> Boolean, null -> null.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("Зайві символи в JSON на позиції ${p.pos}")
        return v
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Float -> write(sb, v.toDouble())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList())
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("Несподіваний кінець JSON")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else
                    throw IllegalArgumentException("Неочікуваний символ '$c' на позиції $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("Очікувалось $word на позиції $pos")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (s[pos] != ':') throw IllegalArgumentException("Очікувалось ':' на позиції $pos")
                pos++
                map[key] = readValue()
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("Очікувалось ',' або '}' на позиції $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("Очікувалось ',' або ']' на позиції $pos")
                }
            }
        }

        private fun readString(): String {
            if (s[pos] != '"') throw IllegalArgumentException("Очікувався рядок на позиції $pos")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Незакритий рядок")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Невідома послідовність \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val str = s.substring(start, pos)
            return if (str.any { it == '.' || it == 'e' || it == 'E' }) str.toDouble()
            else str.toLongOrNull() ?: str.toDouble()
        }
    }
}

// ---- зручні аксесори ----

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

fun Any?.asLongOrNull(): Long? = when (this) {
    is Number -> this.toLong()
    is String -> this.trim().toLongOrNull() ?: this.trim().toDoubleOrNull()?.toLong()
    else -> null
}

fun Any?.asDoubleOrNull(): Double? = when (this) {
    is Number -> this.toDouble()
    is String -> this.trim().replace(',', '.').toDoubleOrNull()
    else -> null
}

fun Any?.asStringOrNull(): String? = when (this) {
    null -> null
    is String -> this
    is Double -> if (this == Math.floor(this) && Math.abs(this) < 1e15) this.toLong().toString() else this.toString()
    is Map<*, *>, is List<*> -> Json.stringify(this)
    else -> this.toString()
}
