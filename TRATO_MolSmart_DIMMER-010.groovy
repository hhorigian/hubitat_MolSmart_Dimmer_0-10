/**
 *  Hubitat - TCP MolSmart Dimmer 0-10V 8CH Driver by TRATO
 *
 *  Copyright 2025 VH
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *       1.0 2025 - Initial version based on MolSmart TRIAC 6CH driver, adapted for 0-10V 8CH module
 *
 *  Return string format (8 channels):
 *    000000000000000000000000:11111111:8:00000000:00000000
 *    [  3*8=24 dim digits    ]:[8 out]:[N]:[dMask8 ]:[iMask8]
 *
 *  Example CH1 at 35%:
 *    035000000000000000000000:11111111:8:10000000:00000000
 */

metadata {
    definition(name: "MolSmart - DIMMER 0-10V 8CH", namespace: "TRATO", author: "TRATO", vid: "generic-contact") {
        capability "Switch"
        capability "Configuration"
        capability "Initialize"
        capability "Refresh"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "PushableButton"
        capability "HoldableButton"
        attribute "numberOfButtons", "number"
        attribute "lastPushed", "number"
        attribute "lastHeld",   "number"
        attribute "boardstatus", "string"
    }
}

import groovy.json.JsonSlurper
import groovy.transform.Field

command "createchilds"
command "connectionCheck"
command "ManualKeepAlive"
command "verstatus"

@Field static final String DRIVER    = "by TRATO"
@Field static final String USER_GUIDE = "https://github.com/hhorigian/hubitat_MolSmart_Dimmer"
@Field static final int    NUM_CH    = 8   // fixed 8 channels

String fmtHelpInfo(String str) {
    String prefLink = "<a href='${USER_GUIDE}' target='_blank'>${str}<br><div style='font-size: 70%;'>${DRIVER}</div></a>"
    return "<div style='font-size: 160%; font-style: bold; padding: 2px 0px; text-align: center;'>${prefLink}</div>"
}

preferences {
    input "device_IP_address", "text",   title: "IP Address of MolSmart 0-10V Dimmer"
    input "device_port",       "number", title: "IP Port of Device", required: true, defaultValue: 502
    input name: "logInfo",  type: "bool", title: "Show Info Logs?",     required: false, defaultValue: true
    input name: "logWarn",  type: "bool", title: "Show Warning Logs?",  required: false, defaultValue: true
    input name: "logDebug", type: "bool", title: "Show Debug Logs?",    required: false, defaultValue: true
    input name: "logTrace", type: "bool", title: "Show Detailed Logs?", required: false, defaultValue: true
    input name: "hbInterval",   type: "number", title: "Keep-alive (seconds)",         defaultValue: 15, range: "5..120",   required: true
    input name: "idleTimeout",  type: "number", title: "Idle timeout (seconds)",        defaultValue: 60, range: "10..600",  required: true
    input name: "reconnectMin", type: "number", title: "Reconnect backoff MIN (s)",     defaultValue: 5,  range: "1..120",   required: true
    input name: "reconnectMax", type: "number", title: "Reconnect backoff MAX (s)",     defaultValue: 60, range: "5..600",   required: true
    input name: "hbPayload",    type: "enum",   title: "Keep-alive command",
          options: ["00","REFRESH"], defaultValue: "00",
          description: "00 for standard; REFRESH if device requires it."
    input name: "UserGuide", type: "hidden", title: fmtHelpInfo("Manual do Driver")
}

@Field static String  dimRxBuf   = ''
@Field static String  prevDimStr = null
@Field static Long    lastHbSentAt = 0L
@Field static java.util.Random _rng = new java.util.Random()

private int  clampInt(v, lo, hi) { Math.max(lo as int, Math.min(hi as int, (v ?: 0) as int)) }
private void markRxNow()         { state.lastMessageReceivedAt = now() }
private Long msSinceRx()         { def t = (state.lastMessageReceivedAt ?: 0L) as Long; (t > 0L) ? (now() - t) : Long.MAX_VALUE }

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    logTrace('installed()')
    state.childscreated = 0
    runIn(1800, logsOff)
}

def uninstalled() {
    logTrace('uninstalled()')
    unschedule()
    interfaces.rawSocket.close()
}

def updated() {
    logTrace('updated()')
    refresh()
}

def initialize() {
    unschedule()
    logTrace('initialize()')
    disconnectSocket()
    if (!device_IP_address) { logError 'IP Address not configured'; return }
    if (!device_port)       { logError 'Port not configured'; return }
    state.inputcount = NUM_CH
    state.ipaddress  = device_IP_address
    try { createchilds() } catch (e) { logError("createchilds error: ${e}") }
    connectSocket()
    runIn(2, "refresh", [overwrite: true])
}

