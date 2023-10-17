/**
*  Ikea Styrbar Remote Control
*  manufacturer: "IKEA of Sweden", model: "Remote Control N2"
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
*                                      Button Designations are as follows:
*
*                                                    (1)
*                                                   /   \
*                                                (2)     (4)
*                                                   \   /
*                                                    (3)
*
*
*
*  Thanks to the following for the code used as a base for this:
*    Code written by motley74 and @sticks18 by AnotherUser.
*    Original source: https://github.com/motley74/SmartThingsPublic/blob/master/devicetypes/motley74/osram-lightify-dimming-switch.src/osram-lightify-dimming-switch.groovy
*
*    Code written by AnotherUser
*    https://github.com/AnotherUser17/SmartThingsPublic-1/blob/master/devicetypes/AnotherUser/osram-lightify-4x-switch.src/osram-lightify-4x-switch.groovy
*
*    Code written by @akafester
*    https://github.com/akafester/Hubitat/blob/main/Drivers/user_driver_Hubitat_IKEA_Styrbar_898.groovy
*
*
*   Change History:
*
*    Date        Who           What
*    ----        ---           ----
*    2021-03-11  akafester     version 0.1.0 Initial release
*    2023-02-24  Ben Mohseni   version 0.1.1 updated code from previous mentioned authors
*                              - correctly realize button actions when pushed, held, or released
*                              - some of the features that needs to be researched and added:
*                                + Additional cleanup needed
*                                + Battery % full
*                                + possibly writing an app to traverse through lights/switches/dimmers 
*                                  that one may want to turn off/on/control using the side
*                                  buttons (2, 4), or control one color light/switch/dimmer
*                                  to control the color change using those buttons
*                                  (feel free to review, contribute, correct, etc., but notify and share code)
*    2023-10-17  Ben Mohseni   version 0.1.2
*                              - further correct the behavior of buttons 2 or 4 held/released.
*
*/

metadata {
	definition (name: "Ikea Styrbar", namespace: "bmx4605", author: "Ben Mohseni") {
    capability "Actuator"
    capability "Battery"
    capability "PushableButton"
    capability "ReleasableButton"
    capability "HoldableButton"
    capability "Configuration"
    capability "Refresh"
    
    capability "SignalStrength"    //lqi - NUMBER; rssi - NUMBER
 
    attribute "batteryVoltage", "string"
    
    attribute "battery","Number"
    attribute "numberOfButtons","Number"
    attribute "pushed", "Number"
    attribute "released", "Number"
    attribute "held", "Number"

    command "configure", [[name: "Initialize the device after switching drivers.  \n\r     ***** Will load device default values! *****" ]]
       
    
    }
    preferences {
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }

    
}


def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def updated(){
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(1800,logsOff)
}

def installed() {
    configure()
    atomicState.releaseButton = 0
    atomicState.releasecalled = 0
    updated()
}

def configure() {
    sendEvent(name:"numberOfButtons", value: 4, isStateChange: true)
    refresh()
}

def refresh() {
    //when refresh button is pushed, read updated status
    List<String> cmds = []
        cmds += zigbee.readAttribute(0x0001, 0x0020, [:], delay=200)     // TODO: check - battery voltage
        cmds += zigbee.readAttribute(0xFCC0, 0x0102, [mfgCode: 0x115F], delay=200)
    if (logEnable) log.debug "$cmds"
    sendZigbeeCommands( cmds ) 
    atomicState.releaseButton = 0
    atomicState.releasecalled = 0
    return cmds
}

// Parse incoming device messages to generate events
def parse(String description) {
    Map map = [:]
    //if (atomicState.releasecalled == null) atomicState.releasecalled = 0
    //if (logEnable) log.debug "====================================================="
    if (logEnable) log.debug "Parse description $description"
    if (description?.startsWith('catchall:')) {
        // call parseCatchAllMessage to parse the catchall message received
        //if (logEnable) log.debug "Going into Parse CatchAll"
        map = parseCatchAllMessage(description)
    } else if (description?.startsWith('read')) {
        // call parseReadMessage to parse the read message received
        map = parseReadMessage(description)
    } else {
        if (logEnable) log.debug "Unknown message received: $description"
    }
    //return event unless map is not set
    //if (logEnable) log.debug "Going out of parse"
    return map ? createEvent(map) : null
}

