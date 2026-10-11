# Care Reminder

Turn recurring household care into a Hubitat device. Care Reminder tracks a job
such as filter cleaning, coffee-machine descaling, or equipment servicing and
makes it **due after an elapsed interval or a number of uses, whichever comes
first**. Its switch stays on while care is outstanding, including during snooze.

Each virtual device tracks one job. Use several devices with this same driver
for separate chores. Everything runs locally: no network requests, polling,
API key, account, companion app, or external library. Usage is supplied by your
own rules; this driver cannot discover appliance activity or actual maintenance.

## Installation and updates

1. Open **Drivers Code → New Driver**, paste
   [CareReminder.groovy](https://raw.githubusercontent.com/b69ca/hubitat-carereminder/main/CareReminder.groovy),
   and Save.
2. Create a virtual device with type **Care Reminder** and a useful name such as
   Coffee Machine Descaling.
3. Save Preferences. The initial interval begins at device creation; the driver
   initially assumes the task is current. Use Mark Due if it is already due, or
   Mark Done after actually completing it.
4. Add a dashboard switch tile or rules that react to the buttons below.

For updates, replace the existing driver source, Save, and run Initialize.
Initialization restores schedules while preserving the completion date, usage,
manual due flag, snooze, and notification history. Saving preferences recalculates
with those records; it does not restart the care interval. A shorter interval or
lower use limit can make a task immediately due.

## Configuration

| Preference | Default | Purpose |
| --- | --- | --- |
| Repeat after days | 30 | Elapsed 24-hour days since completion; 0 disables the time deadline. Fractional days are supported. |
| Repeat after uses | 0 | Positive whole-number usage threshold; 0 disables it. |
| Due-soon warning | 24 hours | Warn this long before the time deadline; 0 disables. Does not predict future usage. |
| Overdue after | 24 hours | Escalate this long after the earliest deadline; 0 immediately escalates. |
| Repeat reminder | 12 hours | Send a recurring button event while due/overdue; 0 disables repeat reminders. |
| Default snooze | 4 hours | Used by Snooze when no argument is supplied. |
| Debug logging | Off | Logs state/count diagnostics; disables automatically after 30 minutes. |

At least one of days or uses must be enabled. Allowed ranges are 0–3650 days,
0–1000000 uses, 0–8760 hours for soon/overdue/repeat, and 0.001–8760 snooze hours.
Positive day and repeat intervals must represent at least one second. Hubitat's
current preference UI retains up to three fractional digits, so use 0.001 days
(86.4 seconds) as the shortest UI-entered day interval. Fractional
hours work, but scheduled execution has second-level precision and can be late.
Usage values must be whole numbers. Missing or blank preferences use defaults;
malformed or out-of-range values expose `configurationError`, cancel the care
schedule, and retain the last switch/state values. Fix settings and Save or
Initialize to recover. Mark Done is blocked while configuration is invalid.

Recommended starting profiles (adjust to the equipment's own instructions):

- **Monthly cleaning:** 30 days, uses disabled, soon 24 hours, overdue 24 hours,
  repeat 12 hours.
- **After ten cycles:** days 0, uses 10; forward a confirmed cycle completion with
  `reportUse(1)`. Set days too if you also want a maximum elapsed interval.
- **Weekly household job:** 7 days, uses disabled, soon 12 hours, repeat 24 hours.

The intervals above are configuration examples, not maintenance advice for a
particular product.

## States and reminders

`current` means no threshold has been reached. `soon` is within the time-based
warning window. `due` means the earliest time, usage, or manual deadline has been
reached. `overdue` means the grace period has elapsed. `snoozed` means reminders
are temporarily suppressed for an outstanding task.

The switch is **on for due, overdue, and snoozed**; **off for current and soon**.
Turning it off when on records completion and resets both the elapsed interval
and usage count. Turning it on manually makes the task due. These commands only
affect this virtual reminder, not an appliance or physical outlet.

Snooze does not move the deadline, clear usage, or turn the switch off. Overdue
escalation and repeat events are held during snooze. When snooze expires, the
current overdue condition can emit button 2 and snooze expiry emits button 6;
these are separate events. With a zero overdue grace, due and overdue buttons can
both fire at the same evaluation. Pick the buttons appropriate to your rules to
avoid duplicate notifications.

Reminders are button events for your notification rules; the driver does not
send phone notifications itself. `notificationText` is updated before each actual
reminder button. Test Push events leave it unchanged. Soon/due/overdue events
are emitted once while that condition remains reached, including across restart.
Preference changes that return the task to a current state rearm future alerts.
Repeated reminder events are intentionally repeated.

## Commands

Capabilities: Actuator, Sensor, Switch, Refresh, Initialize, PushableButton.

| Command | Behavior |
| --- | --- |
| `on()` / `markDue()` | Set a manual due deadline, once; repeated calls retain it. |
| `off()` | Complete only if switch is on; repeated Off while current is a no-op. |
| `markDone()` | Record completion even if early; reset usage, snooze, and manual deadline, and begin a new interval. Every explicit call records a task completion. |
| `reportUse(uses)` | Add a positive whole number, up to 1000000 per call. Blank adds one. Stored total saturates at 1000000000. Invalid input is rejected without changing the count. |
| `snooze(hours)` | Suppress reminders on a due/overdue task; blank uses the preference. Calls while current/soon do not pre-snooze a future task. Rechecks the task before snoozing. |
| `cancelSnooze()` | End snooze manually, re-evaluate overdue escalation, and restart repeat cadence. Does not emit a snooze-expired event. |
| `refresh()` | Recalculate deadlines and restore the next care schedule. No external data is fetched. |
| `initialize()` | Restore schedules and attributes without clearing task records. |
| `push(buttonNumber)` | Emit test button 1–6 without changing task state. Can trigger real rules. |

## Buttons and attributes

| Button | Meaning |
| --- | --- |
| 1 | Task became due |
| 2 | Task became overdue |
| 3 | Repeat reminder |
| 4 | Task completed |
| 5 | Task due soon |
| 6 | Snooze expired; task still outstanding |

| Attribute | Meaning |
| --- | --- |
| `switch` | `on` = outstanding, `off` = current/soon. |
| `careState` | `current`, `soon`, `due`, `overdue`, `snoozed`. |
| `status` | `ready` or `configurationError`. |
| `dueReason` | `none`, `time`, `uses`, or `manual`; earliest deadline wins. Equal deadlines use time, then uses, then manual priority. |
| `nextDue` | Earliest known deadline in ISO hub-local time; `usage only` when waiting for an unknown future use threshold. An outstanding deadline remains in the past. |
| `daysRemaining` | Signed days to the time deadline, three decimals; -1 if time disabled. Can round to zero just before a deadline; use careState for decisions. |
| `usesSinceDone` | Reported count since completion. |
| `usesRemaining` | Remaining count, clamped at 0; -1 if counting disabled. |
| `completedTasks` | Number of recorded completions since creation; initial 0. |
| `lastDone` | ISO completion/start timestamp in hub time zone. At creation it is the beginning of the initial interval, not proof of actual maintenance. |
| `snoozedUntil` | ISO time while snoozed; otherwise `none`. |
| `lastSuccessfulUpdate` | Last successful local evaluation, ISO timestamp. No source freshness claim is implied. |
| `notificationText` | Last reminder/completion/snooze message. |
| `numberOfButtons` | 6. |
| `pushed` | Latest emitted button; repeated events remain real triggers. |

## Rule Machine and dashboard examples

- **Notify when due:** Button 1 pushed → send “Coffee machine needs descaling.”
  Use button 3 in a second rule for follow-up reminders and button 2 for escalation.
- **Count completed laundry cycles:** Appliance Cycle Watch button 2 → custom
  action `reportUse(1)` on a washer-filter Care Reminder. Test events also count;
  do not connect test pushes to a production counter unintentionally.
- **Physical done button:** Your wall button pushed → custom action `markDone()`.
  This also supports preventive care before the threshold is reached.
- **Dashboard:** A Switch tile shows on when care is due; tap it off after doing
  the job. An off tile tapped on makes care due manually. Label it accordingly.
- **Snooze:** A dashboard/custom rule action calls `snooze(4)`. The switch stays on
  so the outstanding task remains visible. Button 6 can notify that snooze ended.

You may trigger from custom `careState` values as well. Configure notifications
in Rule Machine or another app; no app installation is bundled with this driver.

## Reliability, troubleshooting, and removal

The driver keeps JSON-safe task records in Hubitat state. It schedules the next
known transition/reminder and reevaluates at least hourly while configuration is
valid. Delayed callbacks use current state; a completed task cannot be made due
by an old snooze callback. A missed repeat interval produces one catch-up reminder,
not a burst of every missed reminder. Overdue tasks and expired snoozes are
reconciled on Initialize after hub startup.

Time intervals are elapsed 24-hour periods, not a particular calendar day or
local clock hour. DST changes alter displayed local times, not elapsed intervals.
Manual clock jumps can advance/delay deadlines. The hub must be powered and have
a sensible clock. Usage accuracy depends on your forwarding rule: duplicate
reports count twice, missing reports undercount. No freshness/stale indicator is
needed for this local clock/counter; `lastSuccessfulUpdate` reflects evaluation.

If no reminder arrives, inspect `status`, enabled thresholds, snooze, scheduled
jobs, and your button-triggered notification rule. For usage-only setup,
`nextDue=usage only` is normal before the threshold is reached. Saving preferences
can move deadlines; completion does not happen just because preferences changed.
If the switch seems stuck on, use Mark Done after completing care. Push 4 tests
rules but deliberately does not complete the task.

Before removing the virtual device, remove forwarding and notification rules
that reference it. Device removal unschedules its jobs. Recreating it starts a
new interval, usage count, and history. No other devices are edited or deleted.

## Development

Run the actual driver with the simulated Hubitat behavior harness:

```sh
groovy tests/CareReminderTest.groovy
```

Fifteen Groovy 2.4 behavior groups cover initial configuration, exact deadlines,
soon/due/overdue events, usage boundaries and caps, earliest deadline selection,
manual commands, early completion, snooze suppression and expiry, repeat cadence,
delayed callbacks, invalid input/settings, preference changes, JSON persistence,
restart, test buttons, debug shutoff, and removal.

Platform references: [capabilities](https://docs2.hubitat.com/en/developer/driver/capability-list),
[common scheduling methods](https://docs2.hubitat.com/en/developer/common-methods-object),
and [driver lifecycle](https://docs2.hubitat.com/en/developer/driver/overview).
There is no upstream data service, access limit, external response format, or
external data license. License: MIT.

The 1.0.0 source compiled and ran on a real Hubitat hub on October 11, 2026.
Accelerated live checks covered usage threshold, scheduled due-soon/due/overdue,
repeat reminders, snooze retention and scheduled expiry, cancellation, On/Off,
Mark Done, Initialize, and recovery from an invalid all-disabled configuration.
The device log view was empty. Normal production defaults were restored and the
device was left current with a fresh interval. No household automation rules were
connected. The saved hub source exactly matched the tested local source:
SHA-256 `6823ae0c775336bb7e3993453d60a7381a410f90c89f8342f72d396071c69e3b`.
