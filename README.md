# Plugin purpose 

The Warp 10™ MQTT Plugin allows you to subscribe to any MQTT topics and attach a WarpScript™ to process incoming MQTT messages.

# Compilation

```
git clone "https://github.com/senx/warp10-plugin-mqtt.git"
cd warp10-plugin-mqtt
./gradlew shadowJar
```

Copy the jar in your Warp 10™ `lib` directory
```
cp build/libs/warp10-plugin-mqtt-*.jar myWarp10path/lib/
```


# Configuration

Open your Warp 10™ configuration file: default path is `myWarp10path/etc/conf-standalone.conf`.

At the end of the file, add the following lines:

```
//
// Load the MQTT plugin
//
warp10.plugin.mqtt = io.warp10.plugins.mqtt.MQTTWarp10Plugin

//
// mqtt options: the home directory of WarpScript MQTT handlers
//
mqtt.dir = ${standalone.home}/mqtt

//
// mqtt options: scan changes in the directory every 10000ms. 
//
mqtt.period = 10000

```

You may also activate the embedded debug extension. This will allow you to easily debug your MQTT WarpScript™ by printing messages on the standard output.

```
//
// Load the debug extension. (usefull to have the STDOUT function)
//
warpscript.extension.debug=io.warp10.script.ext.debug.DebugWarpScriptExtension
```

Don't forget to create the mqtt directory: `mkdir myWarp10path/mqtt`.

Restart your Warp 10™ instance.


# Your first MQTT WarpScript

In your `mqtt` directory, create a file named `test.mc2`. File must end with the `.mc2` extension, as any WarpScript™.

The file will contain WarpScript™ code which must leave a MAP on the stack. This map contains everything to configure the MQTT plugin. You can have as many WarpScript™ files as you want in your `mqtt` directory. They will be dynamically reloaded every (10s + mqtt timeout).


Example for TheThingsNetwork mqtt:

```
// subscribe to the topics, attach a WarpScript™ macro callback to each message
// the macro reads TheThingNetwork message to extract the first byte of payload,
// the server timestamp, and the device id.

'Loading MQTT TTN Warpscript™' STDOUT
{
  'host' 'eu.thethings.network'
  'port' 1883
  'user' 'senx-sensors'
  'password' 'my application access key'
  'clientid' 'Warp10'
  'topics' [ 
    'senx-sensors/devices/senx-sensor1/up' 
    'senx-sensors/devices/senx-sensor2/up' 
    'senx-sensors/devices/senx-sensor3/up' 
  ]
  'timeout' 20000
  'parallelism' 1
  'autoack' true

  'macro' 
  <% 
    //in case of timeout, the macro is called to flush buffers, if any, with NULL on the stack.
    'message' STORE
    <% $message ISNULL ! %>
    <%
      $message MQTTPAYLOAD 'ascii' BYTES-> JSON-> 'TTNmessage' STORE
      $TTNmessage 'metadata' GET 'time' GET TOTIMESTAMP 'ts' STORE
      $TTNmessage 'dev_id' GET 'sensorID' STORE

      $message MQTTTOPIC ' ' +
        $sensorID + ' ' +
        $ts ISO8601 + ' ' +
        $TTNmessage TOSTRING +
      STDOUT // print to warp10.log 
    %> IFT
  %>
}
```

Monitor your warp10.log to read standard output: `tail -F myWarp10path/logs/warp10.log`.

# MAP detail

- `host`, `port`, `user`, `password`, are similar to any MQTT client e.g mosquitto:

mosquitto_sub -h `host` -p `port` -P `password` -u `user` -d -t 'senx-sensors/devices/senx-sensor1/up'

- `timeout`: if there is no message received after timeout, the macro is called with NULL on the stack. It may help you to manage missing data or raise alerts.
- `parallelism`: spawn multiple thread to handle heavy load.
- `autoack`: you may choose to manually ACK the messages at the end of the macro. 
- `macro`: each time a message is received, this macro will be called with a message object on top of the stack.


# New plugin functions

- MQTTPAYLOAD : Consumes a mqtt message on top of the stack, returns a byte array with the message content on top of the stack.
- MQTTTOPIC : Consumes a mqtt message on top of the stack, returns a string with the topic name of the message.
- MQTTACK : Consumes a mqtt message on top of the stack. In non autoack mode, ack the mqtt message.



