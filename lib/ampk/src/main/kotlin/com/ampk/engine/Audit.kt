package com.ampk.engine

/** Structured audit trail — port of `ampk/audit.py`. One event per engine decision. */
data class AuditEvent(
    val seq: Int,
    val step: Int,
    val type: String,                 // "suggest" | "autocorrect" | "commit"
    val data: Map<String, Any?>,
    val elapsedMs: Double = 0.0,      // non-deterministic; excluded from golden comparisons
)

interface AuditSink {
    fun emit(event: AuditEvent)
}

object NullSink : AuditSink {
    override fun emit(event: AuditEvent) {}
}

class ListSink : AuditSink {
    val events = ArrayList<AuditEvent>()
    override fun emit(event: AuditEvent) {
        events.add(event)
    }
    fun byType(type: String): List<AuditEvent> = events.filter { it.type == type }
}
