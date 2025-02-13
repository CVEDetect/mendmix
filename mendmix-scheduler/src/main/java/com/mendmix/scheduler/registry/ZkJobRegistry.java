/*
 * Copyright 2016-2022 www.mendmix.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mendmix.scheduler.registry;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkConnection;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import com.mendmix.common.GlobalRuntimeContext;
import com.mendmix.common.util.JsonUtils;
import com.mendmix.common.util.ResourceUtils;
import com.mendmix.scheduler.JobContext;
import com.mendmix.scheduler.model.JobConfig;
import com.mendmix.scheduler.monitor.MonitorCommond;

/**
 * 
 * @description <br>
 * @author <a href="mailto:vakinge@gmail.com">vakin</a>
 * @date 2016年5月3日
 */
public class ZkJobRegistry extends AbstarctJobRegistry implements InitializingBean, DisposableBean {

	private static final Logger logger = LoggerFactory.getLogger("com.mendmix.scheduler.registry");

	public static final String ROOT = String.format("/applications/%s/", GlobalRuntimeContext.ENV);

	private String zkServers;

	private ZkClient zkClient;

	private String groupPath;

	private String nodeStateParentPath;

	private ScheduledExecutorService zkCheckTask;

	private volatile boolean zkAvailabled = true;

	public void setZkServers(String zkServers) {
		this.zkServers = zkServers;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
        if(ResourceUtils.getBoolean("mendmix.task.registry.disabled", false)){
        	return;
        }
		ZkConnection zkConnection = new ZkConnection(zkServers);
		zkClient = new ZkClient(zkConnection, 10000);
		//
		zkCheckTask = Executors.newScheduledThreadPool(1);

		zkCheckTask.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				if (schedulerConfgs.isEmpty())
					return;
				List<String> activeNodes = null;
				try {
					activeNodes = zkClient.getChildren(nodeStateParentPath);
					zkAvailabled = true;
				} catch (Exception e) {
					checkZkAvailabled();
					activeNodes = new ArrayList<>(JobContext.getContext().getActiveNodes());
				}

				if (!activeNodes.contains(JobContext.getContext().getNodeId())) {
					zkClient.createEphemeral(nodeStateParentPath + "/" + JobContext.getContext().getNodeId());
					logger.info("MENDMIX-TRACE-LOGGGING-->> node[{}] re-join task clusters", JobContext.getContext().getNodeId());
				}
				// 对节点列表排序
				Collections.sort(activeNodes);
				// 本地缓存的所有jobs
				Collection<JobConfig> jobConfigs = schedulerConfgs.values();
				//
				for (JobConfig jobConfig : jobConfigs) {
					// 如果本地任务指定的执行节点不在当前实际的节点列表，重新指定
					if (!activeNodes.contains(jobConfig.getCurrentNodeId())) {
						// 指定当前节点为排序后的第一个节点
						String newExecuteNodeId = activeNodes.get(0);
						jobConfig.setCurrentNodeId(newExecuteNodeId);
						logger.warn("MENDMIX-TRACE-LOGGGING-->> Job[{}-{}] currentNodeId[{}] not in activeNodeList, assign new ExecuteNodeId:{}",
								jobConfig.getGroupName(), jobConfig.getJobName(), jobConfig.getCurrentNodeId(),
								newExecuteNodeId);
					}
				}
			}
		}, 60, 30, TimeUnit.SECONDS);
	}

	@Override
	public synchronized void register(JobConfig conf) {
		// 是否第一个启动节点
		boolean isFirstNode = false;

		Calendar now = Calendar.getInstance();
		long currentTimeMillis = now.getTimeInMillis();
		conf.setModifyTime(currentTimeMillis);

		if (groupPath == null) {
			groupPath = ROOT + conf.getGroupName() + "/schedulers";
		}
		if (nodeStateParentPath == null) {
			nodeStateParentPath = ROOT + conf.getGroupName() + "/nodes";
		}

		String path = getPath(conf);
		final String jobName = conf.getJobName();
		if (!zkClient.exists(groupPath)) {
			zkClient.createPersistent(groupPath, true);
		}
		if (!zkClient.exists(nodeStateParentPath)) {
			isFirstNode = true;
			zkClient.createPersistent(nodeStateParentPath, true);
		} else {
			// 检查是否有节点
			if (!isFirstNode) {
				isFirstNode = zkClient.getChildren(nodeStateParentPath).size() == 0;
			}
		}
		if (!zkClient.exists(path)) {
			zkClient.createPersistent(path, true);
		}

		// 是否要更新ZK的conf配置
		boolean updateConfInZK = isFirstNode;
		if (!updateConfInZK) {
			JobConfig configFromZK = getConfigFromZK(path, null);
			if (configFromZK != null) {
				// 1.当前执行时间策略变化了
				// 2.下一次执行时间在当前时间之前
				// 3.配置文件修改是30分钟前
				if (!StringUtils.equals(configFromZK.getCronExpr(), conf.getCronExpr())) {
					updateConfInZK = true;
				} else if (configFromZK.getNextFireTime() != null
						&& configFromZK.getNextFireTime().before(now.getTime())) {
					updateConfInZK = true;
				} else if (currentTimeMillis - configFromZK.getModifyTime() > TimeUnit.MINUTES.toMillis(30)) {
					updateConfInZK = true;
				} else {
					if (!JobContext.getContext().getNodeId().equals(configFromZK.getCurrentNodeId())) {
						List<String> nodes = zkClient.getChildren(nodeStateParentPath);
						updateConfInZK = !nodes.contains(configFromZK.getCurrentNodeId());
					}
				}
			} else {
				// zookeeper 该job不存在？
				updateConfInZK = true;
			}
			// 拿ZK上的配置覆盖当前的
			if (!updateConfInZK) {
				conf = configFromZK;
			}
		}

		if (updateConfInZK) {
			conf.setCurrentNodeId(JobContext.getContext().getNodeId());
			zkClient.writeData(path, JsonUtils.toJson(conf));
		}
		schedulerConfgs.put(conf.getJobName(), conf);
		// 订阅同步信息变化
		zkClient.subscribeDataChanges(path, new IZkDataListener() {
			@Override
			public void handleDataDeleted(String dataPath) throws Exception {
				schedulerConfgs.remove(jobName);
			}

			@Override
			public void handleDataChange(String dataPath, Object data) throws Exception {
				if (data == null)
					return;
				JobConfig _jobConfig = JsonUtils.toObject(data.toString(), JobConfig.class);
				schedulerConfgs.put(jobName, _jobConfig);
			}
		});
		//
		logger.info("MENDMIX-TRACE-LOGGGING-->> finish register schConfig:{}",
				ToStringBuilder.reflectionToString(conf, ToStringStyle.MULTI_LINE_STYLE));
	}

	/**
	 * 订阅节点事件
	 * 
	 * @return
	 */
	private synchronized void regAndSubscribeNodeEvent() {
		// 创建node节点
		final String serverNodePath = nodeStateParentPath + "/" + JobContext.getContext().getNodeId();
		try {			
			zkClient.createEphemeral(serverNodePath);
		} catch (ZkNodeExistsException e) {
			zkClient.delete(serverNodePath);
			zkClient.createEphemeral(serverNodePath);
		}

		// 订阅节点信息变化
		zkClient.subscribeChildChanges(nodeStateParentPath, new IZkChildListener() {
			@Override
			public void handleChildChange(String parentPath, List<String> currentChilds) throws Exception {
				//
				if (currentChilds == null || !currentChilds.contains(JobContext.getContext().getNodeId())) {
					zkClient.createEphemeral(serverNodePath);
					logger.info("MENDMIX-TRACE-LOGGGING-->> Nodelist is empty~ node[{}] re-join task clusters",
							JobContext.getContext().getNodeId());
					return;
				}
				logger.info("MENDMIX-TRACE-LOGGGING-->> nodes changed ,nodes:{}", currentChilds);
				// 分配节点
				rebalanceJobNode(currentChilds);
				// 刷新当前可用节点
				JobContext.getContext().refreshNodes(currentChilds);
			}
		});

		logger.info("MENDMIX-TRACE-LOGGGING-->> ", nodeStateParentPath);
		// 注册命令事件
		registerCommondEvent();
		logger.info("MENDMIX-TRACE-LOGGGING-->> subscribe command event at path:{}",
				serverNodePath);
		// 刷新节点列表
		List<String> activeNodes = zkClient.getChildren(nodeStateParentPath);
		JobContext.getContext().refreshNodes(activeNodes);

		logger.info("MENDMIX-TRACE-LOGGGING-->> current activeNodes:{}", activeNodes);
	}


	private synchronized JobConfig getConfigFromZK(String path, Stat stat) {
		Object data = stat == null ? zkClient.readData(path) : zkClient.readData(path, stat);
		return data == null ? null : JsonUtils.toObject(data.toString(), JobConfig.class);
	}

	@Override
	public synchronized JobConfig getConf(String jobName, boolean forceRemote) {
		JobConfig config = schedulerConfgs.get(jobName);

		if (forceRemote) {
			// 如果只有一个节点就不从强制同步了
			if (JobContext.getContext().getActiveNodes().size() == 1) {
				config.setCurrentNodeId(JobContext.getContext().getNodeId());
				return config;
			}
			String path = getPath(config);
			try {
				config = getConfigFromZK(path, null);
			} catch (Exception e) {
				checkZkAvailabled();
				logger.warn("MENDMIX-TRACE-LOGGGING-->> fecth JobConfig from Registry error", e);
			}
		}
		return config;
	}

	@Override
	public synchronized void unregister(String jobName) {
		JobConfig config = schedulerConfgs.get(jobName);

		String path = getPath(config);

		if (zkClient.getChildren(nodeStateParentPath).size() == 1) {
			zkClient.delete(path);
			logger.info("MENDMIX-TRACE-LOGGGING-->> all node is closed ,delete path:" + path);
		}
	}

	private String getPath(JobConfig config) {
		return ROOT + config.getGroupName() + "/" + config.getJobName();
	}

	@Override
	public void destroy() throws Exception {
		try {zkCheckTask.shutdown();} catch (Exception e) {}
		try {zkClient.close();} catch (Exception e) {}
		
	}

	@Override
	public void setRuning(String jobName, Date fireTime) {
		updatingStatus = false;
		try {
			JobConfig config = getConf(jobName, false);
			config.setRunning(true);
			config.setLastFireTime(fireTime);
			config.setModifyTime(Calendar.getInstance().getTimeInMillis());
			config.setErrorMsg(null);
			// 更新本地
			schedulerConfgs.put(jobName, config);
			try {
				if (zkAvailabled)
					zkClient.writeData(getPath(config), JsonUtils.toJson(config));
			} catch (Exception e) {
				checkZkAvailabled();
				logger.warn(String.format("MENDMIX-TRACE-LOGGGING-->> Job[{}] setRuning error...", jobName), e);
			}
		} finally {
			updatingStatus = false;
		}
	}

	@Override
	public void setStoping(String jobName, Date nextFireTime, Exception e) {
		updatingStatus = false;
		try {
			JobConfig config = getConf(jobName, false);
			config.setRunning(false);
			config.setNextFireTime(nextFireTime);
			config.setModifyTime(Calendar.getInstance().getTimeInMillis());
			config.setErrorMsg(e == null ? null : e.getMessage());
			// 更新本地
			schedulerConfgs.put(jobName, config);
			try {
				if (zkAvailabled)
					zkClient.writeData(getPath(config), JsonUtils.toJson(config));
			} catch (Exception ex) {
				checkZkAvailabled();
				logger.warn(String.format("MENDMIX-TRACE-LOGGGING-->> Job[{}] setStoping error...", jobName), ex);
			}
		} finally {
			updatingStatus = false;
		}

	}

	@Override
	public List<JobConfig> getAllJobs() {
		return new ArrayList<>(schedulerConfgs.values());
	}

	private boolean checkZkAvailabled() {
		try {
			zkClient.exists(ROOT);
			zkAvailabled = true;
		} catch (Exception e) {
			zkAvailabled = false;
			logger.warn("MENDMIX-TRACE-LOGGGING-->> ZK server is not available....");
		}
		return zkAvailabled;
	}

	@Override
	public void updateJobConfig(JobConfig config) {
		config.setModifyTime(Calendar.getInstance().getTimeInMillis());
		zkClient.writeData(getPath(config), JsonUtils.toJson(config));
		schedulerConfgs.put(config.getJobName(), config);
	}

	@Override
	public void onRegistered() {
		// 注册订阅事件
		regAndSubscribeNodeEvent();
		List<String> jobs = zkClient.getChildren(groupPath);

		List<String> registerJobs = new ArrayList<>(JobContext.getContext().getAllJobs().keySet());
		String groupName = JobContext.getContext().getGroupName();
		String jobPath;
		for (String job : jobs) {
			if (job.equals("nodes"))
				continue;
			if (registerJobs.contains(groupName + ":" + job))
				continue;
			jobPath = groupPath + "/" + job;
			zkClient.delete(jobPath);
		}

	}

	private void registerCommondEvent() {
		String path = nodeStateParentPath + "/" + JobContext.getContext().getNodeId();
		zkClient.subscribeDataChanges(path, new IZkDataListener() {
			@Override
			public void handleDataDeleted(String dataPath) throws Exception {
			}

			@Override
			public void handleDataChange(String dataPath, Object data) throws Exception {
				MonitorCommond cmd = (MonitorCommond) data;
				if (cmd != null) {
					logger.info("MENDMIX-TRACE-LOGGGING-->> 收到commond:" + cmd.toString());
					execCommond(cmd);
				}
			}
		});
	}

}
