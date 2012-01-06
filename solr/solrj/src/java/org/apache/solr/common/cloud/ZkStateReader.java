package org.apache.solr.common.cloud;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.UnicodeUtil;
import org.apache.noggit.CharArr;
import org.apache.noggit.JSONParser;
import org.apache.noggit.JSONWriter;
import org.apache.noggit.ObjectBuilder;
import org.apache.solr.common.SolrException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZkStateReader {
  private static Logger log = LoggerFactory.getLogger(ZkStateReader.class);
  
  public static final String BASE_URL_PROP = "base_url";
  public static final String NODE_NAME_PROP = "node_name";
  public static final String ROLES_PROP = "roles";
  public static final String STATE_PROP = "state";
  public static final String CORE_PROP = "core";
  public static final String COLLECTION_PROP = "collection";
  public static final String SHARD_ID_PROP = "shard_id";
  public static final String NUM_SHARDS_PROP = "numShards";
  public static final String LEADER_PROP = "leader";
  
  public static final String COLLECTIONS_ZKNODE = "/collections";
  public static final String LIVE_NODES_ZKNODE = "/live_nodes";
  public static final String CLUSTER_STATE = "/clusterstate.json";
  
  public static final String RECOVERING = "recovering";
  public static final String ACTIVE = "active";
  
  private volatile CloudState cloudState;

  private static final long SOLRCLOUD_UPDATE_DELAY = Long.parseLong(System.getProperty("solrcloud.update.delay", "5000"));

  public static final String LEADER_ELECT_ZKNODE = "/leader_elect";

  public static final String SHARD_LEADERS_ZKNODE = "leaders";


  
  //
  // convenience methods... should these go somewhere else?
  //
  public static byte[] toJSON(Object o) {
    CharArr out = new CharArr();
    new JSONWriter(out, 2).write(o); // indentation by default
    return toUTF8(out);
  }

  public static byte[] toUTF8(CharArr out) {
    BytesRef br = new BytesRef(out);
    return Arrays.copyOf(br.bytes, br.length);
  }

  public static Object fromJSON(byte[] utf8) {
    // convert directly from bytes to chars
    // and parse directly from that instead of going through
    // intermediate strings or readers
    CharsRef chars = new CharsRef();
    UnicodeUtil.UTF8toUTF16(utf8, 0, utf8.length, chars);   // TODO: this method currently oversizes the array
    JSONParser parser = new JSONParser(chars.chars, chars.offset, chars.length);
    try {
      return ObjectBuilder.getVal(parser);
    } catch (IOException e) {
      throw new RuntimeException(e); // should never happen w/o using real IO
    }
  }


  private static class ZKTF implements ThreadFactory {
    private static ThreadGroup tg = new ThreadGroup("ZkStateReader");
    @Override
    public Thread newThread(Runnable r) {
      Thread td = new Thread(tg, r);
      td.setDaemon(true);
      return td;
    }
  }
  private ScheduledExecutorService updateCloudExecutor = Executors.newScheduledThreadPool(1, new ZKTF());

  private boolean cloudStateUpdateScheduled;

  private SolrZkClient zkClient;
  
  private boolean closeClient = false;

  private ZkCmdExecutor cmdExecutor;
  
  public ZkStateReader(SolrZkClient zkClient) {
    this.zkClient = zkClient;
    cmdExecutor = new ZkCmdExecutor(zkClient);
  }
  
  public ZkStateReader(String zkServerAddress, int zkClientTimeout, int zkClientConnectTimeout) throws InterruptedException, TimeoutException, IOException {
    closeClient = true;
    zkClient = new SolrZkClient(zkServerAddress, zkClientTimeout, zkClientConnectTimeout,
        // on reconnect, reload cloud info
        new OnReconnect() {

          public void command() {
            try {
            	ZkStateReader.this.createClusterStateWatchersAndUpdate();
            } catch (KeeperException e) {
              log.error("", e);
              throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
                  "", e);
            } catch (InterruptedException e) {
              // Restore the interrupted status
              Thread.currentThread().interrupt();
              log.error("", e);
              throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
                  "", e);
            } 

          }
        });
    cmdExecutor = new ZkCmdExecutor(zkClient);
  }
  
  // load and publish a new CollectionInfo
  public void updateCloudState(boolean immediate) throws KeeperException, InterruptedException {
    updateCloudState(immediate, false);
  }
  
  // load and publish a new CollectionInfo
  public void updateLiveNodes() throws KeeperException, InterruptedException {
    updateCloudState(true, true);
  }
  
  public synchronized void createClusterStateWatchersAndUpdate() throws KeeperException,
      InterruptedException {
    // We need to fetch the current cluster state and the set of live nodes
    
    synchronized (getUpdateLock()) {
      Boolean exists = cmdExecutor.retryOperation(new ZkOperation() {
        
        @Override
        public Boolean execute() throws KeeperException, InterruptedException {
          return zkClient.exists(CLUSTER_STATE);
        }
      });
      
      if (!exists) {
        try {
          cmdExecutor.retryOperation(new ZkOperation() {
            
            @Override
            public Object execute() throws KeeperException, InterruptedException {
              zkClient.create(CLUSTER_STATE, null, CreateMode.PERSISTENT);
              return null;
            }
          });
          
          
        } catch (NodeExistsException e) {
          // if someone beats us to creating this ignore it
        }
      }
    }
    
    
    log.info("Updating cluster state from ZooKeeper... ");
    zkClient.exists(CLUSTER_STATE, new Watcher() {
      
      @Override
      public void process(WatchedEvent event) {
        log.info("A cluster state change has occurred");
        try {
          
          // delayed approach
          // ZkStateReader.this.updateCloudState(false, false);
          synchronized (ZkStateReader.this.getUpdateLock()) {
            // remake watch
            final Watcher thisWatch = this;
            byte[] data = cmdExecutor.retryOperation(new ZkOperation() {
              @Override
              public byte[] execute() throws KeeperException,
                  InterruptedException {
                return zkClient.getData(CLUSTER_STATE, thisWatch, null);
              }
            });
            
            CloudState clusterState = CloudState.load(data,
                ZkStateReader.this.cloudState.getLiveNodes());
            // update volatile
            cloudState = clusterState;
          }
        } catch (KeeperException e) {
          if (e.code() == KeeperException.Code.SESSIONEXPIRED
              || e.code() == KeeperException.Code.CONNECTIONLOSS) {
            log.warn("ZooKeeper watch triggered, but Solr cannot talk to ZK");
            return;
          }
          log.error("", e);
          throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
              "", e);
        } catch (InterruptedException e) {
          // Restore the interrupted status
          Thread.currentThread().interrupt();
          log.warn("", e);
          return;
        } 
      }
      
    });
    
    synchronized (ZkStateReader.this.getUpdateLock()) {
      List<String> liveNodes = zkClient.getChildren(LIVE_NODES_ZKNODE,
          new Watcher() {
            
            @Override
            public void process(WatchedEvent event) {
              log.info("Updating live nodes");
              try {
                // delayed approach
                // ZkStateReader.this.updateCloudState(false, true);
                synchronized (ZkStateReader.this.getUpdateLock()) {
                  List<String> liveNodes = zkClient.getChildren(
                      LIVE_NODES_ZKNODE, this);
                  Set<String> liveNodesSet = new HashSet<String>();
                  liveNodesSet.addAll(liveNodes);
                  CloudState clusterState = new CloudState(liveNodesSet,
                      ZkStateReader.this.cloudState.getCollectionStates());
                  ZkStateReader.this.cloudState = clusterState;
                }
              } catch (KeeperException e) {
                if (e.code() == KeeperException.Code.SESSIONEXPIRED
                    || e.code() == KeeperException.Code.CONNECTIONLOSS) {
                  log.warn("ZooKeeper watch triggered, but Solr cannot talk to ZK");
                  return;
                }
                log.error("", e);
                throw new ZooKeeperException(
                    SolrException.ErrorCode.SERVER_ERROR, "", e);
              } catch (InterruptedException e) {
                // Restore the interrupted status
                Thread.currentThread().interrupt();
                log.warn("", e);
                return;
              }
            }
            
          });
      Set<String> liveNodeSet = new HashSet<String>();
      liveNodeSet.addAll(liveNodes);
      CloudState clusterState = CloudState.load(zkClient, liveNodeSet);
      this.cloudState = clusterState;
    }
  }
  
  
  // load and publish a new CollectionInfo
  private synchronized void updateCloudState(boolean immediate,
      final boolean onlyLiveNodes) throws KeeperException,
      InterruptedException {
    log.info("Manual update of cluster state initiated");
    // build immutable CloudInfo
    
    if (immediate) {
      CloudState clusterState;
      synchronized (getUpdateLock()) {
      List<String> liveNodes = zkClient.getChildren(LIVE_NODES_ZKNODE, null);
      Set<String> liveNodesSet = new HashSet<String>();
      liveNodesSet.addAll(liveNodes);
      
        if (!onlyLiveNodes) {
          log.info("Updating cloud state from ZooKeeper... ");
          
          clusterState = CloudState.load(zkClient, liveNodesSet);
        } else {
          log.info("Updating live nodes from ZooKeeper... ");
          clusterState = new CloudState(liveNodesSet,
              ZkStateReader.this.cloudState.getCollectionStates());
        }
      }

      this.cloudState = clusterState;
    } else {
      if (cloudStateUpdateScheduled) {
        log.info("Cloud state update for ZooKeeper already scheduled");
        return;
      }
      log.info("Scheduling cloud state update from ZooKeeper...");
      cloudStateUpdateScheduled = true;
      updateCloudExecutor.schedule(new Runnable() {
        
        public void run() {
          log.info("Updating cluster state from ZooKeeper...");
          synchronized (getUpdateLock()) {
            cloudStateUpdateScheduled = false;
            CloudState clusterState;
            try {
              List<String> liveNodes = zkClient.getChildren(LIVE_NODES_ZKNODE,
                  null);
              Set<String> liveNodesSet = new HashSet<String>();
              liveNodesSet.addAll(liveNodes);
              
              if (!onlyLiveNodes) {
                log.info("Updating cloud state from ZooKeeper... ");
                
                clusterState = CloudState.load(zkClient, liveNodesSet);
              } else {
                log.info("Updating live nodes from ZooKeeper... ");
                clusterState = new CloudState(liveNodesSet, ZkStateReader.this.cloudState.getCollectionStates());
              }
              
              ZkStateReader.this.cloudState = clusterState;
              
            } catch (KeeperException e) {
              if (e.code() == KeeperException.Code.SESSIONEXPIRED
                  || e.code() == KeeperException.Code.CONNECTIONLOSS) {
                log.warn("ZooKeeper watch triggered, but Solr cannot talk to ZK");
                return;
              }
              log.error("", e);
              throw new ZooKeeperException(
                  SolrException.ErrorCode.SERVER_ERROR, "", e);
            } catch (InterruptedException e) {
              // Restore the interrupted status
              Thread.currentThread().interrupt();
              log.error("", e);
              throw new ZooKeeperException(
                  SolrException.ErrorCode.SERVER_ERROR, "", e);
            } 
            // update volatile
            ZkStateReader.this.cloudState = cloudState;
          }
        }
      }, SOLRCLOUD_UPDATE_DELAY, TimeUnit.MILLISECONDS);
    }
    
  }
   
  /**
   * @return information about the cluster from ZooKeeper
   */
  public CloudState getCloudState() {
    return cloudState;
  }
  
  public Object getUpdateLock() {
    return this;
  }

  public void close() {
    if (closeClient) {
      try {
        zkClient.close();
      } catch (InterruptedException e) {
        // Restore the interrupted status
        Thread.currentThread().interrupt();
        log.error("", e);
        throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR, "",
            e);
      }
    }
  }
  
  abstract class RunnableWatcher implements Runnable {
		Watcher watcher;
		public RunnableWatcher(Watcher watcher){
			this.watcher = watcher;
		}

	}
  
  public String getLeaderUrl(String collection, String shard) throws InterruptedException, KeeperException {
    ZkCoreNodeProps props = new ZkCoreNodeProps(getLeaderProps(collection,shard));
    
    return props.getCoreUrl();
  }
  
  public ZkNodeProps getLeaderProps(String collection, String shard) throws InterruptedException {
    int tries = 120;
    while (tries-- > 0) {
      if (cloudState != null) {
        final CloudState currentState = cloudState;
        final Slice slice = currentState.getSlice(collection, shard);
        if (slice != null) {
          // TODO: we probably should also catch this while building the cloud state and offer a getLeader off each
          // slice so that we do not have to search...
          for (ZkNodeProps nodeProps : slice.getShards().values()) {
            if (nodeProps.containsKey(ZkStateReader.LEADER_PROP)) {
              return nodeProps;
            }
          }
        }
      }
      Thread.sleep(500);
    }
    throw new RuntimeException("No registered leader was found, collection:" + collection + " slice:" + shard);
  }

  public static String getShardLeadersPath(String collection, String shardId) {
    return COLLECTIONS_ZKNODE + "/" + collection + "/"
        + SHARD_LEADERS_ZKNODE + (shardId != null ? ("/" + shardId)
        : "");
  }
  
}
