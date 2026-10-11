/**
 * Care Reminder 1.0.0
 * Copyright 2026 Jon Wallace. MIT License.
 */
metadata {
    definition(name: "Care Reminder", namespace: "jonw", author: "Jon Wallace",
        importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-carereminder/main/CareReminder.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "Refresh"
        capability "Initialize"
        capability "PushableButton"
        attribute "careState", "enum", ["current", "soon", "due", "overdue", "snoozed"]
        attribute "status", "enum", ["ready", "configurationError"]
        attribute "dueReason", "enum", ["none", "time", "uses", "manual"]
        attribute "nextDue", "string"
        attribute "daysRemaining", "number"
        attribute "usesSinceDone", "number"
        attribute "usesRemaining", "number"
        attribute "completedTasks", "number"
        attribute "lastDone", "string"
        attribute "snoozedUntil", "string"
        attribute "lastSuccessfulUpdate", "string"
        attribute "notificationText", "string"
        command "markDone"
        command "markDue"
        command "reportUse", [[name: "Uses", type: "NUMBER", description: "Positive whole number; blank adds one"]]
        command "snooze", [[name: "Hours", type: "NUMBER", description: "Positive hours; blank uses the preference"]]
        command "cancelSnooze"
    }
    preferences {
        input name: "intervalDays", type: "decimal", title: "Repeat after this many days (0 disables time)", defaultValue: 30, range: "0..3650", required: true
        input name: "useLimit", type: "number", title: "Repeat after this many uses (0 disables counting)", defaultValue: 0, range: "0..1000000", required: true
        input name: "soonHours", type: "decimal", title: "Due-soon warning (hours before time deadline, 0 disables)", defaultValue: 24, range: "0..8760", required: true
        input name: "overdueHours", type: "decimal", title: "Overdue after this many hours past due", defaultValue: 24, range: "0..8760", required: true
        input name: "repeatHours", type: "decimal", title: "Repeat reminder every this many hours (0 disables)", defaultValue: 12, range: "0..8760", required: true
        input name: "snoozeHours", type: "decimal", title: "Default snooze (hours)", defaultValue: 4, range: "0.001..8760", required: true
        input name: "logEnable", type: "bool", title: "Debug logging for 30 minutes", defaultValue: false
    }
}

void installed() {
    state.lastDone = now()
    state.uses = 0L
    state.completed = 0L
    initialize()
}
void updated() { initialize() }
void initialize() {
    unschedule()
    if (state.lastDone == null) state.lastDone = now()
    state.uses = state.uses ?: 0L
    state.completed = state.completed ?: 0L
    emit("numberOfButtons", 6)
    if (settings.logEnable) runIn(1800, "logsOff")
    refresh()
}
void uninstalled() { unschedule() }
void logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

private BigDecimal numeric(value, BigDecimal fallback, BigDecimal low, BigDecimal high, boolean whole = false) {
    try {
        BigDecimal n = new BigDecimal(value == null || value.toString().trim() == "" ? fallback.toString() : value.toString())
        if (n < low || n > high || (whole && n.stripTrailingZeros().scale() > 0)) return null
        return n
    } catch (Exception ignored) { return null }
}
private Map config() {
    BigDecimal days = numeric(settings.intervalDays, 30G, 0G, 3650G)
    BigDecimal uses = numeric(settings.useLimit, 0G, 0G, 1000000G, true)
    BigDecimal soon = numeric(settings.soonHours, 24G, 0G, 8760G)
    BigDecimal overdue = numeric(settings.overdueHours, 24G, 0G, 8760G)
    BigDecimal repeat = numeric(settings.repeatHours, 12G, 0G, 8760G)
    BigDecimal snooze = numeric(settings.snoozeHours, 4G, 0.001G, 8760G)
    if ([days, uses, soon, overdue, repeat, snooze].any { it == null } || (days == 0G && uses == 0G)) return null
    if ((days > 0G && days * 86400000G < 1000G) || (repeat > 0G && repeat * 3600000G < 1000G)) return null
    [interval: (days * 86400000G).longValue(), limit: uses.longValue(),
     soon: (soon * 3600000G).longValue(), overdue: (overdue * 3600000G).longValue(),
     repeat: (repeat * 3600000G).longValue(), snooze: snooze]
}

void on() { markDue() }
void off() { if (device.currentValue("switch") == "on") markDone() }

void markDue() {
    if (state.manualDue == null) state.manualDue = now()
    refresh()
}
void markDone() {
    if (!config()) { emit("status", "configurationError"); return }
    state.lastDone = now()
    state.uses = 0L
    state.completed = (state.completed ?: 0L) + 1L
    state.manualDue = null
    state.useDue = null
    state.snoozeUntil = null
    state.dueSent = false
    state.overdueSent = false
    state.soonSent = false
    state.lastReminder = null
    state.phase = "current"
    refresh()
    announce(4, "Care task completed")
}
void reportUse(value = null) {
    BigDecimal amount = numeric(value, 1G, 1G, 1000000G, true)
    if (amount == null) { log.warn "reportUse requires a positive whole number up to 1000000"; return }
    state.uses = Math.min(1000000000L, (state.uses ?: 0L) + amount.longValue())
    refresh()
}
void snooze(value = null) {
    Map c = config()
    if (!c) { emit("status", "configurationError"); return }
    BigDecimal hours = numeric(value, c.snooze, 0.001G, 8760G)
    if (hours == null) { log.warn "snooze requires positive hours from 0.001 through 8760"; return }
    refresh()
    if (!(state.phase in ["due", "overdue"])) return
    state.snoozeUntil = now() + (hours * 3600000G).longValue()
    refresh()
    emit("notificationText", "${device.displayName}: Reminders snoozed")
}
void cancelSnooze() {
    if (state.snoozeUntil == null) return
    state.snoozeUntil = null
    state.lastReminder = now()
    refresh()
    emit("notificationText", "${device.displayName}: Snooze cancelled")
}

