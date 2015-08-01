/**
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.group;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterStatus;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HostPort;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.master.LoadBalancer;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.RegionPlan;
import org.apache.hadoop.hbase.master.balancer.StochasticLoadBalancer;
import org.apache.hadoop.hbase.security.access.AccessControlLists;
import org.apache.hadoop.util.ReflectionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * GroupBasedLoadBalancer, used when Region Server Grouping is configured (HBase-6721)
 * It does region balance based on a table's group membership.
 *
 * Most assignment methods contain two exclusive code paths: Online - when the group
 * table is online and Offline - when it is unavailable.
 *
 * During Offline, assignments are assigned based on cached information in zookeeper.
 * If unavailable (ie bootstrap) then regions are assigned randombly.
 *
 * Once the GROUP table has been assigned, the balancer switches to Online and will then
 * start providing appropriate assignments for user tables.
 *
 */
@InterfaceAudience.Public
public class GroupBasedLoadBalancer implements GroupableBalancer, LoadBalancer {
  /** Config for pluggable load balancers */
  public static final String HBASE_GROUP_LOADBALANCER_CLASS = "hbase.group.grouploadbalancer.class";

  private static final Log LOG = LogFactory.getLog(GroupBasedLoadBalancer.class);

  private Configuration config;
  private ClusterStatus clusterStatus;
  private MasterServices masterServices;
  private GroupInfoManager groupManager;
  private LoadBalancer internalBalancer;

  //used during reflection by LoadBalancerFactory
  @InterfaceAudience.Private
  public GroupBasedLoadBalancer() {
  }

  //This constructor should only be used for unit testing
  @InterfaceAudience.Private
  public GroupBasedLoadBalancer(GroupInfoManager groupManager) {
    this.groupManager = groupManager;
  }

  @Override
  public Configuration getConf() {
    return config;
  }

  @Override
  public void setConf(Configuration conf) {
    this.config = conf;
  }

  @Override
  public void setClusterStatus(ClusterStatus st) {
    this.clusterStatus = st;
  }

  @Override
  public void setMasterServices(MasterServices masterServices) {
    this.masterServices = masterServices;
  }

  @Override
  public List<RegionPlan> balanceCluster(Map<ServerName, List<HRegionInfo>> clusterState)
      throws HBaseIOException {

    if (!isOnline()) {
      throw new IllegalStateException(GroupInfoManager.GROUP_TABLE_NAME+
          " is not online, unable to perform balance");
    }

    Map<ServerName,List<HRegionInfo>> correctedState = correctAssignments(clusterState);
    List<RegionPlan> regionPlans = new ArrayList<RegionPlan>();
    try {
      for (GroupInfo info : groupManager.listGroups()) {
        Map<ServerName, List<HRegionInfo>> groupClusterState = new HashMap<ServerName, List<HRegionInfo>>();
        for (HostPort sName : info.getServers()) {
          for(ServerName curr: clusterState.keySet()) {
            if(curr.getHostPort().equals(sName)) {
              groupClusterState.put(curr, correctedState.get(curr));
            }
          }
        }
        List<RegionPlan> groupPlans = this.internalBalancer
            .balanceCluster(groupClusterState);
        if (groupPlans != null) {
          regionPlans.addAll(groupPlans);
        }
      }
    } catch (IOException exp) {
      LOG.warn("Exception while balancing cluster.", exp);
      regionPlans.clear();
    }
    return regionPlans;
  }

  @Override
  public Map<ServerName, List<HRegionInfo>> roundRobinAssignment (
      List<HRegionInfo> regions, List<ServerName> servers) throws HBaseIOException {
    Map<ServerName, List<HRegionInfo>> assignments = Maps.newHashMap();
    ListMultimap<String,HRegionInfo> regionMap = LinkedListMultimap.create();
    ListMultimap<String,ServerName> serverMap = LinkedListMultimap.create();
    generateGroupMaps(regions, servers, regionMap, serverMap);
    for(String groupKey : regionMap.keySet()) {
      if (regionMap.get(groupKey).size() > 0) {
        Map<ServerName, List<HRegionInfo>> result =
            this.internalBalancer.roundRobinAssignment(
                regionMap.get(groupKey),
                serverMap.get(groupKey));
        if(result != null) {
          assignments.putAll(result);
        }
      }
    }
    return assignments;
  }

  @Override
  public Map<ServerName, List<HRegionInfo>> retainAssignment(
      Map<HRegionInfo, ServerName> regions, List<ServerName> servers) throws HBaseIOException {
    if (!isOnline()) {
      return offlineRetainAssignment(regions, servers);
    }
    return onlineRetainAssignment(regions, servers);
  }

  public Map<ServerName, List<HRegionInfo>> offlineRetainAssignment(
      Map<HRegionInfo, ServerName> regions, List<ServerName> servers) throws HBaseIOException {
      //We will just keep assignments even if they are incorrect.
      //Chances are most will be assigned correctly.
      //Then we just use balance to correct the misplaced few.
      //we need to correct catalog and group table assignment anyway.
      return internalBalancer.retainAssignment(regions, servers);
  }