private Map parseCatchAllMessage(String description) {
  // Create a map from the raw zigbee message to make parsing more intuitive
  def msg = zigbee.parse(description)
  if (logEnable) log.debug msg
  if (logEnable) log.debug "clusterId= "+msg.clusterId+", Msg.command= "+msg.command+", atomicState.releasecalled= "+atomicState.releasecalled
  switch(msg.clusterId) {
    case 1:
      // call getBatteryResult method to parse battery message into event map
       if (logEnable) log.debug 'BATTERY MESSAGE'
      def result = getBatteryResult(Integer.parseInt(msg.value, 16))
      break
    case 5:
      //if (logEnable) log.debug "Side buttons 2 or 4 pressed/Held/Released"
      switch(msg.command) {
        case 7: // Side buttons 2 or 4 pressed
          //if (logEnable) log.debug "Side buttons 2 or 4 pressed"
            def button = 0
            if (msg.data == [0x01,0x01,0x0D,0x00])
              button = 2
            else if (msg.data == [0x00,0x01,0x0D,0x00])
              button = 4
            else 
              button = 0
            //if (logEnable) log.debug button
            Map result = [:]
            if (button > 0) {
              result = [
                name: 'button',
                value: 'pushed',
                data: [buttonNumber: button],
                descriptionText: "$device.displayName button $button was pushed",
                isStateChange: true
              ]
              push(button)
              if (txtEnable) log.debug "Parse returned ${result?.descriptionText}"
              return result
            }
          break
        case 8: // Side buttons 2 or 4 held
          //if (logEnable) log.debug "Side buttons 2 or 4 held"
            def button = 0
            if (msg.data == [0x01,0x0D,0x00])
              button = 2
            else if (msg.data == [0x00,0x0D,0x00])
              button = 4
            else 
              button = 0
            //if (logEnable) log.debug button
            Map result = [:]
            result = [
              name: 'button',
              value: 'held',
              data: [buttonNumber: button],
              descriptionText: "$device.displayName button $button was held",
              isStateChange: true
            ]
            hold(button)
            if (txtEnable) log.debug "Parse returned ${result?.descriptionText}"
            return result
          break
        case 9: // Side buttons 2 or 4 released
          //if (logEnable) log.debug "Side buttons 2 or 4 released"
          def button = (msg.data == [0x01,0x01,0x0D,0x00] ? 2 : 4)
          Map result = [:]
          result = [
            name: 'button',
            value: 'released',
            data: [buttonNumber: button],
            descriptionText: "$device.displayName button $button was released",
            isStateChange: true
          ]
          release(button)
          if (txtEnable) log.debug "Parse returned ${result?.descriptionText}"
          if (atomicState.releasecalled < 1)
            atomicState.releasecalled = 1
          else
            atomicState.releasecalled = 0  
          return result
          break
      }
      break
    case 6:
      //button 1 or 3 was pressed
      //if (logEnable) log.debug "buttons 1 or 3 pressed"
      def button = (msg.command == 1 ? 1 : 3)
      Map result = [:]
      result = [
        name: 'button',
        value: 'pushed',
        data: [buttonNumber: button],
        descriptionText: "$device.displayName button $button was pushed",
        isStateChange: true
      ]
      if (txtEnable) log.debug "Parse returned ${result?.descriptionText}"
      if (atomicState.releasecalled < 1) push(button)
      return result
      break
    case 8:
      //button 1 or 3 was held or released
      switch(msg.command) {
        case 1: 
          //button 3 held
          def button = 3
          atomicState.releaseButton = 3
          Map result = [:]
          result = [
            name: 'button',
            value: 'held',
            data: [buttonNumber: button],
            descriptionText: "$device.displayName button $button was held",
            isStateChange: true
          ]
          hold(button)
          if (txtEnable) log.debug "Parse returned ${result?.descriptionText}"
          return result
          break
        case 3: /* brightness change stop command
          def result = [
            name: 'button',
            value: 'released',
            data: [buttonNumber: [1,2]],
            descriptionText: "$device.displayName button was released",
            isStateChange: true
          ]*/
          if (logEnable) log.debug "not currently implemented!"
          //return result
          break
        case 5: 
          //button 1 held
          def button = 1
          atomicState.releaseButton = 1
          Map result = [:]
          result = [
            name: 'button',
            value: 'held',
            data: [buttonNumber: button],
            descriptionText: "$device.displayName button $button was held",
            isStateChange: true
          ]
          hold(button)
          if (txtEnable) log.debug "Parse returned ${result?.descriptionText}"
          return result
          break
        case 7:// Side buttons released 1 or 3
          def button = atomicState.releaseButton
          Map result = [:]
          result = [
            name: 'button',
            value: 'released',
            data: [buttonNumber: button],
            descriptionText: "$device.displayName button $button was released",
            isStateChange: true
          ]
          release(button)
          if (txtEnable) log.debug "Parse returned ${result?.descriptionText}"
          return result
          break
      }
      break
  }
}

private Map parseReadMessage(String description) {
  // Create a map from the message description to make parsing more intuitive
  //def msg = zigbee.parseDescriptionAsMap(description)
  def msg = zigbee.parse(description)
  if (logEnable) log.debug msg
    
  if (msg.clusterInt==1 && msg.attrInt==32) {
    // call getBatteryResult method to parse battery message into event map
    def result = getBatteryResult(Integer.parseInt(msg.value, 16))
  } else {
    if (logEnable) log.debug "Unknown read message received, parsed message: $msg"
  }
  // return map used to create event
  return result
}

def push(button) {
    sendEvent(name:"pushed", value:button, isStateChange: true)
}

def hold(button) {
    sendEvent(name:"held", value:button, isStateChange: true)
}

def release(button) {
    sendEvent(name:"released", value:button, isStateChange: true)
}

//obtained from other examples, converts battery message into event map
private Map getBatteryResult(rawValue) {
  def linkText = getLinkText(device)
  def result = [
    name: 'battery',
    value: '--'
  ]
  def volts = rawValue / 10
  def descriptionText
  if (rawValue == 0) {
  } else {
    if (volts > 3.5) {
      result.descriptionText = "${linkText} battery has too much power (${volts} volts)."
    } else if (volts > 0){
      def minVolts = 2.1
      def maxVolts = 3.0
      def pct = (volts - minVolts) / (maxVolts - minVolts)
      result.value = Math.min(100, (int) pct * 100)
      result.descriptionText = "${linkText} battery was ${result.value}%"
    }
  }
  if (txtEnable) log.debug "Parse returned ${result?.descriptionText}"
  return result
}