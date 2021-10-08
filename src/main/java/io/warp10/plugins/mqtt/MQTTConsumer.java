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

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.fusesource.mqtt.client.BlockingConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.Message;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.warp10.script.MemoryWarpScriptStack;
import io.warp10.script.WarpScriptStack.Macro;
import io.warp10.script.WarpScriptStopException;

public class MQTTConsumer extends Thread {

  private static final Logger LOG = LoggerFactory.getLogger(MQTTConsumer.class);

  private static final int DEFAULT_QSIZE = 1024;
  
  private static final String PARAM_MACRO = "macro";
  private static final String PARAM_PARALLELISM = "parallelism";
  private static final String PARAM_PARTITIONER = "partitioner";
  private static final String PARAM_QSIZE = "qsize";
  private static final String PARAM_MQTT_HOST = "host";
  private static final String PARAM_MQTT_USER = "user";
  private static final String PARAM_MQTT_PASSWORD = "password";
  private static final String PARAM_MQTT_PORT = "port";
  private static final String PARAM_MQTT_CLIENTID = "clientid";
  private static final String PARAM_MQTT_QOS = "qos";
  private static final String PARAM_MQTT_TIMEOUT = "timeout";
  private static final String PARAM_MQTT_AUTOACK = "autoack";
  
  private static final String PARAM_MQTT_TOPICS = "topics";
  private static final String PARAM_MQTT_CLEANSESSION = "cleansession";
  
  private static final QoS DEFAULT_QOS = QoS.AT_LEAST_ONCE;
  
  private final MemoryWarpScriptStack stack;
  private final Macro macro;
  private final Macro partitioner;
  private final String mqttuser;
  private final String mqttpassword;
  private final String mqtthost;
  private final String mqttclientid;
  private final boolean mqttautoack;
  private final QoS mqttqos;
  private long timeout = 0;
  private final boolean mqttcleansession;
  
  private final int parallelism;
  private final int mqttport;
  private final Topic[] topics;
  
  
  private BlockingConnection connection;

  private boolean done;
  
  private final String warpscript;
  
  private final LinkedBlockingQueue<Message> queue;
  private final LinkedBlockingQueue<Message>[] queues;

  private Thread[] executors;
  
  public MQTTConsumer(Path p) throws Exception {
    //
    // Read content of mc2 file
    //
    
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    InputStream in = new FileInputStream(p.toFile());
    byte[] buf = new byte[8192];
    
    try {
      while(true) {
        int len = in.read(buf);
        if (len < 0) {
          break;
        }
        baos.write(buf, 0, len);
      }      
    } finally {
      in.close();
    }
    
    this.warpscript = new String(baos.toByteArray(), Charsets.UTF_8);
    this.stack = new MemoryWarpScriptStack(null, null, new Properties());
    stack.maxLimits();
    
    try {
      stack.execMulti(this.warpscript);
    } catch (Throwable t) {
      t.printStackTrace();
      LOG.error("Caught exception while loading '" + p.getFileName() + "'.", t);
    }

    Object top = stack.pop();
    
    if (!(top instanceof Map)) {
      throw new RuntimeException("MQTT consumer spec must leave a configuration map on top of the stack.");
    }
    
    Map<Object,Object> config = (Map<Object,Object>) top;
    
    //
    // Extract parameters
    //
    
    this.macro = (Macro) config.get(PARAM_MACRO);
    this.partitioner = (Macro) config.get(PARAM_PARTITIONER);
    this.mqttuser = null != config.get(PARAM_MQTT_USER) ? String.valueOf(config.get(PARAM_MQTT_USER)) : null;
    this.mqttpassword = null != config.get(PARAM_MQTT_PASSWORD) ? String.valueOf(config.get(PARAM_MQTT_PASSWORD)) : null;
    this.mqtthost = String.valueOf(config.get(PARAM_MQTT_HOST));
    this.mqttport = ((Number) config.get(PARAM_MQTT_PORT)).intValue();
    this.mqttclientid = String.valueOf(config.get(PARAM_MQTT_CLIENTID));
    this.parallelism = Integer.parseInt(null != config.get(PARAM_PARALLELISM) ? String.valueOf(config.get(PARAM_PARALLELISM)) : "1");
    this.mqttqos = QoS.valueOf(null != config.get(PARAM_MQTT_QOS) ? String.valueOf(config.get(PARAM_MQTT_QOS)) : DEFAULT_QOS.toString());
    this.mqttautoack = Boolean.TRUE.equals(config.get(PARAM_MQTT_AUTOACK));
    if (config.containsKey(PARAM_MQTT_CLEANSESSION)) {
      this.mqttcleansession = Boolean.TRUE.equals(config.get(PARAM_MQTT_CLEANSESSION));
    } else {
      this.mqttcleansession = true;
    }
    
    if (config.containsKey(PARAM_MQTT_TIMEOUT)) {
      this.timeout = Long.parseLong(String.valueOf(config.get(PARAM_MQTT_TIMEOUT)));
    }
      
    int qsize = DEFAULT_QSIZE;
    
    if (null != config.get(PARAM_QSIZE)) {
      qsize = Integer.parseInt(String.valueOf(config.get(PARAM_QSIZE)));
    }
    
    if (null == this.partitioner) {
      this.queue = new LinkedBlockingQueue<Message>(qsize);
      this.queues = null;
    } else {
      this.queue = null;
      this.queues = new LinkedBlockingQueue[this.parallelism];
      for (int i = 0; i < this.parallelism; i++) {
        this.queues[i] = new LinkedBlockingQueue<Message>(qsize);
      }
    }
    
    Object t = config.get(PARAM_MQTT_TOPICS);

    if (null == t || !(t instanceof List)) {
      throw new RuntimeException("Invalid topic list.");
    }
    
    this.topics = new Topic[((List) t).size()];
    
    for (int i = 0; i < topics.length; i++) {
      this.topics[i] = new Topic(String.valueOf(((List) t).get(i)), this.mqttqos);
    }

    //
    // Create MQTT client
    //
    MQTT mqtt = new MQTT();

    mqtt.setCleanSession(this.mqttcleansession);
    
    mqtt.setHost(this.mqtthost, this.mqttport);
    if (null != this.mqttclientid) {
      mqtt.setClientId(this.mqttclientid);
    }
    if (null != this.mqttuser) {
      mqtt.setUserName(this.mqttuser);
    }
    if (null != this.mqttpassword) {
      mqtt.setPassword(this.mqttpassword);
    }
    
    this.connection = mqtt.blockingConnection();
    this.connection.connect();
    
    this.connection.subscribe(topics);

    this.setDaemon(true);
    this.setName("[MQTT Client " + this.mqttclientid + "]");
    this.start();
  }
  