  public Map<ServerName, List<HRegionInfo>> onlineRetainAssignment(
      Map<HRegionInfo, ServerName> regions, List<ServerName> servers) throws HBaseIOException {
    try {
      Map<ServerName, List<HRegionInfo>> assignments = new TreeMap<ServerName, List<HRegionInfo>>();
      ListMultimap<String, HRegionInfo> groupToRegion = ArrayListMultimap.create();
      List<HRegionInfo> misplacedRegions = getMisplacedRegions(regions);
      for (HRegionInfo region : regions.keySet()) {
        if (!misplacedRegions.contains(region)) {
          String groupName = groupManager.getGroupOfTable(region.getTable());
          groupToRegion.put(groupName, region);
        }
      }
      // Now the "groupToRegion" map has only the regions which have correct
      // assignments.
      for (String key : groupToRegion.keySet()) {
        Map<HRegionInfo, ServerName> currentAssignmentMap = new TreeMap<HRegionInfo, ServerName>();
        List<HRegionInfo> regionList = groupToRegion.get(key);
        GroupInfo info = groupManager.getGroup(key);
        List<ServerName> candidateList = filterOfflineServers(info, servers);
        for (HRegionInfo region : regionList) {
          currentAssignmentMap.put(region, regions.get(region));
        }
        assignments.putAll(this.internalBalancer.retainAssignment(
            currentAssignmentMap, candidateList));
      }

      for (HRegionInfo region : misplacedRegions) {
        String groupName = groupManager.getGroupOfTable(
            region.getTable());
        GroupInfo info = groupManager.getGroup(groupName);
        List<ServerName> candidateList = filterOfflineServers(info, servers);
        ServerName server = this.internalBalancer.randomAssignment(region,
            candidateList);
        if (server != null && !assignments.containsKey(server)) {
          assignments.put(server, new ArrayList<HRegionInfo>());
        } else if (server != null) {
          assignments.get(server).add(region);
        } else {
          //if not server is available assign to bogus so it ends up in RIT
          if(!assignments.containsKey(BOGUS_SERVER_NAME)) {
            assignments.put(BOGUS_SERVER_NAME, new ArrayList<HRegionInfo>());
          }
          assignments.get(BOGUS_SERVER_NAME).add(region);
        }
      }
      return assignments;
    } catch (IOException e) {
      throw new HBaseIOException("Failed to do online retain assignment", e);
    }
  }

  @Override
  public Map<HRegionInfo, ServerName> immediateAssignment(
      List<HRegionInfo> regions, List<ServerName> servers) throws HBaseIOException {
    Map<HRegionInfo,ServerName> assignments = Maps.newHashMap();
    ListMultimap<String,HRegionInfo> regionMap = LinkedListMultimap.create();
    ListMultimap<String,ServerName> serverMap = LinkedListMultimap.create();
    generateGroupMaps(regions, servers, regionMap, serverMap);
    for(String groupKey : regionMap.keySet()) {
      if (regionMap.get(groupKey).size() > 0) {
        assignments.putAll(
            this.internalBalancer.immediateAssignment(
                regionMap.get(groupKey),
                serverMap.get(groupKey)));
      }
    }
    return assignments;
  }

  @Override
  public ServerName randomAssignment(HRegionInfo region,
      List<ServerName> servers) throws HBaseIOException {
    ListMultimap<String,HRegionInfo> regionMap = LinkedListMultimap.create();
    ListMultimap<String,ServerName> serverMap = LinkedListMultimap.create();
    generateGroupMaps(Lists.newArrayList(region), servers, regionMap, serverMap);
    List<ServerName> filteredServers = serverMap.get(regionMap.keySet().iterator().next());
    return this.internalBalancer.randomAssignment(region, filteredServers);
  }

  private void generateGroupMaps(
    List<HRegionInfo> regions,
    List<ServerName> servers,
    ListMultimap<String, HRegionInfo> regionMap,
    ListMultimap<String, ServerName> serverMap) throws HBaseIOException {
    try {
      for (HRegionInfo region : regions) {
        String groupName = groupManager.getGroupOfTable(region.getTable());
        if(groupName == null) {
          LOG.warn("Group for table "+region.getTable()+" is null");
        }
        regionMap.put(groupName, region);
      }
      for (String groupKey : regionMap.keySet()) {
        GroupInfo info = groupManager.getGroup(groupKey);
        serverMap.putAll(groupKey, filterOfflineServers(info, servers));
        if(serverMap.get(groupKey).size() < 1) {
          serverMap.put(groupKey, BOGUS_SERVER_NAME);
        }
      }
    } catch(IOException e) {
      throw new HBaseIOException("Failed to generate group maps", e);
    }
  }