void refresh() {
    unschedule("refresh")
    Map c = config()
    if (!c) {
        emit("status", "configurationError")
        // Retain the last public switch value rather than claim that care was done.
        return
    }
    long time = now()
    long uses = state.uses ?: 0L
    Long calendar = c.interval > 0 ? (state.lastDone as Long) + (c.interval as Long) : null
    if (c.limit > 0 && uses >= c.limit) {
        if (state.useDue == null) state.useDue = time
    } else state.useDue = null
    Map deadlines = [:]
    if (calendar != null) deadlines.time = calendar
    if (state.useDue != null) deadlines.uses = state.useDue as Long
    if (state.manualDue != null) deadlines.manual = state.manualDue as Long
    def first = deadlines ? deadlines.min { it.value } : null
    Long dueAt = first == null ? null : (first.value as Long)
    boolean due = dueAt != null && time >= dueAt
    String phase = due ? (time - dueAt >= c.overdue ? "overdue" : "due") :
        (calendar != null && c.soon > 0 && calendar - time <= c.soon ? "soon" : "current")
    boolean wasSnoozed = state.snoozeUntil != null
    boolean snoozed = due && wasSnoozed && time < (state.snoozeUntil as Long)
    if (!snoozed) state.snoozeUntil = null
    state.phase = phase
    emit("status", "ready")
    emit("careState", snoozed ? "snoozed" : phase)
    emit("switch", due ? "on" : "off")
    emit("dueReason", due ? first.key.toString() : "none")
    emit("nextDue", dueAt != null ? stamp(dueAt) : "usage only")
    emit("daysRemaining", calendar == null ? -1G : ((calendar - time) / 86400000G).setScale(3, BigDecimal.ROUND_HALF_UP), "days")
    emit("usesSinceDone", uses)
    emit("usesRemaining", c.limit > 0 ? Math.max(0L, (c.limit as Long) - uses) : -1L)
    emit("completedTasks", state.completed ?: 0L)
    emit("lastDone", stamp(state.lastDone as Long))
    emit("snoozedUntil", snoozed ? stamp(state.snoozeUntil as Long) : "none")
    emit("lastSuccessfulUpdate", stamp(time))

    if (!due) {
        state.dueSent = false
        state.overdueSent = false
        state.lastReminder = null
        if (phase == "soon" && !state.soonSent) {
            state.soonSent = true
            announce(5, "Care task is due soon")
        }
        if (phase == "current") state.soonSent = false
    } else if (!snoozed) {
        // A snooze affects reminders, never the outstanding task or its deadline.
        boolean announced = false
        if (!state.dueSent) {
            state.dueSent = true
            announce(1, "Care task is due")
            announced = true
        }
        if (phase == "overdue" && !state.overdueSent) {
            state.overdueSent = true
            announce(2, "Care task is overdue")
            announced = true
        }
        if (wasSnoozed) {
            announce(6, "Snooze ended; care task is still due")
            announced = true
        }
        if (!announced && c.repeat > 0 && state.lastReminder != null && time - (state.lastReminder as Long) >= c.repeat) {
            announce(3, "Care task is still ${phase}")
            announced = true
        }
        if (announced) state.lastReminder = time
    }
    if (settings.logEnable) log.debug "Care ${phase}; uses ${uses}; snoozed ${snoozed}"
    List<Long> next = [time + 3600000L]
    if (calendar != null && calendar > time) {
        next << calendar
        if (c.soon > 0 && calendar - c.soon > time) next << ((calendar - c.soon) as Long)
    }
    if (due && dueAt + c.overdue > time) next << ((dueAt + c.overdue) as Long)
    if (snoozed) next << (state.snoozeUntil as Long)
    else if (due && c.repeat > 0 && state.lastReminder != null) next << ((state.lastReminder as Long) + (c.repeat as Long))
    long delay = Math.max(1L, Math.ceil((next.min() - time) / 1000.0D).longValue())
    runIn(delay, "refresh")
}

private void announce(Integer button, String text) {
    emit("notificationText", "${device.displayName}: ${text}")
    push(button)
}
void push(value) {
    BigDecimal button = numeric(value, 0G, 1G, 6G, true)
    if (button == null) return
    sendEvent(name: "pushed", value: button.intValue(), isStateChange: true, type: "digital")
}
private String stamp(long time) { new Date(time).format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone ?: TimeZone.getTimeZone("UTC")) }
private void emit(String name, value, String unit = null) {
    if (device.currentValue(name)?.toString() == value?.toString()) return
    Map event = [name: name, value: value]
    if (unit) event.unit = unit
    sendEvent(event)
}
