package com.zhiyu.app.domain

/** A presentation receipt only. Raw messages, timestamps, RAG order and content are never rewritten. */
object ReplyTurnOrder {
    fun apply(messages: List<Message>, turns: List<List<String>>): List<Message> = turns.fold(messages) { rows, ids ->
        require(ids.size in 2..96 && ids.distinct().size == ids.size && ids.all { it.isNotBlank() && it.length <= 160 })
        val available = rows.associateBy { it.id }
        val members = ids.mapNotNull(available::get)
        // A page missing part of a turn keeps its raw order; do not move messages across a pagination boundary.
        if (members.size != ids.size) rows else {
            val selected = ids.toSet()
            val first = rows.indexOfFirst { it.id in selected }
            buildList { rows.forEachIndexed { index, message ->
                if (index == first) addAll(members)
                if (message.id !in selected) add(message)
            } }
        }
    }
}
