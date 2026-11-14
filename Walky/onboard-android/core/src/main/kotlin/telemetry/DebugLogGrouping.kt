package com.vadymsidorov.walky.core.telemetry

/** Takes visible events oldest first. Each entry occupies its latest member's position. */
fun groupDebugEvents(
    events: List<TelemetryEvent>,
    groupedKinds: Set<String>,
    windowSeconds: Int,
): List<List<TelemetryEvent>> {
    require(windowSeconds > 0)
    val windowNs = windowSeconds * 1_000_000_000L
    val open = mutableMapOf<String, MutableList<TelemetryEvent>>()
    val rows = linkedMapOf<Long, List<TelemetryEvent>>()
    for (event in events) {
        if (event.kind !in groupedKinds) {
            rows[event.seq] = listOf(event)
            continue
        }
        val group = open[event.kind]
        if (group != null && event.tsMonoNs - group.last().tsMonoNs in 0..windowNs) {
            rows.remove(group.last().seq)
            group.add(event)
            rows[event.seq] = group
        } else {
            val next = mutableListOf(event)
            open[event.kind] = next
            rows[event.seq] = next
        }
    }
    return rows.values.toList().asReversed()
}
