import groovy.json.JsonOutput
import groovy.json.JsonSlurper
class HubDevice {
    Map values = [:], settings
    String displayName = 'Test appliance'
    Object currentValue(String key) { values[key] }
    void updateSetting(String key, Map setting) { settings[key] = setting.value == 'true' }
}
class Harness extends Script {
    Map state = [:], settings = [:], jobs = [:]
    List events = [], warnings = []
    HubDevice device = new HubDevice(settings: settings)
    Map location = [timeZone: TimeZone.getTimeZone('UTC')]
    long clock = 1000000L
    def log = [warn: { warnings << it }, debug: { }]
    Object run() { null }
    void metadata(Closure body) { }
    long now() { clock }
    void sendEvent(Map event) { events << event; device.values[event.name] = event.value }
    void runIn(Number seconds, String handler) { jobs[handler] = clock + seconds.longValue() * 1000L }
    void unschedule(String handler = null) { if (handler) jobs.remove(handler); else jobs.clear() }
    void restore(Map recovered) { state = recovered }
    void advance(long seconds) { clock += seconds * 1000L }
    int presses(int button) { events.count { it.name == 'pushed' && it.value == button } }
}
def compiler = new org.codehaus.groovy.control.CompilerConfiguration(scriptBaseClass: Harness.name)
def shell = new GroovyShell(this.class.classLoader, new Binding(), compiler)
def fresh = { Map prefs = [:] ->
    def d = shell.parse(new File(args ? args[0] : 'CareReminder.groovy'))
    d.settings.putAll(prefs); d.run(); d.installed(); d
}
int groups = 0
def check = { String label, Closure test -> test(); groups++; println "PASS ${label}" }
check('defaults and initial calendar') {
    def d = fresh(); assert d.device.values.careState == 'current'; assert d.device.values.switch == 'off'
    assert d.device.values.daysRemaining == 30G; assert d.device.values.usesRemaining == -1
    assert d.device.values.completedTasks == 0; assert d.jobs.refresh == d.now() + 3600000L
}
check('calendar soon, due and overdue boundaries') {
    def d = fresh([intervalDays:1,soonHours:1,overdueHours:1]); d.advance(23*3600); d.refresh()
    assert d.device.values.careState == 'soon'; assert d.presses(5) == 1
    d.advance(3600); d.refresh(); assert d.device.values.switch == 'on'; assert d.device.values.dueReason == 'time'
    assert d.presses(1) == 1; d.advance(3600); d.refresh(); assert d.presses(2) == 1
    assert d.device.values.careState == 'overdue'; d.refresh(); assert d.presses(2) == 1
}
check('usage threshold, positive amounts and time disabled') {
    def d = fresh([intervalDays:0,useLimit:3]); d.reportUse(); d.reportUse(2)
    assert d.device.values.careState == 'due'; assert d.device.values.dueReason == 'uses'
    assert d.device.values.usesRemaining == 0; assert d.device.values.daysRemaining == -1G
    d.reportUse(); assert d.presses(1) == 1; assert d.device.values.usesSinceDone == 4
}
check('earliest deadline wins') {
    def d = fresh([intervalDays:1,useLimit:2]); d.reportUse(2); assert d.device.values.dueReason == 'uses'
    d.advance(86400); d.refresh(); assert d.device.values.dueReason == 'uses'
    def e = fresh([intervalDays:1,useLimit:2]); e.advance(86401); e.reportUse(2)
    assert e.device.values.dueReason == 'time'
}
check('manual due is idempotent and standard off completes once') {
    def d = fresh(); d.on(); d.on(); assert d.device.values.dueReason == 'manual'; assert d.presses(1) == 1
    d.off(); d.off(); assert d.presses(4) == 1; assert d.device.values.completedTasks == 1
    assert d.device.values.switch == 'off'; assert d.device.values.daysRemaining == 30G
}
check('early completion resets usage and clock') {
    def d = fresh([useLimit:10]); d.reportUse(4); d.advance(86400); d.markDone()
    assert d.device.values.usesSinceDone == 0; assert d.device.values.daysRemaining == 30G
    assert d.device.values.completedTasks == 1; assert d.presses(4) == 1
}
check('snooze preserves outstanding switch and suppresses escalation') {
    def d = fresh([intervalDays:0,useLimit:1,overdueHours:1,repeatHours:0.5])
    d.reportUse(); d.snooze(2); assert d.device.values.careState == 'snoozed'; assert d.device.values.switch == 'on'
    d.advance(3600); d.refresh(); assert d.presses(2) == 0; assert d.presses(3) == 0
    d.advance(3600); d.refresh(); assert d.device.values.careState == 'overdue'
    assert d.presses(2) == 1; assert d.presses(6) == 1; d.refresh(); assert d.presses(6) == 1
}
check('repeat reminder cadence and delayed callback emits once') {
    def d = fresh([intervalDays:0,useLimit:1,repeatHours:1,overdueHours:24]); d.reportUse()
    d.advance(3599); d.refresh(); assert d.presses(3) == 0; d.advance(1); d.refresh(); assert d.presses(3) == 1
    d.advance(36000); d.refresh(); assert d.presses(3) == 2; d.refresh(); assert d.presses(3) == 2
}
check('cancel snooze and completion cancel old expiry') {
    def d = fresh(); d.on(); d.snooze(1); d.cancelSnooze(); assert d.device.values.careState == 'due'
    assert d.device.values.snoozedUntil == 'none'; assert d.presses(6) == 0
    d.snooze(1); d.markDone(); d.advance(3600); d.refresh()
    assert d.device.values.careState == 'current'; assert d.presses(6) == 0; assert d.device.values.switch == 'off'
}
check('invalid commands do not change task state') {
    def d = fresh(); def before = d.state.clone()
    [-1,0,1.5,'NaN','Infinity','abc',1000001].each { d.reportUse(it) }
    assert d.state == before; d.on(); before = d.state.clone()
    [-1,0,'NaN','Infinity','abc',8761].each { d.snooze(it) }; assert d.state == before
    assert d.warnings.size() == 13
}
check('invalid settings retain due switch and recover') {
    def d = fresh(); d.on(); d.settings.intervalDays=0; d.updated()
    assert d.device.values.status == 'configurationError'; assert d.device.values.switch == 'on'
    assert !d.jobs.refresh; d.markDone(); assert d.device.values.completedTasks == 0
    d.settings.useLimit=2; d.updated(); assert d.device.values.status == 'ready'; assert d.device.values.switch == 'on'
    d.off(); assert d.device.values.switch == 'off'
    d.settings.intervalDays='NaN'; d.updated(); assert d.device.values.status == 'configurationError'
}
check('preference changes retain completion date and usage') {
    def d = fresh([useLimit:10]); def original=d.state.lastDone; d.reportUse(5); d.settings.useLimit=4; d.updated()
    assert d.device.values.dueReason == 'uses'; assert d.state.lastDone == original
    d.settings.useLimit=6; d.updated(); assert d.device.values.switch == 'off'; assert d.device.values.usesSinceDone == 5
    d.reportUse(); assert d.presses(1) == 2
}
check('restart, JSON state and expired snooze catch-up') {
    def d = fresh([repeatHours:0]); d.on(); d.snooze(1); d.restore(new JsonSlurper().parseText(JsonOutput.toJson(d.state)))
    d.initialize(); assert d.device.values.careState == 'snoozed'; assert d.presses(1) == 1
    d.advance(3600); d.initialize(); assert d.presses(6) == 1; assert d.presses(1) == 1
    d.advance(36000); d.refresh(); assert d.presses(3) == 0
}
check('test buttons, debug shutoff and removal') {
    def d = fresh([logEnable:true]); assert d.jobs.logsOff
    [0,7,1.5,'NaN'].each { d.push(it) }; assert !d.events.any { it.name == 'pushed' }
    d.push(1); d.push(1); assert d.presses(1) == 2; assert d.device.values.switch == 'off'
    d.logsOff(); assert !d.settings.logEnable; d.uninstalled(); assert d.jobs.isEmpty()
}
check('exact next deadlines, tiny intervals and usage cap') {
    def d = fresh([intervalDays:0.0001,soonHours:0]); assert d.jobs.refresh == d.now()+9000L
    d.advance(9); d.refresh(); assert d.device.values.switch == 'on'
    d.settings.intervalDays=0.0000001; d.updated(); assert d.device.values.status == 'configurationError'
    def e = fresh([intervalDays:0,useLimit:1000000]); 1001.times { e.reportUse(1000000) }
    assert e.state.uses == 1000000000L; assert e.presses(1) == 1
}
println "${groups} behavior groups passed"
