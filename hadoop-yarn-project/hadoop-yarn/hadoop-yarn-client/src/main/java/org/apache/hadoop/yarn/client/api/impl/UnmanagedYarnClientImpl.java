package org.apache.hadoop.yarn.client.api.impl;

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


import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.DataInputByteBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.yarn.api.ApplicationClientProtocol;
import org.apache.hadoop.yarn.api.ApplicationMasterProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationAttemptReportRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationAttemptReportResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationAttemptsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationAttemptsResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationReportRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationReportResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationsResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterMetricsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterMetricsResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterNodeLabelsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterNodesRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterNodesResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetContainerReportRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetContainerReportResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetContainersRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetContainersResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetDelegationTokenRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetDelegationTokenResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetNodesToLabelsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetQueueInfoRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetQueueUserAclsInfoRequest;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.MoveApplicationAcrossQueuesRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.ReservationDeleteRequest;
import org.apache.hadoop.yarn.api.protocolrecords.ReservationDeleteResponse;
import org.apache.hadoop.yarn.api.protocolrecords.ReservationSubmissionRequest;
import org.apache.hadoop.yarn.api.protocolrecords.ReservationSubmissionResponse;
import org.apache.hadoop.yarn.api.protocolrecords.ReservationUpdateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.ReservationUpdateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationRequest;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptReport;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerReport;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.Token;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.api.records.YarnClusterMetrics;
import org.apache.hadoop.yarn.client.ClientRMProxy;
import org.apache.hadoop.yarn.client.api.AHSClient;
import org.apache.hadoop.yarn.client.api.TimelineClient;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.ApplicationIdNotProvidedException;
import org.apache.hadoop.yarn.exceptions.ApplicationNotFoundException;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.security.client.TimelineDelegationTokenIdentifier;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;
import org.apache.hadoop.yarn.util.timeline.TimelineUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

@Private
@Unstable
public class UnmanagedYarnClientImpl extends YarnClient {

  private static final Log LOG = LogFactory.getLog(UnmanagedYarnClientImpl.class);

  protected ApplicationClientProtocol rmClient;
  protected ApplicationMasterProtocol rmMaster;
  private boolean callAMS;
  protected long submitPollIntervalMillis;
  private long asyncApiPollIntervalMillis;
  private long asyncApiPollTimeoutMillis;
  private AHSClient historyClient;
  private boolean historyServiceEnabled;
  protected TimelineClient timelineClient;
  @VisibleForTesting
  Text timelineService;
  @VisibleForTesting
  String timelineDTRenewer;
  protected boolean timelineServiceEnabled;
  
  private static final String ROOT = "root";
  
  protected String appHostName;
  protected int appHostPort;
  protected String appTrackingUrl;

  public UnmanagedYarnClientImpl() {
    super(UnmanagedYarnClientImpl.class.getName());
  }

  @SuppressWarnings("deprecation")
  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    asyncApiPollIntervalMillis =
        conf.getLong(YarnConfiguration.YARN_CLIENT_APPLICATION_CLIENT_PROTOCOL_POLL_INTERVAL_MS,
          YarnConfiguration.DEFAULT_YARN_CLIENT_APPLICATION_CLIENT_PROTOCOL_POLL_INTERVAL_MS);
    asyncApiPollTimeoutMillis =
        conf.getLong(YarnConfiguration.YARN_CLIENT_APPLICATION_CLIENT_PROTOCOL_POLL_TIMEOUT_MS,
            YarnConfiguration.DEFAULT_YARN_CLIENT_APPLICATION_CLIENT_PROTOCOL_POLL_TIMEOUT_MS);
    submitPollIntervalMillis = asyncApiPollIntervalMillis;
    if (conf.get(YarnConfiguration.YARN_CLIENT_APP_SUBMISSION_POLL_INTERVAL_MS)
        != null) {
      submitPollIntervalMillis = conf.getLong(
        YarnConfiguration.YARN_CLIENT_APP_SUBMISSION_POLL_INTERVAL_MS,
        YarnConfiguration.DEFAULT_YARN_CLIENT_APPLICATION_CLIENT_PROTOCOL_POLL_INTERVAL_MS);
    }

