package us.easyconnect.pcremote.net

import java.io.InputStream

/** Connection coordinates decoded from the pairing QR: {@code tcr://host:port/token}. */
data class PairInfo(val host: String, val port: Int, val token: String) {
    companion object {
        /** Parses a scanned QR payload, or returns null if it isn't a valid pairing URI. */
        fun parse(raw: String): PairInfo? {
            val s = raw.trim()
            if (!s.startsWith("tcr://")) return null
            val rest = s.removePrefix("tcr://")
            val slash = rest.indexOf('/')
            if (slash <= 0 || slash == rest.length - 1) return null
            val hostPort = rest.substring(0, slash)
            val token = rest.substring(slash + 1)
            val colon = hostPort.lastIndexOf(':')
            if (colon <= 0) return null
            val host = hostPort.substring(0, colon)
            val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
            if (token.isBlank()) return null
            return PairInfo(host, port, token)
        }
    }
}

/** Parsed result of the server's handshake reply: its protocol version + capabilities. */
data class ServerHello(val version: Int, val caps: Set<String>)

/** Builds the newline-terminated command lines understood by the PC server. */
object Protocol {
    const val LEFT = "L"
    const val RIGHT = "R"
    const val MIDDLE = "M"

    /** Wire-protocol version this client speaks. */
    const val VERSION = 1

    // Capability tokens the server may advertise; check membership before using
    // a feature so this client stays compatible with older/newer servers.
    const val CAP_INPUT = "input"
    const val CAP_FILE = "file"
    const val CAP_HSCROLL = "hscroll"

    fun hello(token: String) = "HELLO $token v$VERSION"

    /**
     * Parses a handshake reply like {@code "OK v1 caps=input,file"}. Any reply
     * beginning with "OK" is a success; missing version/caps default sensibly so
     * an older server that just says "OK" still connects.
     */
    fun parseServerHello(reply: String): ServerHello {
        var version = 0
        var caps = emptySet<String>()
        for (tok in reply.split(' ')) {
            when {
                tok.startsWith("v") -> tok.drop(1).toIntOrNull()?.let { version = it }
                tok.startsWith("caps=") -> caps = tok.removePrefix("caps=")
                    .split(',').filter { it.isNotBlank() }.toSet()
            }
        }
        return ServerHello(version, caps)
    }
    fun move(dx: Int, dy: Int) = "M $dx $dy"
    fun click(button: String) = "C $button"
    fun doubleClick(button: String) = "DC $button"
    fun down(button: String) = "DN $button"
    fun up(button: String) = "UP $button"
    fun scroll(amount: Int) = "SC $amount"
    fun scrollH(amount: Int) = "SCH $amount"
    fun type(text: String) = "K $text"

    /** Reads one UTF-8 line (up to '\n') straight from the socket stream. */
    fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        var any = false
        while (true) {
            val b = input.read()
            if (b == -1) break
            any = true
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.append(b.toChar())
        }
        return if (!any && buf.isEmpty()) null else buf.toString()
    }
}
