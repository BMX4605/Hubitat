/**
*  Ikea Tradfri Motion Sensor
*  manufacturer: "IKEA of Sweden", model: "TRADFRI motion sensor"
*
*  Copyright 2023 Ben Mohseni
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
*
*  Thanks to the following for the code used as a base for this:
*    Code written by Mikhail Diatchenko, Robert Morris, akafester and others
*
*
*   Change History:
*
*    Date        Who           What
*    ----        ---           ----
*    2023-02-26  Ben Mohseni   version 0.1.0
*                              -initial version to detect motion and an attempt to report on battery
*                              -review, contribute, correct, etc., but notify and share code)
*
*/

import hubitat.zigbee.clusters.iaszone.ZoneStatus
import com.hubitat.zigbee.DataType

metadata {
	definition (name: "Ikea Tradfri Motion Sensor", namespace: "bmx4605", author: "Ben Mohseni") {
		capability "MotionSensor"
		capability "Battery"
		capability "Configuration"
        capability "Refresh"
        capability "Sensor"
    
	}

	preferences {
		input name: "infoLogging", type: "bool", title: "Enable info message logging", defaultValue: true
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", defaultValue: true
	}
}


// Parse incoming device messages to generate events
def parse(String description) {
    Map map = [:]
    logDebug("[$description]")
    if (description?.startsWith('catchall:')) {
        // call parseCatchAllMessage to parse the catchall message received
        map = parseCatchAllMessage(description)
    } else if (description?.startsWith('read')) {
        // call parseReadMessage to parse the read message received
        map = parseReadMessage(description)
    } 
    
    if (map != [:]) {
		if (map.descriptionText != null) logInfo(map.descriptionText)
		return createEvent(map)
	} else
		return [:]
}

private Map parseCatchAllMessage(String description) {
  // Create a map from the message description to make parsing more intuitive
  Map map = [:]
  Map descMap = zigbee.parseDescriptionAsMap(description)
  switch(descMap.clusterId) {
    case "0001":
        if (descMap.command == "07") {
            // Process "Configure Reporting" response            
            if (descMap.data[0] == "00") {
                switch (descMap.clusterInt) {
                      case zigbee.POWER_CONFIGURATION_CLUSTER:
                          break
                      default:                    
                          break
                }
            } 
        } 
        break
    case "0006":
        if (descMap.command == "42") {
             // Motion detected
	         return handleMotion(true)
        }
        break
    default:
        break
  }
  map = descMap
  return map
}

private Map parseReadMessage(String description) {
  // Create a map from the message description to make parsing more intuitive
  Map map = [:]
  Map descMap = zigbee.parseDescriptionAsMap(description)
  if (descMap.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && descMap.attrInt == 0x0020) {
      map = parseBattery(descMap.value)
  }
  return map
}

// helpers -------------------
private handleMotion(motionActive) {    
    if (motionActive) {
        def timeout = 46
        // The sensor only sends a motion detected message so reset to motion inactive is performed in code
        runIn(timeout, resetToMotionInactive)        
        if (device.currentState('motion')?.value != "active") {
            state.motionStarted = now()
        }
    }
    
	return getMotionResult(motionActive)
}

def getMotionResult(motionActive) {
	def descriptionText = "Detected motion"
    if (!motionActive) {
		descriptionText = "Motion reset to inactive"
    }
	return [
			name			: 'motion',
			value			: motionActive ? 'active' : 'inactive',
			descriptionText : descriptionText
	]
}

def resetToMotionInactive() {
    def descText = "Reset motion"
	if (device.currentState('motion')?.value == "active") {
		descText = "Motion reset to inactive"
		sendEvent(
			name:'motion',
			value:'inactive',
			// isStateChange: true,
			descriptionText: descText
		)
		logInfo(descText)
	}
}

def getSecondsInactive() {
    if (state.motionStarted) {
        return Math.round((now() - state.motionStarted)/1000)
    } else {
        return 46
    }
}

// Convert 2-byte hex string to voltage
// 0x0020 BatteryVoltage -  The BatteryVoltage attribute is 8 bits in length and specifies the current actual (measured) battery voltage, in units of 100mV.
private parseBattery(valueHex) {
	logDebug("Battery parse string = ${valueHex}")
	def rawVolts = Integer.parseInt(valueHex, 16) / 10 // hexStrToSignedInt(valueHex)/10
	def minVolts = voltsmin ? voltsmin : 2.5
	def maxVolts = voltsmax ? voltsmax : 3.0
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	//logInfo(descText)
    sendEvent(name: "batteryLevelLastReceived", value: new Date())    
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		//isStateChange: true,
		descriptionText: descText
	]
	return result
}

// lifecycle methods -------------

// installed() runs just after a sensor is paired
def installed() {
	//logInfo("Installing...")    
    return configureReporting()
    //return refresh()
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	//logInfo("Configuring...")
    return configureReporting()
}

def refresh() {
	//logInfo("Refreshing...")
    return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) // battery voltage
}

// updated() runs every time user saves preferences
def updated() {
	//logInfo("Updating...")
    if (debugLogging) runIn(1800,logsOff)
    return configureReporting()
}


def logsOff(){
    //log.warn "debug logging disabled..."
    device.updateSetting("debugLogging",[value:"false",type:"bool"])

}

// helpers -------------

private def configureReporting() {
    def seconds = 43200
    
    return zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020, DataType.UINT8, seconds, seconds, 0x01)
        + zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20)
}

private def logDebug(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def logInfo(message) {
	if (infoLogging)
		log.info "${device.displayName}: ${message}"
}