  @Override
  public void run() {
    
    this.executors = new Thread[this.parallelism];
    
    for (int i = 0; i < this.parallelism; i++) {
      
      final MemoryWarpScriptStack stack = new MemoryWarpScriptStack(MQTTWarp10Plugin.getExposedStoreClient(), MQTTWarp10Plugin.getExposedDirectoryClient(), new Properties());
      stack.maxLimits();
      
      final LinkedBlockingQueue<Message> queue = null == this.partitioner ? this.queue : this.queues[i];
      
      executors[i] = new Thread() {
        @Override
        public void run() {
          while(true) {
            
            try {
              Message msg = null;
              
              if (timeout > 0) {
                msg = queue.poll(timeout, TimeUnit.MILLISECONDS);
              } else {
                msg = queue.take();
              }

              stack.clear();
              
              if (null != msg) {
                stack.push(msg);
              } else {
                stack.push(null);
              }
              
              stack.exec(macro);
              
              //
              // Acknowledge the message if autoack is true
              //
              if (mqttautoack && null != msg) {
                msg.ack();
              }
            } catch (InterruptedException e) {
              return;
            } catch (WarpScriptStopException wsse) {
            } catch (Exception e) {
              e.printStackTrace();
            }            
          }
        }
      };
      
      executors[i].setContextClassLoader(this.getContextClassLoader());
      executors[i].setName("[MQTT Executor #" + i + "]");
      executors[i].setDaemon(true);
      executors[i].start();
    }
    
    while(!done) {
      try {
        Message msg = null;
        
        msg = this.connection.receive();

        try {
          // Apply the partitioning macro if it is defined
          if (null != this.partitioner) {
            this.stack.clear();
            this.stack.push(msg);
            this.stack.exec(this.partitioner);
            int seq = ((Number) this.stack.pop()).intValue();
            this.queues[seq % this.parallelism].put(msg);
          } else {
            this.queue.put(msg);
          }
        } catch (Exception e) {
          // Ignore exceptions
        }
      } catch (Exception e) {
        LOG.error("Caught exception while receiving message", e);
      }      
    }
    
  }
  
  public void end() {
    this.done = true;
    try {
      this.connection.disconnect();
      
      for (Thread t: this.executors) {
        t.interrupt();
      }
    } catch (Exception e) {
    }
  }
  
  public String getWarpScript() {
    return this.warpscript;
  }
}
