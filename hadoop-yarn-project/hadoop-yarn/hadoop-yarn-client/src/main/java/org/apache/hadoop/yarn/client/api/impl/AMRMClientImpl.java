/**
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

package org.apache.hadoop.yarn.client.api.impl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationMasterProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NMToken;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceBlacklistRequest;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.api.records.Token;
import org.apache.hadoop.yarn.client.ClientRMProxy;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.InvalidContainerRequestException;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.ApplicationMasterNotRegisteredException;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.ipc.RPCUtil;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.util.RackResolver;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.sun.tools.classfile.InnerClasses_attribute.Info;
import com.sun.tools.javac.comp.Infer;

import org.apache.hadoop.yarn.client.api.UnmanagedAMClient;

@Private
@Unstable
public class AMRMClientImpl<T extends ContainerRequest> extends AMRMClient<T> {

  private static final Log LOG = LogFactory.getLog(AMRMClientImpl.class);
  private static final List<String> ANY_LIST =
      Collections.singletonList(ResourceRequest.ANY);
  
  private int lastResponseId = 0;
  
  private int lastResponseIdRemote = 0;

  protected String appHostName;
  protected int appHostPort;
  protected String appTrackingUrl;
  protected String appId = "none";

  protected ApplicationMasterProtocol rmClient;
  boolean isRemote;
  boolean hasRemoteReq = false;
  UnmanagedAMClient uAMClient;
  Thread ram;
  protected Resource clusterAvailableResources;
  protected int clusterNodeCount;
  
  private Map<Integer, Long> expResponseTime = new HashMap<Integer, Long>();
  
  // blacklistedNodes is required for keeping history of blacklisted nodes that
  // are sent to RM. On RESYNC command from RM, blacklistedNodes are used to get
  // current blacklisted nodes and send back to RM.
  protected final Set<String> blacklistedNodes = new HashSet<String>();
  protected final Set<String> blacklistAdditions = new HashSet<String>();
  protected final Set<String> blacklistRemovals = new HashSet<String>();
  
  class ResourceRequestInfo {
    ResourceRequest remoteRequest;
    LinkedHashSet<T> containerRequests;
    
    ResourceRequestInfo(Priority priority, String resourceName,
        Resource capability, boolean relaxLocality) {
      remoteRequest = ResourceRequest.newInstance(priority, resourceName,
          capability, 0);
      remoteRequest.setRelaxLocality(relaxLocality);
      containerRequests = new LinkedHashSet<T>();
    }
  }
  
  
  /**
   * Class compares Resource by memory then cpu in reverse order
   */
  class ResourceReverseMemoryThenCpuComparator implements Comparator<Resource> {
    @Override
    public int compare(Resource arg0, Resource arg1) {
      int mem0 = arg0.getMemory();
      int mem1 = arg1.getMemory();
      int cpu0 = arg0.getVirtualCores();
      int cpu1 = arg1.getVirtualCores();
      if(mem0 == mem1) {
        if(cpu0 == cpu1) {
          return 0;
        }
        if(cpu0 < cpu1) {
          return 1;
        }
        return -1;
      }
      if(mem0 < mem1) { 
        return 1;
      }
      return -1;
    }    
  }
  
  static boolean canFit(Resource arg0, Resource arg1) {
    int mem0 = arg0.getMemory();
    int mem1 = arg1.getMemory();
    int cpu0 = arg0.getVirtualCores();
    int cpu1 = arg1.getVirtualCores();
    
    if(mem0 <= mem1 && cpu0 <= cpu1) { 
      return true;
    }
    return false; 
  }
  
  //Key -> Priority
  //Value -> Map
  //Key->ResourceName (e.g., nodename, rackname, *)
  //Value->Map
  //Key->Resource Capability
  //Value->ResourceRequest
  protected final 
  Map<Priority, Map<String, TreeMap<Resource, ResourceRequestInfo>>>
    remoteRequestsTable =
    new TreeMap<Priority, Map<String, TreeMap<Resource, ResourceRequestInfo>>>();

  protected final Set<ResourceRequest> ask = new TreeSet<ResourceRequest>(
      new org.apache.hadoop.yarn.api.records.ResourceRequest.ResourceRequestComparator());
  protected final Set<ContainerId> release = new TreeSet<ContainerId>();
  // pendingRelease holds history or release requests.request is removed only if
  // RM sends completedContainer.
  // How it different from release? --> release is for per allocate() request.
  protected Set<ContainerId> pendingRelease = new TreeSet<ContainerId>();
  
  public AMRMClientImpl() {
    super(AMRMClientImpl.class.getName());
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    RackResolver.init(conf);
    super.serviceInit(conf);
  }

  @Override
  protected void serviceStart() throws Exception {
    final YarnConfiguration conf = new YarnConfiguration(getConfig());
    try {
    	isRemote = conf.getBoolean("yarn.isRemote", false);
    	//isUnmanaged = conf.getBoolean("yarn.isUnmanaged", false);
    	LOG.info("isRemote: " + isRemote);
       rmClient =
           ClientRMProxy.createRMProxy(conf, ApplicationMasterProtocol.class);
    	LOG.info("rmClient create over");
		
    	if(isRemote){
    		LOG.info("new UAM");
    		uAMClient = new UnmanagedAMClient();
    		uAMClient.init(conf);
    		uAMClient.start();
    		//runRAM();
    	}
    	
    } catch (IOException e) {
      throw new YarnRuntimeException(e);
    }
    super.serviceStart();
  }

  @Override
  protected void serviceStop() throws Exception {
    if (this.rmClient != null) {
      RPC.stopProxy(this.rmClient);
    }
    if (isRemote) {
    	uAMClient.stop();
       // ram.join();
      }

    super.serviceStop();
  }
  

  
  @Override
  public RegisterApplicationMasterResponse registerApplicationMaster(
      String appHostName, int appHostPort, String appTrackingUrl)
      throws YarnException, IOException {
    this.appHostName = appHostName;
    this.appHostPort = appHostPort;
    this.appTrackingUrl = appTrackingUrl;
    
    Preconditions.checkArgument(appHostName != null,
        "The host name should not be null");
    Preconditions.checkArgument(appHostPort >= -1, "Port number of the host"
        + " should be any integers larger than or equal to -1");

    return registerApplicationMaster();
  }

  private RegisterApplicationMasterResponse registerApplicationMaster()
      throws YarnException, IOException {
    RegisterApplicationMasterRequest request =
        RegisterApplicationMasterRequest.newInstance(this.appHostName,
            this.appHostPort, this.appTrackingUrl);
    
    RegisterApplicationMasterResponse response = rmClient.registerApplicationMaster(request);
    synchronized (this) {
      lastResponseId = 0;
      if (!response.getNMTokensFromPreviousAttempts().isEmpty()) {
        populateNMTokens(response.getNMTokensFromPreviousAttempts());
      }
    }
    if(isRemote){
    	startUAM();
    }
    return response;
  }
  
  private void startUAM(){
	  uAMClient.setHostinfo(this.appHostName, this.appHostPort, this.appTrackingUrl);
	  uAMClient.startUAM();
  }

  @Override
  public void setAppId(String id){
	  LOG.info("appId: " + id); 
	  this.appId = id;
  }
  
  private void writeExpData() throws IOException, YarnException{
	  LOG.info("save expData");
     /*
      String filePath = "/home/zxd/expdata/allocateResponseTime.csv";
      File file = new File(filePath);
      if(file.exists()){
    	  LOG.info("file exits!");
      }
      FileOutputStream out = new FileOutputStream(file);
      OutputStreamWriter osw = new OutputStreamWriter(out, "UTF8");

      BufferedWriter bw = new BufferedWriter(osw);
      bw.append("responseId,responseTime(ms)\n");
 	  for(Map.Entry<Integer, Long> entry : this.expResponseTime.entrySet()){
 		  bw.append(String.valueOf(entry.getKey()) + "," + String.valueOf(entry.getValue()) + "\n");
 	  }

     bw.close();
     osw.close();
     out.close(); 
      */


     String fileName = "/home/zxd/expdata/" + this.appId + "/allocateResponseTime.csv";
     File dir = new File("/home/zxd/expdata/" +this.appId);
     if(!dir.exists()) {
       dir.mkdir();
     }
     
     FileWriter writer = new FileWriter(fileName, true);
     writer.write("responseId,responseTime(ms)\n");
     for(Map.Entry<Integer, Long> entry : this.expResponseTime.entrySet()){
    	 writer.write(String.valueOf(entry.getKey()) + "," + String.valueOf(entry.getValue()) + "\n");
     }
     writer.close();

     
     if(isRemote){
 	    uAMClient.writeExpData();
  }
      
  }
  
  public AllocateResponse allocateLocal(float progressIndicator)
		  throws YarnException, IOException{
	  LOG.info("allocate for local");
	  Preconditions.checkArgument(progressIndicator >= 0,
		        "Progress indicator should not be negative");
		    AllocateResponse allocateResponse = null;
		    List<ResourceRequest> askList = null;
		    List<ContainerId> releaseList = null;
		    AllocateRequest allocateRequest = null;
		    List<String> blacklistToAdd = new ArrayList<String>();
		    List<String> blacklistToRemove = new ArrayList<String>();
		    
		    try {
		      synchronized (this) {
		        askList = new ArrayList<ResourceRequest>(ask.size());
		        for(ResourceRequest r : ask) {
		          // create a copy of ResourceRequest as we might change it while the 
		          // RPC layer is using it to send info across
		          askList.add(ResourceRequest.newInstance(r.getPriority(),
		              r.getResourceName(), r.getCapability(), r.getNumContainers(),
		              r.getRelaxLocality(), r.getNodeLabelExpression()));
		        }
		        releaseList = new ArrayList<ContainerId>(release);
		        // optimistically clear this collection assuming no RPC failure
		        ask.clear();
		        release.clear();

		        blacklistToAdd.addAll(blacklistAdditions);
		        blacklistToRemove.addAll(blacklistRemovals);
		        
		        ResourceBlacklistRequest blacklistRequest = 
		            (blacklistToAdd != null) || (blacklistToRemove != null) ? 
		            ResourceBlacklistRequest.newInstance(blacklistToAdd,
		                blacklistToRemove) : null;
		        
		        allocateRequest =
		            AllocateRequest.newInstance(lastResponseId, progressIndicator,
		              askList, releaseList, blacklistRequest);
		        // clear blacklistAdditions and blacklistRemovals before 
		        // unsynchronized part
		        blacklistAdditions.clear();
		        blacklistRemovals.clear();
		      }

		      try {
		    	  //long start = System.currentTimeMillis();
		    	  allocateResponse = rmClient.allocate(allocateRequest);
		    	  /*
		    	  long end = System.currentTimeMillis();
		    	  if(allocateResponse != null){
			    	  expResponseTime.put(allocateResponse.getResponseId(), end - start);
		    	  }
		    	  */
		      } catch (ApplicationMasterNotRegisteredException e) {
		        LOG.warn("ApplicationMaster is out of sync with ResourceManager,"
		            + " hence resyncing.");
		        synchronized (this) {
		          release.addAll(this.pendingRelease);
		          blacklistAdditions.addAll(this.blacklistedNodes);
		          for (Map<String, TreeMap<Resource, ResourceRequestInfo>> rr : remoteRequestsTable
		            .values()) {
		            for (Map<Resource, ResourceRequestInfo> capabalities : rr.values()) {
		              for (ResourceRequestInfo request : capabalities.values()) {
		                addResourceRequestToAsk(request.remoteRequest);
		              }
		            }
		          }
		        }
		        // re register with RM
		        registerApplicationMaster();
		        allocateResponse = allocate(progressIndicator);
		        return allocateResponse;
		      }

		      synchronized (this) {
		        // update these on successful RPC
		        clusterNodeCount = allocateResponse.getNumClusterNodes();
		        lastResponseId = allocateResponse.getResponseId();
		        clusterAvailableResources = allocateResponse.getAvailableResources();
		        if (!allocateResponse.getNMTokens().isEmpty()) {
		          populateNMTokens(allocateResponse.getNMTokens());
		        }
		        if (allocateResponse.getAMRMToken() != null) {
		          updateAMRMToken(allocateResponse.getAMRMToken());
		        }
		        if (!pendingRelease.isEmpty()
		            && !allocateResponse.getCompletedContainersStatuses().isEmpty()) {
		          removePendingReleaseRequests(allocateResponse
		              .getCompletedContainersStatuses());
		        }
		      }
		    } finally {
		      // TODO how to differentiate remote yarn exception vs error in rpc
		      if(allocateResponse == null) {
		        // we hit an exception in allocate()
		        // preserve ask and release for next call to allocate()
		        synchronized (this) {
		          release.addAll(releaseList);
		          // requests could have been added or deleted during call to allocate
		          // If requests were added/removed then there is nothing to do since
		          // the ResourceRequest object in ask would have the actual new value.
		          // If ask does not have this ResourceRequest then it was unchanged and
		          // so we can add the value back safely.
		          // This assumes that there will no concurrent calls to allocate() and
		          // so we dont have to worry about ask being changed in the
		          // synchronized block at the beginning of this method.
		          for(ResourceRequest oldAsk : askList) {
		            if(!ask.contains(oldAsk)) {
		              ask.add(oldAsk);
		            }
		          }
		          
		          blacklistAdditions.addAll(blacklistToAdd);
		          blacklistRemovals.addAll(blacklistToRemove);
		        }
		      }
		    }
		return allocateResponse;
  }
  
  @Override
  public AllocateResponse allocate(float progressIndicator) 
      throws YarnException, IOException {
	  if(!isRemote){
		  return allocateLocal(progressIndicator);
	  }
	  
    Preconditions.checkArgument(progressIndicator >= 0,
        "Progress indicator should not be negative");
    
    AllocateResponse allocateResponse = null;
    List<ResourceRequest> askList = null;
    List<ContainerId> releaseList = new ArrayList<ContainerId>();;
    AllocateRequest allocateRequest = null;
    List<String> blacklistToAdd = new ArrayList<String>();
    List<String> blacklistToRemove = new ArrayList<String>();
    
    AllocateResponse allocateResponseRemote = null;
    List<ResourceRequest> askListRemote = null;
    List<ContainerId> releaseListRemote = new ArrayList<ContainerId>();;
    AllocateRequest allocateRequestRemote = null;
    List<String> blacklistToAddRemote = new ArrayList<String>();
    List<String> blacklistToRemoveRemote = new ArrayList<String>();
    
    AllocateResponse allocateResponseMerged = null;

    try {
      synchronized (this) {
        askList = new ArrayList<ResourceRequest>();
        askListRemote = new ArrayList<ResourceRequest>();
        for(ResourceRequest r : ask) {
          // create a copy of ResourceRequest as we might change it while the 
          // RPC layer is using it to send info across
        	String resourceName = r.getResourceName();
        	// LOG.info("resourceName in ask: " + resourceName);
        	if(resourceName.contains(ResourceRequest.ANY)){

        		if(askListRemote.isEmpty()){
            		askListRemote.add(ResourceRequest.newInstance(r.getPriority(),
            				ResourceRequest.ANY, r.getCapability(), r.getNumContainers(),
            				r.getRelaxLocality(), r.getNodeLabelExpression()));
        		}
        		
        		askList.add(ResourceRequest.newInstance(r.getPriority(),
        				ResourceRequest.ANY, r.getCapability(), r.getNumContainers(),
        				r.getRelaxLocality(), r.getNodeLabelExpression()));

        	}else if(resourceName.contains("hdfs")){
        		
        		int idx = resourceName.indexOf(":");
        		String resolveName = resourceName.substring(idx + 3);
        		r.setResourceName(resolveName);
        		 
        		askListRemote.add(ResourceRequest.newInstance(r.getPriority(),
        				resolveName, r.getCapability(), r.getNumContainers(),
        				r.getRelaxLocality(), r.getNodeLabelExpression()));
        		hasRemoteReq = true;
                LOG.info("hasRemote: " + hasRemoteReq);
        	}else {
        		askList.add(ResourceRequest.newInstance(r.getPriority(),
        				resourceName, r.getCapability(), r.getNumContainers(),
        				r.getRelaxLocality(), r.getNodeLabelExpression()));
        	}

        }
        if(!hasRemoteReq) {
        	askListRemote.clear();
        }
        ApplicationAttemptId remoteAppAttemptId = uAMClient.getApplicationAttemptId();
        for(ContainerId r: release){
        	if(r.getApplicationAttemptId().equals(remoteAppAttemptId)){
        		releaseListRemote.add(r);
        	}else{
        		releaseList.add(r);
        	}
        }
        //releaseList = new ArrayList<ContainerId>(release);
        
        // optimistically clear this collection assuming no RPC failure
        ask.clear();
        release.clear();

        blacklistToAdd.addAll(blacklistAdditions);
        blacklistToRemove.addAll(blacklistRemovals);
        
        ResourceBlacklistRequest blacklistRequest = 
            (blacklistToAdd != null) || (blacklistToRemove != null) ? 
            ResourceBlacklistRequest.newInstance(blacklistToAdd,
                blacklistToRemove) : null;
            
            ResourceBlacklistRequest blacklistRequestRemote =  null;
            
        allocateRequest =
            AllocateRequest.newInstance(lastResponseId, progressIndicator,
              askList, releaseList, blacklistRequest);
        
        allocateRequestRemote =
                AllocateRequest.newInstance(lastResponseIdRemote, progressIndicator,
                  askListRemote, releaseListRemote, blacklistRequestRemote);
        
        // clear blacklistAdditions and blacklistRemovals before 
        // unsynchronized part
        blacklistAdditions.clear();
        blacklistRemovals.clear();
      }

      try {
    	  // if(isRemote){
    	  //LOG.info("request to remote RM");
    	  allocateResponseRemote = uAMClient.allocate(allocateRequestRemote);
    	  /*
    	  if(allocateRequestRemote != null){
    		  LOG.info("remoteAllocateResponse get" );
    	  }else {
    		  LOG.info("remoteAllocateResponse null" );
		}
		*/

    	  //}else {
    	  //LOG.info("request to local RM");
    	  long start = System.currentTimeMillis();
    	  allocateResponse = rmClient.allocate(allocateRequest);
    	  long end = System.currentTimeMillis();
    	  if(allocateResponse != null){
        	  expResponseTime.put(allocateResponse.getResponseId(), end - start);  
    	  }
    	  
    	  /*
    	  if(allocateResponse != null){
    		  LOG.info("localAllocateResponse get" );
    	  }else {
    		  LOG.info("localAllocateResponse null" );
		}
		*/
    	  //}   
    	  
      } catch (ApplicationMasterNotRegisteredException e) {
        LOG.warn("ApplicationMaster is out of sync with ResourceManager,"
            + " hence resyncing.");
        synchronized (this) {
          release.addAll(this.pendingRelease);
          blacklistAdditions.addAll(this.blacklistedNodes);
          for (Map<String, TreeMap<Resource, ResourceRequestInfo>> rr : remoteRequestsTable
            .values()) {
            for (Map<Resource, ResourceRequestInfo> capabalities : rr.values()) {
              for (ResourceRequestInfo request : capabalities.values()) {
                addResourceRequestToAsk(request.remoteRequest);
              }
            }
          }
        }
        // re register with RM
        LOG.info("re register with RM");
        registerApplicationMaster();
        allocateResponse = allocate(progressIndicator);
        return allocateResponse;
      }

      synchronized (this) {
        // update these on successful RPC
        clusterNodeCount = allocateResponse.getNumClusterNodes();
        lastResponseId = allocateResponse.getResponseId();
        clusterAvailableResources = allocateResponse.getAvailableResources();
        if (!allocateResponse.getNMTokens().isEmpty()) {
          populateNMTokens(allocateResponse.getNMTokens());
        }
        
        
        if (allocateResponse.getAMRMToken() != null) {
          updateAMRMToken(allocateResponse.getAMRMToken());
        }
        
        if (!pendingRelease.isEmpty()
            && !allocateResponse.getCompletedContainersStatuses().isEmpty()) {
          removePendingReleaseRequests(allocateResponse
              .getCompletedContainersStatuses());
        }
        if(allocateResponseRemote != null){
            if (!allocateResponseRemote.getNMTokens().isEmpty()) {
                populateNMTokens(allocateResponseRemote.getNMTokens());
              }
            
            if (!pendingRelease.isEmpty()
                    && !allocateResponseRemote.getCompletedContainersStatuses().isEmpty()) {
                  removePendingReleaseRequests(allocateResponseRemote
                      .getCompletedContainersStatuses());
                }
        }
        allocateResponseMerged =  mergeAllocateResponse(allocateResponse, allocateResponseRemote);

        LOG.info("Merge complete");
      }
    } finally {
    	synchronized (this) {
    		// TODO how to differentiate remote yarn exception vs error in rpc
    		if(allocateResponse == null) {
    			LOG.info("localResponse is null");
    			// we hit an exception in allocate()
    			// preserve ask and release for next call to allocate()
    			release.addAll(releaseList);
    			// requests could have been added or deleted during call to allocate
    			// If requests were added/removed then there is nothing to do since
    			// the ResourceRequest object in ask would have the actual new value.
    			// If ask does not have this ResourceRequest then it was unchanged and
    			// so we can add the value back safely.
    			// This assumes that there will no concurrent calls to allocate() and
    			// so we dont have to worry about ask being changed in the
    			// synchronized block at the beginning of this method.
    			for(ResourceRequest oldAsk : askList) {
    				if(!ask.contains(oldAsk)) {
    					ask.add(oldAsk);
    				}
    			}
    			blacklistAdditions.addAll(blacklistToAdd);
    			blacklistRemovals.addAll(blacklistToRemove);
    		}
    		
    		if(allocateResponseRemote == null) {
    			//LOG.info("remoteResponse is null");
    			release.addAll(releaseListRemote);
    			for(ResourceRequest oldAsk : askListRemote) {
    				if(!ask.contains(oldAsk)) {
    					ask.add(oldAsk);
    				}
    			}
    			blacklistAdditions.addAll(blacklistToAddRemote);
    			blacklistRemovals.addAll(blacklistToRemoveRemote);
    		}
    	}
      
      
    }
    
    return allocateResponseMerged;
  }
  
  /*
   * need merge
   * 
   * NMTokens List<NMToken>
   * updatedNodeReports List<NodeReport>
   * AllocatedContainers  List<Container>
   * CompletedContainersStatuses List<ContainerStatus>
   * 
   * don't need merge:
   * 
   * ResponseId int
   * AvailableResources resource 
   * NumClusterNodes int 
   * PreemptionMessage PreemptionMessage
   * AMRMToken Token
   */
  private AllocateResponse mergeAllocateResponse(AllocateResponse responseBase, AllocateResponse response){
	  LOG.info("Mergeing response");
	  /*
	  List<NMToken> nmTokensMerged=  responseBase.getNMTokens();
	  nmTokensMerged.addAll(response.getNMTokens());
	  
	  List<NodeReport> updatedNodeReportsMerged = responseBase.getUpdatedNodes();
	  updatedNodeReportsMerged.addAll(response.getUpdatedNodes());
	  
	  List<Container>allocatedContainersMerged = responseBase.getAllocatedContainers();
	  allocatedContainersMerged.addAll(response.getAllocatedContainers());
	  
	  List<ContainerStatus> CompletedContainersStatusesMerged = responseBase.getCompletedContainersStatuses();
	  CompletedContainersStatusesMerged.addAll(response.getCompletedContainersStatuses());
	  */
	  
	  if(responseBase != null && response != null){
		  
		  List<NMToken> token = responseBase.getNMTokens();
		  if(token != null && !token.isEmpty()){
			  responseBase.setNMTokens(token);
		  }

		  List<NodeReport> report = response.getUpdatedNodes();
		  if(report != null && !report.isEmpty()){
			  List<NodeReport> resolveReport = new ArrayList<NodeReport>();
			  for(NodeReport r : report){
				  String newHostName = "hdfs1://" + r.getNodeId().getHost();
				  NodeId newNodeId = NodeId.newInstance(newHostName, r.getNodeId().getPort());
				  r.setNodeId(newNodeId);
				  resolveReport.add(r);
			  }
			  responseBase.setUpdatedNodes(resolveReport);
		  }

		  List<Container> containers = response.getAllocatedContainers();
		  if(containers!= null && !containers.isEmpty()){
			  List<Container> resolveContainers = new ArrayList<Container>();
			  for(Container c : containers){
				  LOG.info("remoteRes: " + c.getNodeId().getHost());
				  String newHostName = "hdfs1://" + c.getNodeId().getHost();
				  NodeId newNodeId = NodeId.newInstance(newHostName, c.getNodeId().getPort());
				  c.setNodeId(newNodeId);
				  resolveContainers.add(c);
			  }
			  responseBase.setAllocatedContainers(resolveContainers);
		  }

		  List<ContainerStatus> completed = response.getCompletedContainersStatuses();
		  if(completed != null && !completed.isEmpty()){
			  responseBase.setCompletedContainersStatuses(completed);  
		  }
		  
		  return responseBase;
	  }else if(responseBase != null){
		  return responseBase;
	  }else {
		  return response;
	}

	  
  }
  

  protected void removePendingReleaseRequests(
      List<ContainerStatus> completedContainersStatuses) {
    for (ContainerStatus containerStatus : completedContainersStatuses) {
      pendingRelease.remove(containerStatus.getContainerId());
    }
  }

  @Private
  @VisibleForTesting
  protected void populateNMTokens(List<NMToken> nmTokens) {
    for (NMToken token : nmTokens) {
      String nodeId = token.getNodeId().toString();
      if (getNMTokenCache().containsToken(nodeId)) {
        LOG.info("Replacing token for : " + nodeId);
      } else {
        LOG.info("Received new token for : " + nodeId);
      }
      getNMTokenCache().setToken(nodeId, token.getToken());
    }
  }

  @Override
  public void unregisterApplicationMaster(FinalApplicationStatus appStatus,
      String appMessage, String appTrackingUrl) throws YarnException,
      IOException {
    Preconditions.checkArgument(appStatus != null,
      "AppStatus should not be null.");
    FinishApplicationMasterRequest request =
        FinishApplicationMasterRequest.newInstance(appStatus, appMessage,
          appTrackingUrl);
    //writeExpData();
    try {
      while (true) {
    	  
    	  FinishApplicationMasterResponse response;
    	  if(isRemote){
        	  LOG.info("uAMClient start unregisterApplicationMaster");
        	  uAMClient.finishUAM();
    	  }
    	  response = rmClient.finishApplicationMaster(request);
      
        if (response.getIsUnregistered()) {
          break;
        }
        LOG.info("Waiting for application to be successfully unregistered.");
        Thread.sleep(100);
      }
    } catch (InterruptedException e) {
      LOG.info("Interrupted while waiting for application"
          + " to be removed from RMStateStore");
    } catch (ApplicationMasterNotRegisteredException e) {
      LOG.warn("ApplicationMaster is out of sync with ResourceManager,"
          + " hence resyncing.");
      // re register with RM
      registerApplicationMaster();
      unregisterApplicationMaster(appStatus, appMessage, appTrackingUrl);
    }
  }
  
  @Override
  public synchronized void addContainerRequest(T req) {
    Preconditions.checkArgument(req != null,
        "Resource request can not be null.");
    Set<String> dedupedRacks = new HashSet<String>();
    if (req.getRacks() != null) {
      dedupedRacks.addAll(req.getRacks());
      if(req.getRacks().size() != dedupedRacks.size()) {
        Joiner joiner = Joiner.on(',');
        LOG.warn("ContainerRequest has duplicate racks: "
            + joiner.join(req.getRacks()));
      }
    }
    Set<String> inferredRacks = resolveRacks(req.getNodes());
    inferredRacks.removeAll(dedupedRacks);

    // check that specific and non-specific requests cannot be mixed within a
    // priority
    checkLocalityRelaxationConflict(req.getPriority(), ANY_LIST,
        req.getRelaxLocality());
    // check that specific rack cannot be mixed with specific node within a 
    // priority. If node and its rack are both specified then they must be 
    // in the same request.
    // For explicitly requested racks, we set locality relaxation to true
    checkLocalityRelaxationConflict(req.getPriority(), dedupedRacks, true);
    checkLocalityRelaxationConflict(req.getPriority(), inferredRacks,
        req.getRelaxLocality());
    // check if the node label expression specified is valid
    checkNodeLabelExpression(req);

    if (req.getNodes() != null) {
      HashSet<String> dedupedNodes = new HashSet<String>(req.getNodes());
      if(dedupedNodes.size() != req.getNodes().size()) {
        Joiner joiner = Joiner.on(',');
        LOG.warn("ContainerRequest has duplicate nodes: "
            + joiner.join(req.getNodes()));        
      }
      for (String node : dedupedNodes) {
        addResourceRequest(req.getPriority(), node, req.getCapability(), req,
            true, req.getNodeLabelExpression());
      }
    }

    for (String rack : dedupedRacks) {
      addResourceRequest(req.getPriority(), rack, req.getCapability(), req,
          true, req.getNodeLabelExpression());
    }

    // Ensure node requests are accompanied by requests for
    // corresponding rack
    for (String rack : inferredRacks) {
      addResourceRequest(req.getPriority(), rack, req.getCapability(), req,
          req.getRelaxLocality(), req.getNodeLabelExpression());
    }

    // Off-switch
    addResourceRequest(req.getPriority(), ResourceRequest.ANY, 
        req.getCapability(), req, req.getRelaxLocality(), req.getNodeLabelExpression());
  }

  @Override
  public synchronized void removeContainerRequest(T req) {
    Preconditions.checkArgument(req != null,
        "Resource request can not be null.");
    Set<String> allRacks = new HashSet<String>();
    if (req.getRacks() != null) {
      allRacks.addAll(req.getRacks());
    }
    allRacks.addAll(resolveRacks(req.getNodes()));

    // Update resource requests
    if (req.getNodes() != null) {
      for (String node : new HashSet<String>(req.getNodes())) {
        decResourceRequest(req.getPriority(), node, req.getCapability(), req);
      }
    }

    for (String rack : allRacks) {
      decResourceRequest(req.getPriority(), rack, req.getCapability(), req);
    }

    decResourceRequest(req.getPriority(), ResourceRequest.ANY,
        req.getCapability(), req);
  }

  @Override
  public synchronized void releaseAssignedContainer(ContainerId containerId) {
    Preconditions.checkArgument(containerId != null,
        "ContainerId can not be null.");
    pendingRelease.add(containerId);
    release.add(containerId);
  }
  
  @Override
  public synchronized Resource getAvailableResources() {
    return clusterAvailableResources;
  }
  
  @Override
  public synchronized int getClusterNodeCount() {
    return clusterNodeCount;
  }
  
  @Override
  public synchronized List<? extends Collection<T>> getMatchingRequests(
                                          Priority priority, 
                                          String resourceName, 
                                          Resource capability) {
    Preconditions.checkArgument(capability != null,
        "The Resource to be requested should not be null ");
    Preconditions.checkArgument(priority != null,
        "The priority at which to request containers should not be null ");
    List<LinkedHashSet<T>> list = new LinkedList<LinkedHashSet<T>>();
    
    if(isRemote && !hasRemoteReq && resourceName.contains("hdfs")){
    	return list;
    }
    
    Map<String, TreeMap<Resource, ResourceRequestInfo>> remoteRequests = 
        this.remoteRequestsTable.get(priority);
    if (remoteRequests == null) {
      return list;
    }
    TreeMap<Resource, ResourceRequestInfo> reqMap = remoteRequests
        .get(resourceName);
    if (reqMap == null) {
      return list;
    }

    ResourceRequestInfo resourceRequestInfo = reqMap.get(capability);
    if (resourceRequestInfo != null &&
        !resourceRequestInfo.containerRequests.isEmpty()) {
      list.add(resourceRequestInfo.containerRequests);
      return list;
    }
    
    // no exact match. Container may be larger than what was requested.
    // get all resources <= capability. map is reverse sorted. 
    SortedMap<Resource, ResourceRequestInfo> tailMap = 
                                                  reqMap.tailMap(capability);
    for(Map.Entry<Resource, ResourceRequestInfo> entry : tailMap.entrySet()) {
      if (canFit(entry.getKey(), capability) &&
          !entry.getValue().containerRequests.isEmpty()) {
        // match found that fits in the larger resource
        list.add(entry.getValue().containerRequests);
      }
    }
    
    // no match found
    return list;          
  }
  
  private Set<String> resolveRacks(List<String> nodes) {
    Set<String> racks = new HashSet<String>();    
    if (nodes != null) {
      for (String node : nodes) {
        // Ensure node requests are accompanied by requests for
        // corresponding rack
        String rack = RackResolver.resolve(node).getNetworkLocation();
        if (rack == null) {
          LOG.warn("Failed to resolve rack for node " + node + ".");
        } else {
          racks.add(rack);
        }
      }
    }
    
    return racks;
  }
  
  /**
   * ContainerRequests with locality relaxation cannot be made at the same
   * priority as ContainerRequests without locality relaxation.
   */
  private void checkLocalityRelaxationConflict(Priority priority,
      Collection<String> locations, boolean relaxLocality) {
    Map<String, TreeMap<Resource, ResourceRequestInfo>> remoteRequests =
        this.remoteRequestsTable.get(priority);
    if (remoteRequests == null) {
      return;
    }
    // Locality relaxation will be set to relaxLocality for all implicitly
    // requested racks. Make sure that existing rack requests match this.
    for (String location : locations) {
        TreeMap<Resource, ResourceRequestInfo> reqs =
            remoteRequests.get(location);
        if (reqs != null && !reqs.isEmpty()) {
          boolean existingRelaxLocality =
              reqs.values().iterator().next().remoteRequest.getRelaxLocality();
          if (relaxLocality != existingRelaxLocality) {
            throw new InvalidContainerRequestException("Cannot submit a "
                + "ContainerRequest asking for location " + location
                + " with locality relaxation " + relaxLocality + " when it has "
                + "already been requested with locality relaxation " + existingRelaxLocality);
          }
        }
      }
  }
  
  /**
   * Valid if a node label expression specified on container request is valid or
   * not
   * 
   * @param containerRequest
   */
  private void checkNodeLabelExpression(T containerRequest) {
    String exp = containerRequest.getNodeLabelExpression();
    
    if (null == exp || exp.isEmpty()) {
      return;
    }

    // Don't support specifying >= 2 node labels in a node label expression now
    if (exp.contains("&&") || exp.contains("||")) {
      throw new InvalidContainerRequestException(
          "Cannot specify more than two node labels"
              + " in a single node label expression");
    }
    
    // Don't allow specify node label against ANY request
    if ((containerRequest.getRacks() != null && 
        (!containerRequest.getRacks().isEmpty()))
        || 
        (containerRequest.getNodes() != null && 
        (!containerRequest.getNodes().isEmpty()))) {
      throw new InvalidContainerRequestException(
          "Cannot specify node label with rack and node");
    }
  }
  
  private void addResourceRequestToAsk(ResourceRequest remoteRequest) {
    // This code looks weird but is needed because of the following scenario.
    // A ResourceRequest is removed from the remoteRequestTable. A 0 container 
    // request is added to 'ask' to notify the RM about not needing it any more.
    // Before the call to allocate, the user now requests more containers. If 
    // the locations of the 0 size request and the new request are the same
    // (with the difference being only container count), then the set comparator
    // will consider both to be the same and not add the new request to ask. So 
    // we need to check for the "same" request being present and remove it and 
    // then add it back. The comparator is container count agnostic.
    // This should happen only rarely but we do need to guard against it.
    if(ask.contains(remoteRequest)) {
      ask.remove(remoteRequest);
    }
    ask.add(remoteRequest);
  }

  private void
      addResourceRequest(Priority priority, String resourceName,
          Resource capability, T req, boolean relaxLocality,
          String labelExpression) {
    Map<String, TreeMap<Resource, ResourceRequestInfo>> remoteRequests =
      this.remoteRequestsTable.get(priority);
    if (remoteRequests == null) {
      remoteRequests = 
          new HashMap<String, TreeMap<Resource, ResourceRequestInfo>>();
      this.remoteRequestsTable.put(priority, remoteRequests);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Added priority=" + priority);
      }
    }
    TreeMap<Resource, ResourceRequestInfo> reqMap = 
                                          remoteRequests.get(resourceName);
    if (reqMap == null) {
      // capabilities are stored in reverse sorted order. smallest last.
      reqMap = new TreeMap<Resource, ResourceRequestInfo>(
          new ResourceReverseMemoryThenCpuComparator());
      remoteRequests.put(resourceName, reqMap);
    }
    ResourceRequestInfo resourceRequestInfo = reqMap.get(capability);
    if (resourceRequestInfo == null) {
      resourceRequestInfo =
          new ResourceRequestInfo(priority, resourceName, capability,
              relaxLocality);
      reqMap.put(capability, resourceRequestInfo);
    }
    
    resourceRequestInfo.remoteRequest.setNumContainers(
         resourceRequestInfo.remoteRequest.getNumContainers() + 1);

    if (relaxLocality) {
      resourceRequestInfo.containerRequests.add(req);
    }
    
    if (ResourceRequest.ANY.equals(resourceName)) {
      resourceRequestInfo.remoteRequest.setNodeLabelExpression(labelExpression);
    }

    // Note this down for next interaction with ResourceManager
    addResourceRequestToAsk(resourceRequestInfo.remoteRequest);

    if (LOG.isDebugEnabled()) {
      LOG.debug("addResourceRequest:" + " applicationId="
          + " priority=" + priority.getPriority()
          + " resourceName=" + resourceName + " numContainers="
          + resourceRequestInfo.remoteRequest.getNumContainers() 
          + " #asks=" + ask.size());
    }
  }

  private void decResourceRequest(Priority priority, 
                                   String resourceName,
                                   Resource capability, 
                                   T req) {
    Map<String, TreeMap<Resource, ResourceRequestInfo>> remoteRequests =
      this.remoteRequestsTable.get(priority);
    
    if(remoteRequests == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Not decrementing resource as priority " + priority 
            + " is not present in request table");
      }
      return;
    }
    
    Map<Resource, ResourceRequestInfo> reqMap = remoteRequests.get(resourceName);
    if (reqMap == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Not decrementing resource as " + resourceName
            + " is not present in request table");
      }
      return;
    }
    ResourceRequestInfo resourceRequestInfo = reqMap.get(capability);

    if (LOG.isDebugEnabled()) {
      LOG.debug("BEFORE decResourceRequest:" + " applicationId="
          + " priority=" + priority.getPriority()
          + " resourceName=" + resourceName + " numContainers="
          + resourceRequestInfo.remoteRequest.getNumContainers() 
          + " #asks=" + ask.size());
    }

    resourceRequestInfo.remoteRequest.setNumContainers(
        resourceRequestInfo.remoteRequest.getNumContainers() - 1);

    resourceRequestInfo.containerRequests.remove(req);
    
    if(resourceRequestInfo.remoteRequest.getNumContainers() < 0) {
      // guard against spurious removals
      resourceRequestInfo.remoteRequest.setNumContainers(0);
    }
    // send the ResourceRequest to RM even if is 0 because it needs to override
    // a previously sent value. If ResourceRequest was not sent previously then
    // sending 0 aught to be a no-op on RM
    addResourceRequestToAsk(resourceRequestInfo.remoteRequest);

    // delete entries from map if no longer needed
    if (resourceRequestInfo.remoteRequest.getNumContainers() == 0) {
      reqMap.remove(capability);
      if (reqMap.size() == 0) {
        remoteRequests.remove(resourceName);
      }
      if (remoteRequests.size() == 0) {
        remoteRequestsTable.remove(priority);
      }
    }

    if (LOG.isDebugEnabled()) {
      LOG.info("AFTER decResourceRequest:" + " applicationId="
          + " priority=" + priority.getPriority()
          + " resourceName=" + resourceName + " numContainers="
          + resourceRequestInfo.remoteRequest.getNumContainers() 
          + " #asks=" + ask.size());
    }
  }

  @Override
  public synchronized void updateBlacklist(List<String> blacklistAdditions,
      List<String> blacklistRemovals) {
    
    if (blacklistAdditions != null) {
      this.blacklistAdditions.addAll(blacklistAdditions);
      this.blacklistedNodes.addAll(blacklistAdditions);
      // if some resources are also in blacklistRemovals updated before, we 
      // should remove them here.
      this.blacklistRemovals.removeAll(blacklistAdditions);
    }
    
    if (blacklistRemovals != null) {
      this.blacklistRemovals.addAll(blacklistRemovals);
      this.blacklistedNodes.removeAll(blacklistRemovals);
      // if some resources are in blacklistAdditions before, we should remove
      // them here.
      this.blacklistAdditions.removeAll(blacklistRemovals);
    }
    
    if (blacklistAdditions != null && blacklistRemovals != null
        && blacklistAdditions.removeAll(blacklistRemovals)) {
      // we allow resources to appear in addition list and removal list in the
      // same invocation of updateBlacklist(), but should get a warn here.
      LOG.warn("The same resources appear in both blacklistAdditions and " +
          "blacklistRemovals in updateBlacklist.");
    }
  }

  private void updateAMRMToken(Token token) throws IOException {
    org.apache.hadoop.security.token.Token<AMRMTokenIdentifier> amrmToken =
        new org.apache.hadoop.security.token.Token<AMRMTokenIdentifier>(token
          .getIdentifier().array(), token.getPassword().array(), new Text(
          token.getKind()), new Text(token.getService()));
    // Preserve the token service sent by the RM when adding the token
    // to ensure we replace the previous token setup by the RM.
    // Afterwards we can update the service address for the RPC layer.
    UserGroupInformation currentUGI = UserGroupInformation.getCurrentUser();
    currentUGI.addToken(amrmToken);
    amrmToken.setService(ClientRMProxy.getAMRMTokenService(getConfig()));
  }
}