def ManualKeepAlive() {
    logTrace('ManualKeepAlive()')
    unschedule()
    disconnectSocket()
    connectSocket()
}

// ── Child devices ─────────────────────────────────────────────────────────────

def createchilds() {
    String thisId = device.id
    state.netids = "${thisId}-Switch-"
    if (state.childscreated == 0) {
        for (int i = 1; i <= NUM_CH; i++) {
            def dni = "${thisId}-Switch-${i}"
            if (!getChildDevice(dni)) {
                addChildDevice("hubitat", "Generic Component Dimmer", dni,
                    [name: "${device.displayName} CH-${i}", isComponent: true])
                logInfo("Created child dimmer CH-${i}")
            }
        }
        state.childscreated = 1
    } else {
        logInfo("Child dimmers already created")
    }
}

def verstatus() {
    for (int i = 1; i <= NUM_CH; i++) {
        def cd = getChildDevice("${state.netids}${i}")
        log.info "CH${i} level = ${cd?.currentValue('level')}"
    }
}

// ── Refresh / Heartbeat ───────────────────────────────────────────────────────

def refresh() {
    logInfo('Refresh()')
    sendCommand(settings?.hbPayload ?: "00")
}

// ── Parse ─────────────────────────────────────────────────────────────────────
//
//  Format: DDDDDDDDDDDDDDDDDDDDDDDD:OOOOOOOO:8:MMMMMMMM:IIIIIIII
//            24 dim digits           8 out    N  dMask    iMask
//
def parse(String msg) {
    state.lastMessageReceived   = new Date(now()).toString()
    state.lastMessageReceivedAt = now()

    // HEX → ASCII if needed
    String s = msg
    if (s ==~ /(?i)^[0-9A-F]+$/ && (s.length() % 2 == 0)) {
        try { s = new String(hubitat.helper.HexUtils.hexStringToByteArray(s)) } catch (ignored) {}
    }

    s = s.replaceAll(/[\r\n]+/, ' ')
    dimRxBuf += s
    if ((Boolean)settings.logDebug != false) log.debug "RX chunk: ${s.trim()} (buf=${dimRxBuf.length()})"

    while (true) {
        while (dimRxBuf.startsWith(' ')) { dimRxBuf = dimRxBuf.substring(1) }
        if (dimRxBuf.length() == 0) break

        int sp = dimRxBuf.indexOf(' ')
        String token = (sp >= 0) ? dimRxBuf.substring(0, sp) : dimRxBuf

        // need exactly 4 colons (5 fields)
        if (token.split(':').length - 1 < 4) {
            if (sp < 0) break
            dimRxBuf = dimRxBuf.substring(sp + 1)
            continue
        }

        String[] parts = token.split(':')
        if (parts.length < 5) {
            if (sp < 0) break
            dimRxBuf = (sp >= 0) ? dimRxBuf.substring(sp + 1) : ''
            continue
        }

        String dStr  = parts[0].trim()   // 24 dim digits
        String oBits = parts[1].trim()   // 8 output on/off bits
        Integer ch   = null
        try { ch = parts[2].trim().toInteger() } catch (ignored) {}
        String dMask = parts[3].trim()   // output change mask
        String iMask = parts[4].trim()   // input mask

        // infer channel count from dStr if not given
        if (ch == null || ch <= 0) {
            if (dStr != null && dStr.length() % 3 == 0) ch = (int)(dStr.length() / 3)
        }

        // validate lengths
        boolean valid = ch != null && ch > 0 &&
                        dStr  != null && dStr.length()  == 3 * ch &&
                        oBits != null && oBits.length() == ch &&
                        dMask != null && dMask.length() == ch &&
                        iMask != null && iMask.length() == ch

        if (!valid) {
            if (sp < 0) break
            if ((Boolean)settings.logDebug != false) log.debug "Skip frame (invalid) ch=${ch} token=${token}"
            dimRxBuf = (sp >= 0) ? dimRxBuf.substring(sp + 1) : ''
            continue
        }

        // consume token
        dimRxBuf = (sp >= 0) ? dimRxBuf.substring(sp + 1) : ''

        // apply levels per channel where changed
        for (int i = 0; i < ch; i++) {
            int start = i * 3
            int lvl = 0
            try { lvl = Integer.parseInt(dStr.substring(start, start + 3)) } catch (ignored) {}
            lvl = Math.max(0, Math.min(100, lvl))

            boolean changed = false
            if (prevDimStr == null)                          changed = true
            else if (prevDimStr.length() != dStr.length())  changed = true
            else {
                char dm = dMask.charAt(i)
                if (dm == '1' || dm == 'H')  changed = true
                else if (!dStr.substring(start, start + 3).equals(prevDimStr.substring(start, start + 3))) changed = true
            }

            if (changed) {
                def cd = getChildDevice("${state.netids}${i + 1}")
                if (cd) {
                    cd.parse([[name: "switch", value: (lvl > 0) ? "on" : "off", isStateChange: true]])
                    cd.parse([[name: "level",  value: lvl]])
                }
            }
        }
        prevDimStr = dStr

        // input button feedback (8 pushbutton inputs = iMask block)
        try { handleInputButtons(iMask, iMask, ch) } catch (ignored) {}

        sendEvent(name: "lastmessage", value: token)
    }
}