    if (conf.getBoolean(YarnConfiguration.APPLICATION_HISTORY_ENABLED,
      YarnConfiguration.DEFAULT_APPLICATION_HISTORY_ENABLED)) {
      historyServiceEnabled = true;
      historyClient = AHSClient.createAHSClient();
      historyClient.init(conf);
    }

    if (conf.getBoolean(YarnConfiguration.TIMELINE_SERVICE_ENABLED,
        YarnConfiguration.DEFAULT_TIMELINE_SERVICE_ENABLED)) {
      timelineServiceEnabled = true;
      timelineClient = TimelineClient.createTimelineClient();
      timelineClient.init(conf);
      timelineDTRenewer = getTimelineDelegationTokenRenewer(conf);
      timelineService = TimelineUtils.buildTimelineTokenService(conf);
    }
    super.serviceInit(conf);
  }

  @Override
  protected void serviceStart() throws Exception {
    try {
    	rmClient = ClientRMProxy.createRMProxyRemote(getConfig(),
    			ApplicationClientProtocol.class);
    	
    	callAMS = getConfig().getBoolean("yarn.callAMS", true);
    	LOG.info("callAMS: " + callAMS);
    	if(callAMS){
        	rmMaster = ClientRMProxy.createRMProxyRemote(getConfig(), 
        			ApplicationMasterProtocol.class);
        	LOG.info("rmMaster create success");
    	}

      if (historyServiceEnabled) {
        historyClient.start();
      }
      if (timelineServiceEnabled) {
        timelineClient.start();
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
    if(callAMS && this.rmMaster != null){
    	RPC.stopProxy(this.rmMaster);
    }
    if (historyServiceEnabled) {
      historyClient.stop();
    }
    if (timelineServiceEnabled) {
      timelineClient.stop();
    }
    super.serviceStop();
  }

  private GetNewApplicationResponse getNewApplication()
      throws YarnException, IOException {
    GetNewApplicationRequest request =
        Records.newRecord(GetNewApplicationRequest.class);
    return rmClient.getNewApplication(request);
  }

  @Override
  public YarnClientApplication createApplication()
      throws YarnException, IOException {
    ApplicationSubmissionContext context = Records.newRecord
        (ApplicationSubmissionContext.class);
    GetNewApplicationResponse newApp = getNewApplication();
    ApplicationId appId = newApp.getApplicationId();
    context.setApplicationId(appId);
    return new YarnClientApplication(newApp, context);
  }

  @Override
  public ApplicationId
      submitApplication(ApplicationSubmissionContext appContext)
          throws YarnException, IOException {
	  //LOG.info("UAMcontext: " + appContext.toString());
	  //LOG.info("queue: " + appContext.getQueue());
	  //LOG.info("Priority: " + appContext.getPriority().toString());
	 // LOG.info("Type: " + appContext.getApplicationType());
    ApplicationId applicationId = appContext.getApplicationId();
    if (applicationId == null) {
      throw new ApplicationIdNotProvidedException(
          "ApplicationId is not provided in ApplicationSubmissionContext");
    }
    SubmitApplicationRequest request =
        Records.newRecord(SubmitApplicationRequest.class);
    request.setApplicationSubmissionContext(appContext);

    // Automatically add the timeline DT into the CLC
    // Only when the security and the timeline service are both enabled
    if (isSecurityEnabled() && timelineServiceEnabled) {
      addTimelineDelegationToken(appContext.getAMContainerSpec());
    }

    //TODO: YARN-1763:Handle RM failovers during the submitApplication call.
    rmClient.submitApplication(request);

    int pollCount = 0;
    long startTime = System.currentTimeMillis();

    while (true) {
      try {
        YarnApplicationState state =
            getApplicationReport(applicationId).getYarnApplicationState();
        if (!state.equals(YarnApplicationState.NEW) &&
            !state.equals(YarnApplicationState.NEW_SAVING)) {
          LOG.info("Submitted application " + applicationId);
          break;
        }

        long elapsedMillis = System.currentTimeMillis() - startTime;
        if (enforceAsyncAPITimeout() &&
            elapsedMillis >= asyncApiPollTimeoutMillis) {
          throw new YarnException("Timed out while waiting for application " +
              applicationId + " to be submitted successfully");
        }

        // Notify the client through the log every 10 poll, in case the client
        // is blocked here too long.
        if (++pollCount % 10 == 0) {
          LOG.info("Application submission is not finished, " +
              "submitted application " + applicationId +
              " is still in " + state);
        }
        try {
          Thread.sleep(submitPollIntervalMillis);
        } catch (InterruptedException ie) {
          LOG.error("Interrupted while waiting for application "
              + applicationId
              + " to be successfully submitted.");
        }
      } catch (ApplicationNotFoundException ex) {
        // FailOver or RM restart happens before RMStateStore saves
        // ApplicationState
        LOG.info("Re-submit application " + applicationId + "with the " +
            "same ApplicationSubmissionContext");
        rmClient.submitApplication(request);
      }
    }

    return applicationId;
  }

  private void addTimelineDelegationToken(
      ContainerLaunchContext clc) throws YarnException, IOException {
    Credentials credentials = new Credentials();
    DataInputByteBuffer dibb = new DataInputByteBuffer();
    ByteBuffer tokens = clc.getTokens();
    if (tokens != null) {
      dibb.reset(tokens);
      credentials.readTokenStorageStream(dibb);
      tokens.rewind();
    }
    // If the timeline delegation token is already in the CLC, no need to add
    // one more
    for (org.apache.hadoop.security.token.Token<? extends TokenIdentifier> token : credentials
        .getAllTokens()) {
      if (token.getKind().equals(TimelineDelegationTokenIdentifier.KIND_NAME)) {
        return;
      }
    }
    org.apache.hadoop.security.token.Token<TimelineDelegationTokenIdentifier>
        timelineDelegationToken = getTimelineDelegationToken();
    if (timelineDelegationToken == null) {
      return;
    }
    credentials.addToken(timelineService, timelineDelegationToken);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Add timline delegation token into credentials: "
          + timelineDelegationToken);
    }
    DataOutputBuffer dob = new DataOutputBuffer();
    credentials.writeTokenStorageToStream(dob);
    tokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());
    clc.setTokens(tokens);
  }

  @VisibleForTesting
  org.apache.hadoop.security.token.Token<TimelineDelegationTokenIdentifier>
      getTimelineDelegationToken() throws IOException, YarnException {
    return timelineClient.getDelegationToken(timelineDTRenewer);
  }

  private static String getTimelineDelegationTokenRenewer(Configuration conf)
      throws IOException, YarnException  {
    // Parse the RM daemon user if it exists in the config
    String rmPrincipal = conf.get(YarnConfiguration.RM_PRINCIPAL);
    String renewer = null;
    if (rmPrincipal != null && rmPrincipal.length() > 0) {
      String rmHost = conf.getSocketAddr(
          YarnConfiguration.RM_ADDRESS,
          YarnConfiguration.DEFAULT_RM_ADDRESS,
          YarnConfiguration.DEFAULT_RM_PORT).getHostName();
      renewer = SecurityUtil.getServerPrincipal(rmPrincipal, rmHost);
    }
    return renewer;
  }

  @Private
  @VisibleForTesting
  protected boolean isSecurityEnabled() {
    return UserGroupInformation.isSecurityEnabled();
  }

  @Override
  public void killApplication(ApplicationId applicationId)
      throws YarnException, IOException {
    KillApplicationRequest request =
        Records.newRecord(KillApplicationRequest.class);
    request.setApplicationId(applicationId);

    try {
      int pollCount = 0;
      long startTime = System.currentTimeMillis();

      while (true) {
        KillApplicationResponse response =
            rmClient.forceKillApplication(request);
        if (response.getIsKillCompleted()) {
          LOG.info("Killed application " + applicationId);
          break;
        }

        long elapsedMillis = System.currentTimeMillis() - startTime;
        if (enforceAsyncAPITimeout() &&
            elapsedMillis >= this.asyncApiPollTimeoutMillis) {
          throw new YarnException("Timed out while waiting for application " +
            applicationId + " to be killed.");
        }

        if (++pollCount % 10 == 0) {
          LOG.info("Waiting for application " + applicationId + " to be killed.");
        }
        Thread.sleep(asyncApiPollIntervalMillis);
      }
    } catch (InterruptedException e) {
      LOG.error("Interrupted while waiting for application " + applicationId
          + " to be killed.");
    }
  }

  @VisibleForTesting
  boolean enforceAsyncAPITimeout() {
    return asyncApiPollTimeoutMillis >= 0;
  }

  @Override
  public ApplicationReport getApplicationReport(ApplicationId appId)
      throws YarnException, IOException {
    GetApplicationReportResponse response = null;
    try {
      GetApplicationReportRequest request = Records
          .newRecord(GetApplicationReportRequest.class);
      request.setApplicationId(appId);
      response = rmClient.getApplicationReport(request);
    } catch (YarnException e) {
      if (!historyServiceEnabled) {
        // Just throw it as usual if historyService is not enabled.
        throw e;
      }
      // Even if history-service is enabled, treat all exceptions still the same
      // except the following
      if (!(e.getClass() == ApplicationNotFoundException.class)) {
        throw e;
      }
      return historyClient.getApplicationReport(appId);
    }
    return response.getApplicationReport();
  }

  public org.apache.hadoop.security.token.Token<AMRMTokenIdentifier>
      getAMRMToken(ApplicationId appId) throws YarnException, IOException {
    Token token = getApplicationReport(appId).getAMRMToken();
    org.apache.hadoop.security.token.Token<AMRMTokenIdentifier> amrmToken =
        null;
    if (token != null) {
      amrmToken = ConverterUtils.convertFromYarn(token, (Text) null);
    }
    return amrmToken;
  }

  @Override
  public List<ApplicationReport> getApplications() throws YarnException,
      IOException {
    return getApplications(null, null);
  }

  @Override
  public List<ApplicationReport> getApplications(Set<String> applicationTypes)
      throws YarnException,
      IOException {
    return getApplications(applicationTypes, null);
  }

  @Override
  public List<ApplicationReport> getApplications(
      EnumSet<YarnApplicationState> applicationStates)
      throws YarnException, IOException {
    return getApplications(null, applicationStates);
  }

  @Override
  public List<ApplicationReport> getApplications(Set<String> applicationTypes,
      EnumSet<YarnApplicationState> applicationStates) throws YarnException,
      IOException {
    GetApplicationsRequest request =
        GetApplicationsRequest.newInstance(applicationTypes, applicationStates);
    GetApplicationsResponse response = rmClient.getApplications(request);
    return response.getApplicationList();
  }

  @Override
  public YarnClusterMetrics getYarnClusterMetrics() throws YarnException,
      IOException {
    GetClusterMetricsRequest request =
        Records.newRecord(GetClusterMetricsRequest.class);
    GetClusterMetricsResponse response = rmClient.getClusterMetrics(request);
    return response.getClusterMetrics();
  }

  @Override
  public List<NodeReport> getNodeReports(NodeState... states) throws YarnException,
      IOException {
    EnumSet<NodeState> statesSet = (states.length == 0) ?
        EnumSet.allOf(NodeState.class) : EnumSet.noneOf(NodeState.class);
    for (NodeState state : states) {
      statesSet.add(state);
    }
    GetClusterNodesRequest request = GetClusterNodesRequest
        .newInstance(statesSet);
    GetClusterNodesResponse response = rmClient.getClusterNodes(request);
    return response.getNodeReports();
  }

  @Override
  public Token getRMDelegationToken(Text renewer)
      throws YarnException, IOException {
    /* get the token from RM */
    GetDelegationTokenRequest rmDTRequest =
        Records.newRecord(GetDelegationTokenRequest.class);
    rmDTRequest.setRenewer(renewer.toString());
    GetDelegationTokenResponse response =
        rmClient.getDelegationToken(rmDTRequest);
    return response.getRMDelegationToken();
  }


  private GetQueueInfoRequest
      getQueueInfoRequest(String queueName, boolean includeApplications,
          boolean includeChildQueues, boolean recursive) {
    GetQueueInfoRequest request = Records.newRecord(GetQueueInfoRequest.class);
    request.setQueueName(queueName);
    request.setIncludeApplications(includeApplications);
    request.setIncludeChildQueues(includeChildQueues);
    request.setRecursive(recursive);
    return request;
  }

  @Override
  public QueueInfo getQueueInfo(String queueName) throws YarnException,
      IOException {
    GetQueueInfoRequest request =
        getQueueInfoRequest(queueName, true, false, false);
    Records.newRecord(GetQueueInfoRequest.class);
    return rmClient.getQueueInfo(request).getQueueInfo();
  }

  @Override
  public List<QueueUserACLInfo> getQueueAclsInfo() throws YarnException,
      IOException {
    GetQueueUserAclsInfoRequest request =
        Records.newRecord(GetQueueUserAclsInfoRequest.class);
    return rmClient.getQueueUserAcls(request).getUserAclsInfoList();
  }

  @Override
  public List<QueueInfo> getAllQueues() throws YarnException,
      IOException {
    List<QueueInfo> queues = new ArrayList<QueueInfo>();

    QueueInfo rootQueue =
        rmClient.getQueueInfo(getQueueInfoRequest(ROOT, false, true, true))
          .getQueueInfo();
    getChildQueues(rootQueue, queues, true);
    return queues;
  }

  @Override
  public List<QueueInfo> getRootQueueInfos() throws YarnException,
      IOException {
    List<QueueInfo> queues = new ArrayList<QueueInfo>();

    QueueInfo rootQueue =
        rmClient.getQueueInfo(getQueueInfoRequest(ROOT, false, true, true))
          .getQueueInfo();
    getChildQueues(rootQueue, queues, false);
    return queues;
  }

  @Override
  public List<QueueInfo> getChildQueueInfos(String parent)
      throws YarnException, IOException {
    List<QueueInfo> queues = new ArrayList<QueueInfo>();

    QueueInfo parentQueue =
        rmClient.getQueueInfo(getQueueInfoRequest(parent, false, true, false))
          .getQueueInfo();
    getChildQueues(parentQueue, queues, true);
    return queues;
  }

  private void getChildQueues(QueueInfo parent, List<QueueInfo> queues,
      boolean recursive) {
    List<QueueInfo> childQueues = parent.getChildQueues();

    for (QueueInfo child : childQueues) {
      queues.add(child);
      if (recursive) {
        getChildQueues(child, queues, recursive);
      }
    }
  }

  @Private
  @VisibleForTesting
  public void setRMClient(ApplicationClientProtocol rmClient) {
    this.rmClient = rmClient;
  }

  @Override
  public ApplicationAttemptReport getApplicationAttemptReport(
      ApplicationAttemptId appAttemptId) throws YarnException, IOException {
    try {
      GetApplicationAttemptReportRequest request = Records
          .newRecord(GetApplicationAttemptReportRequest.class);
      request.setApplicationAttemptId(appAttemptId);
      GetApplicationAttemptReportResponse response = rmClient
          .getApplicationAttemptReport(request);
      return response.getApplicationAttemptReport();
    } catch (YarnException e) {
      if (!historyServiceEnabled) {
        // Just throw it as usual if historyService is not enabled.
        throw e;
      }
      // Even if history-service is enabled, treat all exceptions still the same
      // except the following
      if (e.getClass() != ApplicationNotFoundException.class) {
        throw e;
      }
      return historyClient.getApplicationAttemptReport(appAttemptId);
    }
  }

  @Override
  public List<ApplicationAttemptReport> getApplicationAttempts(
      ApplicationId appId) throws YarnException, IOException {
    try {
      GetApplicationAttemptsRequest request = Records
          .newRecord(GetApplicationAttemptsRequest.class);
      request.setApplicationId(appId);
      GetApplicationAttemptsResponse response = rmClient
          .getApplicationAttempts(request);
      return response.getApplicationAttemptList();
    } catch (YarnException e) {
      if (!historyServiceEnabled) {
        // Just throw it as usual if historyService is not enabled.
        throw e;
      }
      // Even if history-service is enabled, treat all exceptions still the same
      // except the following
      if (e.getClass() != ApplicationNotFoundException.class) {
        throw e;
      }
      return historyClient.getApplicationAttempts(appId);
    }
  }

  @Override
  public ContainerReport getContainerReport(ContainerId containerId)
      throws YarnException, IOException {
    try {
      GetContainerReportRequest request = Records
          .newRecord(GetContainerReportRequest.class);
      request.setContainerId(containerId);
      GetContainerReportResponse response = rmClient
          .getContainerReport(request);
      return response.getContainerReport();
    } catch (YarnException e) {
      if (!historyServiceEnabled) {
        // Just throw it as usual if historyService is not enabled.
        throw e;
      }
      // Even if history-service is enabled, treat all exceptions still the same
      // except the following
      if (e.getClass() != ApplicationNotFoundException.class) {
        throw e;
      }
      return historyClient.getContainerReport(containerId);
    }
  }

  @Override
  public List<ContainerReport> getContainers(
      ApplicationAttemptId applicationAttemptId) throws YarnException,
      IOException {
    try {
      GetContainersRequest request = Records
          .newRecord(GetContainersRequest.class);
      request.setApplicationAttemptId(applicationAttemptId);
      GetContainersResponse response = rmClient.getContainers(request);
      return response.getContainerList();
    } catch (YarnException e) {
      if (!historyServiceEnabled) {
        // Just throw it as usual if historyService is not enabled.
        throw e;
      }
      // Even if history-service is enabled, treat all exceptions still the same
      // except the following
      if (e.getClass() != ApplicationNotFoundException.class) {
        throw e;
      }
      return historyClient.getContainers(applicationAttemptId);
    }
  }

  @Override
  public void moveApplicationAcrossQueues(ApplicationId appId,
      String queue) throws YarnException, IOException {
    MoveApplicationAcrossQueuesRequest request =
        MoveApplicationAcrossQueuesRequest.newInstance(appId, queue);
    rmClient.moveApplicationAcrossQueues(request);
  }

  @Override
  public ReservationSubmissionResponse submitReservation(
      ReservationSubmissionRequest request) throws YarnException, IOException {
    return rmClient.submitReservation(request);
  }

  @Override
  public ReservationUpdateResponse updateReservation(
      ReservationUpdateRequest request) throws YarnException, IOException {
    return rmClient.updateReservation(request);
  }

  @Override
  public ReservationDeleteResponse deleteReservation(
      ReservationDeleteRequest request) throws YarnException, IOException {
    return rmClient.deleteReservation(request);
  }
  
  @Override
  public Map<NodeId, Set<String>> getNodeToLabels() throws YarnException,
      IOException {
    return rmClient.getNodeToLabels(GetNodesToLabelsRequest.newInstance())
        .getNodeToLabels();
  }

  @Override
  public Set<String> getClusterNodeLabels() throws YarnException, IOException {
    return rmClient.getClusterNodeLabels(
        GetClusterNodeLabelsRequest.newInstance()).getNodeLabels();
  }
  
  public AllocateResponse allocate(AllocateRequest request)  throws YarnException, IOException{
	  if(callAMS){
		  LOG.info("AllocateRequest to AMS");
		  return rmMaster.allocate(request);
	  }else {
		  LOG.info("AllocateRequest to ACS");
		  return rmClient.allocate(request);
	}
  }
  
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
	    
	    LOG.info("Regist AM for host: " + appHostName);
	    RegisterApplicationMasterRequest request =
	            RegisterApplicationMasterRequest.newInstance(this.appHostName,
	                this.appHostPort, this.appTrackingUrl);
	        
	        RegisterApplicationMasterResponse response = rmMaster.registerApplicationMaster(request);
	        LOG.info("regist AM over" );
	        return response;
	  }
  
  public void finishApplicationMaster() throws YarnException, IOException{
	  FinishApplicationMasterRequest request =  FinishApplicationMasterRequest.newInstance(
	          FinalApplicationStatus.SUCCEEDED, "", "");
	  rmMaster.finishApplicationMaster(request);
	  
  }
  
}
