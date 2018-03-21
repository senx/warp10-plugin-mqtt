package io.warp10.plugins.mqtt;

import java.util.HashMap;
import java.util.Map;

import io.warp10.warp.sdk.WarpScriptExtension;

public class MQTTWarpScriptExtension extends WarpScriptExtension {
  
  private static Map<String,Object> functions;
  
  static {
    functions = new HashMap<String,Object>();
    
    functions.put("MQTTACK", new MQTTACK("MQTTACK"));
    functions.put("MQTTTOPIC", new MQTTTOPIC("MQTTTOPIC"));
    functions.put("MQTTPAYLOAD", new MQTTPAYLOAD("MQTTPAYLOAD"));
  }
  
  protected MQTTWarpScriptExtension() {
  }
  
  @Override
  public Map<String, Object> getFunctions() {
    return functions;
  }
}
