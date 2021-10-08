//
//   Copyright 2018-2021  SenX S.A.S.
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.plugins.mqtt;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import io.warp10.script.WarpScriptLib;
import io.warp10.warp.sdk.AbstractWarp10Plugin;

public class MQTTWarp10Plugin extends AbstractWarp10Plugin implements Runnable {
  
  /**
   * Directory where spec files are located
   */
  private static final String CONF_MQTT_DIR = "mqtt.dir";
  
  /**
   * Period at which to scan the spec directory
   */
  private static final String CONF_MQTT_PERIOD = "mqtt.period";  

  /**
   * Default scanning period in ms
   */
  private static final long DEFAULT_PERIOD = 60000L;
  
  private String dir;
  private long period;

  /**
   * Map of spec file to MQTTConsumer instance
   */
  private Map<String,MQTTConsumer> consumers = new HashMap<String,MQTTConsumer>();
  
  private boolean done = false;
  
  public MQTTWarp10Plugin() {
    super();
    
    //
    // Register the MQTTACK function
    //
    
    MQTTWarpScriptExtension ext = new MQTTWarpScriptExtension();
    WarpScriptLib.register(ext);
  }
  
  @Override
  public void run() {
    while(true) {
      DirectoryStream<Path> pathes = null;
      
      try {
        
        if (done) {
          return;
        }
        
        pathes = Files.newDirectoryStream(new File(dir).toPath(), "*.mc2");
        
        Iterator<Path> iter = pathes.iterator();
        
        Set<String> specs = new HashSet<String>();
        
        while (iter.hasNext()) {
          Path p = iter.next();
          
          String filename = p.getFileName().toString();
          
          boolean load = false;
          
          if (this.consumers.containsKey(filename)) {
            if (this.consumers.get(filename).getWarpScript().length() != p.toFile().length()) {
              load = true;
            }
          } else {
            // This is a new spec
            load = true;
          }
          
          if (load) {
            load(filename);
          }
          specs.add(filename);
        }
        
        pathes.close();
        
        //
        // Clean the specs which disappeared
        //
        
        Set<String> removed = new HashSet<String>(this.consumers.keySet());
        removed.removeAll(specs);
        
        for (String spec: removed) {
          try {
            consumers.remove(spec).end();
          } catch (Exception e) {              
          }
        }
      } catch (Throwable t) {
        t.printStackTrace();
      } finally {
        if (null != pathes) {
          try {
            pathes.close();
          } catch (Exception e) {            
          }
        }
      }
      
      LockSupport.parkNanos(this.period * 1000000L);
    }
  }
  
  /**
   * Load a spec file
   * @param filename
   */
  private boolean load(String filename) {
    
    //
    // Stop the current MQTTConsumer if it exists
    //
    
    MQTTConsumer consumer = consumers.get(filename);
    
    if (null != consumer) {
      consumer.end();
    }
    
    try {
      consumer = new MQTTConsumer(new File(this.dir, filename).toPath());
    } catch (Exception e) {
      return false;
    }
    
    consumers.put(filename, consumer);
    
    return true;
  }
  
  @Override
  public void init(Properties properties) {
    this.dir = properties.getProperty(CONF_MQTT_DIR);
    
    if (null == this.dir) {
      throw new RuntimeException("Missing '" + CONF_MQTT_DIR + "' configuration.");
    }
    
    this.period = Long.parseLong(properties.getProperty(CONF_MQTT_PERIOD, Long.toString(DEFAULT_PERIOD)));
    
    //
    // Register shutdown hook
    //
    
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        done = true;
        System.out.println("MQTT Plugin shutting down all consumers.");
        this.interrupt();
        for (MQTTConsumer consumer: consumers.values()) {
          try {
            consumer.end();
          } catch (Exception e) {            
          }
        }
      }
    });
    
    Thread t = new Thread(this);
    t.setDaemon(true);
    t.setName("[Warp 10 MQTT Plugin " + this.dir + "]");
    t.start();
  }
}
