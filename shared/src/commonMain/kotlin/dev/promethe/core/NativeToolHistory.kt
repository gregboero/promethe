package dev.promethe.core

import dev.promethe.db.MessageRow

/** Typed exchanges live only for the current execution, keyed by persisted observation identity. */
internal class NativeToolHistory {
    private val turns = mutableMapOf<Int, PendingToolTurn>()

    fun record(
        observationId: Int,
        turn: PendingToolTurn,
    ) {
        turns[observationId] = turn.copy(history = emptyList())
    }

    suspend fun prepare(
        visibleRows: List<MessageRow>,
        compress: suspend (List<Pair<String, String>>) -> List<Pair<String, String>>,
    ): Pair<List<Pair<String, String>>, PendingToolTurn?> {
        val first = visibleRows.indexOfFirst { it.id in turns }
        val pairs = visibleRows.map { it.role to it.content }
        if (first < 0) return compress(pairs) to null

        // Compress only the preceding conversation: never split or prune an active typed exchange.
        val prefix = compress(pairs.take(first))
        val history = visibleRows.drop(first).mapIndexedNotNull { offset, row ->
            turns[row.id]?.let { PositionedToolTurn(prefix.size + offset, it) }
        }
        return (prefix + pairs.drop(first)) to history.last().turn.copy(history = history)
    }
}