// ── Input button handling ─────────────────────────────────────────────────────

private void handleInputButtons(String iBits, String iMask, int chan) {
    if (!iBits || chan <= 0) return
    if ((device.currentValue("numberOfButtons") as Integer) != chan) {
        sendEvent(name: "numberOfButtons", value: chan)
    }
    String prev = state.prevInputBits ?: ("1" * chan)
    if (prev.length() != iBits.length()) prev = "1" * iBits.length()

    int debounceMs = 120
    long nowMs = now()
    state.btnLastMs     = state.btnLastMs     ?: [:]
    state.btnLastHeldMs = state.btnLastHeldMs ?: [:]

    for (int i = 0; i < chan; i++) {
        char before = (i < prev.length()) ? prev.charAt(i) : '1'
        char after  = iBits.charAt(i)
        boolean holdSeen   = (after == 'H') || (iMask && i < iMask.length() && iMask.charAt(i) == 'H')
        boolean wasPressed = (before == '0' || before == 'H')
        boolean nowPressed = (after  == '0' || after  == 'H')
        boolean pushEdge   = (!wasPressed && nowPressed)
        int btn = i + 1

        if (holdSeen) {
            long lastHeld = (state.btnLastHeldMs["${btn}"] ?: 0L) as Long
            if ((nowMs - lastHeld) >= debounceMs) {
                sendEvent(name: "held",     value: btn, isStateChange: true, type: "physical")
                sendEvent(name: "lastHeld", value: btn)
                state.btnLastHeldMs["${btn}"] = nowMs
            }
        } else if (pushEdge) {
            long lastMs = (state.btnLastMs["${btn}"] ?: 0L) as Long
            if ((nowMs - lastMs) >= debounceMs) {
                sendEvent(name: "pushed",     value: btn, isStateChange: true, type: "physical")
                sendEvent(name: "lastPushed", value: btn)
                state.btnLastMs["${btn}"] = nowMs
            }
        }
    }
    state.prevInputBits = iBits
}

// ── Master on/off ─────────────────────────────────────────────────────────────

def on()  { logDebug("Master ON()");  masteron()  }
def off() { logDebug("Master OFF()"); masteroff() }

def masteron() {
    log.info "MasterON()"
    for (int i = 1; i <= NUM_CH; i++) {
        def cd = getChildDevice("${state.netids}${i}")
        if (cd) { on(cd); pauseExecution(300) }
    }
}

def masteroff() {
    log.info "MasterOFF()"
    for (int i = 1; i <= NUM_CH; i++) {
        def cd = getChildDevice("${state.netids}${i}")
        if (cd) { off(cd); pauseExecution(300) }
    }
}

// ── Component callbacks ───────────────────────────────────────────────────────

void componentRefresh(cd)              { refresh() }
void componentOn(cd)  {
    getChildDevice(cd.deviceNetworkId).parse([[name:"switch", value:"on"]])
    on(cd); pauseExecution(200)
}
void componentOff(cd) {
    getChildDevice(cd.deviceNetworkId).parse([[name:"switch", value:"off"]])
    off(cd); pauseExecution(200)
}
void componentSetLevel(cd, level) {
    def lvl = Math.max(Math.min(level as Integer, 100), 0)
    getChildDevice(cd.deviceNetworkId).parse([[name:"switch", value: (lvl > 0) ? "on" : "off"]])
    getChildDevice(cd.deviceNetworkId).parse([[name:"level",  value: lvl]])
    SetLevel(cd, lvl)
}

// ── Low-level commands ────────────────────────────────────────────────────────

private int relayFromDni(cd) {
    def dni = cd.deviceNetworkId
    int sub1 = dni.indexOf("-", dni.indexOf("-") + 1)
    def tail = dni.substring(sub1 + 1)
    return tail as Integer
}

void on(cd) {
    int relay = relayFromDni(cd)
    sendCommand("1${relay}")
    logDebug("ON CH${relay}")
}

void off(cd) {
    int relay = relayFromDni(cd)
    sendCommand("2${relay}")
    logDebug("OFF CH${relay}")
}