  private List<ServerName> filterOfflineServers(GroupInfo groupInfo,
                                                List<ServerName> onlineServers) {
    if (groupInfo != null) {
      return filterServers(groupInfo.getServers(), onlineServers);
    } else {
      LOG.debug("Group Information found to be null. Some regions might be unassigned.");
      return Collections.EMPTY_LIST;
    }
  }

  /**
   * Filter servers based on the online servers.
   *
   * @param servers
   *          the servers
   * @param onlineServers
   *          List of servers which are online.
   * @return the list
   */
  private List<ServerName> filterServers(Collection<HostPort> servers,
      Collection<ServerName> onlineServers) {
    ArrayList<ServerName> finalList = new ArrayList<ServerName>();
    for (HostPort server : servers) {
      for(ServerName curr: onlineServers) {
        if(curr.getHostPort().equals(server)) {
          finalList.add(curr);
        }
      }
    }
    return finalList;
  }

  private ListMultimap<String, HRegionInfo> groupRegions(
      List<HRegionInfo> regionList) throws IOException {
    ListMultimap<String, HRegionInfo> regionGroup = ArrayListMultimap
        .create();
    for (HRegionInfo region : regionList) {
      String groupName = groupManager.getGroupOfTable(region.getTable());
      regionGroup.put(groupName, region);
    }
    return regionGroup;
  }

  private List<HRegionInfo> getMisplacedRegions(
      Map<HRegionInfo, ServerName> regions) throws IOException {
    List<HRegionInfo> misplacedRegions = new ArrayList<HRegionInfo>();
    for (HRegionInfo region : regions.keySet()) {
      ServerName assignedServer = regions.get(region);
      GroupInfo info = groupManager.getGroup(groupManager.getGroupOfTable(region.getTable()));
      if (assignedServer != null &&
          (info == null || !info.containsServer(assignedServer.getHostPort()))) {
        LOG.warn("Found misplaced region: "+region.getRegionNameAsString()+
            " on server: "+assignedServer+
            " found in group: "+groupManager.getGroupOfServer(assignedServer.getHostPort())+
            " outside of group: "+info.getName());
        misplacedRegions.add(region);
      }
    }
    return misplacedRegions;
  }

  private Map<ServerName, List<HRegionInfo>> correctAssignments(
       Map<ServerName, List<HRegionInfo>> existingAssignments){
    Map<ServerName, List<HRegionInfo>> correctAssignments = new TreeMap<ServerName, List<HRegionInfo>>();
    List<HRegionInfo> misplacedRegions = new LinkedList<HRegionInfo>();
    for (ServerName sName : existingAssignments.keySet()) {
      correctAssignments.put(sName, new LinkedList<HRegionInfo>());
      List<HRegionInfo> regions = existingAssignments.get(sName);
      for (HRegionInfo region : regions) {
        GroupInfo info = null;
        try {
          info = groupManager.getGroup(groupManager.getGroupOfTable(region.getTable()));
        }catch(IOException exp){
          LOG.debug("Group information null for region of table " + region.getTable(),
              exp);
        }
        if ((info == null) || (!info.containsServer(sName.getHostPort()))) {
          // Misplaced region.
          misplacedRegions.add(region);
        } else {
          correctAssignments.get(sName).add(region);
        }
      }
    }

    //TODO bulk unassign?
    //unassign misplaced regions, so that they are assigned to correct groups.
    for(HRegionInfo info: misplacedRegions) {
      this.masterServices.getAssignmentManager().unassign(info);
    }
    return correctAssignments;
  }

  @Override
  public void initialize() throws HBaseIOException {
    // Create the balancer
    Class<? extends LoadBalancer> balancerKlass = config.getClass(
        HBASE_GROUP_LOADBALANCER_CLASS,
        StochasticLoadBalancer.class, LoadBalancer.class);
    internalBalancer = ReflectionUtils.newInstance(balancerKlass, config);
    internalBalancer.setClusterStatus(clusterStatus);
    internalBalancer.setMasterServices(masterServices);
    internalBalancer.setConf(config);
    internalBalancer.initialize();
  }

  public boolean isOnline() {
    return groupManager != null && groupManager.isOnline();
  }

  @InterfaceAudience.Private
  public GroupInfoManager getGroupInfoManager() throws IOException {
    return groupManager;
  }

  @Override
  public void regionOnline(HRegionInfo regionInfo, ServerName sn) {
  }

  @Override
  public void regionOffline(HRegionInfo regionInfo) {
  }

  @Override
  public void onConfigurationChange(Configuration conf) {
    //DO nothing for now
  }

  @Override
  public void stop(String why) {
  }

  @Override
  public boolean isStopped() {
    return false;
  }

  @Override
  public void setGroupInfoManager(GroupInfoManager groupInfoManager) throws IOException {
    this.groupManager = groupInfoManager;
  }
}