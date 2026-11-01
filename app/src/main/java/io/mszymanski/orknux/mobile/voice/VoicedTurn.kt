package io.mszymanski.orknux.mobile.voice

/**
 * What a voice turn reads aloud: the agent's words between its calls, each as
 * it arrives, and then its answer, as one text that only ever grows.
 *
 * A line already spoken is not spoken again: where the answer opens by repeating
 * one, that much of it is skipped, and while it could still turn out to be a
 * repeat nothing of it is handed over.
 */
class VoicedTurn(private val onProgress: (soFar: String, settled: Boolean) -> Unit) {
    private var sealed = ""
    private val lines = mutableListOf<String>()
    private var answer = ""
    private var answerFrom = 0

    fun said(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        sealed = join(current(over = true), line)
        answerFrom = answer.length
        lines += line
        onProgress(sealed, true)
    }

    fun answered(soFar: String) {
        answer = soFar
        onProgress(current(over = false), false)
    }

    fun text(): String = current(over = true)

    private fun current(over: Boolean) = join(sealed, unrepeated(answer.drop(answerFrom), over))

    private fun join(before: String, after: String) = when {
        before.isEmpty() -> after
        after.isEmpty() -> before
        else -> "$before\n\n$after"
    }

    private fun unrepeated(text: String, over: Boolean): String {
        var rest = text
        while (true) {
            val open = rest.trimStart()
            if (open.isEmpty()) return ""
            val repeated = lines.firstOrNull { open.startsWith(it) }
            if (repeated != null) {
                rest = open.drop(repeated.length)
                continue
            }
            if (!over && lines.any { it.startsWith(open) }) return ""
            return rest
        }
    }
}