void SetLevel(cd, level) {
    int relay = relayFromDni(cd)
    def lvl = String.format("%03d", Math.max(Math.min(level as Integer, 100), 0))
    sendCommand("1${relay}%${lvl}")
    logDebug("LEVEL CH${relay} -> ${lvl}")
}

private sendCommand(String s) {
    logDebug("sendCommand: ${s}")
    interfaces.rawSocket.sendMessage(s)
}

// ── Connection management ─────────────────────────────────────────────────────

private void connectSocket() {
    String ip = device_IP_address
    int port  = device_port as int
    try {
        interfaces.rawSocket.connect(ip, port)
        state.socketOnline    = true
        state.reconnectAttempt = 0
        markRxNow()
        setBoardOnline()
        scheduleHeartbeat()
        scheduleWatchdog()
        runIn(1, "heartbeat", [overwrite: true])
        logTrace("Socket connected to ${ip}:${port}")
    } catch (e) {
        logError("Connect failed ${ip}:${port}: ${e}")
        setBoardOffline()
        scheduleReconnect("connect error")
    }
}

private void disconnectSocket() {
    try { interfaces.rawSocket.close() } catch (ignored) {}
    state.socketOnline = false
}

private void setBoardOnline() {
    sendEvent(name: "boardstatus", value: "online",  isStateChange: true)
}
private void setBoardOffline() {
    sendEvent(name: "boardstatus", value: "offline", isStateChange: true)
}

private void scheduleHeartbeat() {
    runIn(clampInt(settings?.hbInterval, 5, 120), "heartbeat", [overwrite: true])
}

def heartbeat() {
    def hb = settings?.hbPayload ?: "00"
    try {
        interfaces.rawSocket.sendMessage(hb)
        lastHbSentAt = now()
        if ((Boolean)settings.logDebug != false) log.debug "HB -> '${hb}'"
    } catch (e) {
        logWarn("HB send fail: ${e}")
        scheduleReconnect("send fail")
    }
    scheduleHeartbeat()
}

private void scheduleWatchdog() { runIn(5, "connectionCheck", [overwrite: true]) }

def connectionCheck() {
    int hb      = clampInt(settings?.hbInterval, 5, 120)
    int userIdle = clampInt(settings?.idleTimeout, 10, 600)
    int effIdle  = Math.max(userIdle, hb * 3)
    long idle   = msSinceRx()
    if (idle >= effIdle * 1000L) {
        logError("No RX for ${String.format('%.1f', idle / 1000.0)}s (limit=${effIdle}s) - reconnecting")
        setBoardOffline()
        scheduleReconnect("idle timeout")
        return
    }
    setBoardOnline()
    scheduleWatchdog()
}

private void scheduleReconnect(String reason) {
    if (state.reconnecting == true) return
    state.reconnecting = true
    disconnectSocket()
    int attempt = ((state.reconnectAttempt ?: 0) as int) + 1
    state.reconnectAttempt = attempt
    int minS  = clampInt(settings?.reconnectMin, 1, 120)
    int maxS  = clampInt(settings?.reconnectMax, 5, 600)
    int delay = Math.min(maxS, (int)Math.pow(2D, Math.min(6, attempt - 1)) * minS) + _rng.nextInt(1)
    logWarn("Reconnect #${attempt} in ~${delay}s (${reason})")
    runIn(delay, "doReconnect", [overwrite: true])
}

def doReconnect() { state.reconnecting = false; connectSocket() }

def socketStatus(String message) {
    logWarn("socketStatus: ${message}")
    if (!(message?.toLowerCase()?.contains("normal") ?: false)) {
        scheduleReconnect("socketStatus")
    }
}

// ── Logging ───────────────────────────────────────────────────────────────────

def logsOff() {
    log.warn 'logging disabled'
    device.updateSetting('logInfo',  [value: 'false', type: 'bool'])
    device.updateSetting('logWarn',  [value: 'false', type: 'bool'])
    device.updateSetting('logDebug', [value: 'false', type: 'bool'])
    device.updateSetting('logTrace', [value: 'false', type: 'bool'])
}

void logDebug(String msg) { if ((Boolean)settings.logDebug != false) log.debug "MolSmart-0-10V: ${msg}" }
void logInfo(String msg)  { if ((Boolean)settings.logInfo  != false) log.info  "MolSmart-0-10V: ${msg}" }
void logTrace(String msg) { if ((Boolean)settings.logTrace != false) log.trace "MolSmart-0-10V: ${msg}" }
void logWarn(String msg, boolean force = false) { if (force || (Boolean)settings.logWarn != false) log.warn "MolSmart-0-10V: ${msg}" }
void logError(String msg) { log.error "MolSmart-0-10V: ${msg}" }
