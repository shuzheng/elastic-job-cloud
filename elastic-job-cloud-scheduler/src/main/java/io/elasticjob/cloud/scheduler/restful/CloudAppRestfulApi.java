/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.elasticjob.cloud.scheduler.restful;

import io.elasticjob.cloud.scheduler.config.app.CloudAppConfigurationGsonFactory;
import io.elasticjob.cloud.scheduler.mesos.MesosStateService;
import io.elasticjob.cloud.scheduler.state.disable.app.DisableAppService;
import io.elasticjob.cloud.scheduler.config.app.CloudAppConfiguration;
import io.elasticjob.cloud.scheduler.config.app.CloudAppConfigurationService;
import io.elasticjob.cloud.scheduler.config.job.CloudJobConfiguration;
import io.elasticjob.cloud.scheduler.config.job.CloudJobConfigurationService;
import io.elasticjob.cloud.scheduler.producer.ProducerManager;
import io.elasticjob.cloud.exception.AppConfigurationException;
import io.elasticjob.cloud.exception.JobSystemException;
import io.elasticjob.cloud.reg.base.CoordinatorRegistryCenter;
import io.elasticjob.cloud.util.json.GsonFactory;
import com.google.common.base.Optional;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.SlaveID;
import org.codehaus.jettison.json.JSONException;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Collection;

import static javax.ws.rs.core.Response.Status.NOT_FOUND;

/**
 * 云作业应用的REST API.
 *
 * @author caohao
 */
@Path("/app")
public final class CloudAppRestfulApi {
    
    private static CoordinatorRegistryCenter regCenter;
    
    private static ProducerManager producerManager;
    
    private final CloudAppConfigurationService appConfigService;
    
    private final CloudJobConfigurationService jobConfigService;
    
    private final DisableAppService disableAppService;
    
    private final MesosStateService mesosStateService;
    
    public CloudAppRestfulApi() {
        appConfigService = new CloudAppConfigurationService(regCenter);
        jobConfigService = new CloudJobConfigurationService(regCenter);
        mesosStateService = new MesosStateService(regCenter);
        disableAppService = new DisableAppService(regCenter);
    }
    
    /**
     * 初始化.
     *
     * @param producerManager 生产管理器
     * @param regCenter 注册中心
     */
    public static void init(final CoordinatorRegistryCenter regCenter, final ProducerManager producerManager) {
        CloudAppRestfulApi.regCenter = regCenter;
        CloudAppRestfulApi.producerManager = producerManager;
        GsonFactory.registerTypeAdapter(CloudAppConfiguration.class, new CloudAppConfigurationGsonFactory.CloudAppConfigurationGsonTypeAdapter());
    }
    
    /**
     * 注册应用配置.
     * 
     * @param appConfig 应用配置
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void register(final CloudAppConfiguration appConfig) {
        Optional<CloudAppConfiguration> appConfigFromZk = appConfigService.load(appConfig.getAppName());
        if (appConfigFromZk.isPresent()) {
            throw new AppConfigurationException("app '%s' already existed.", appConfig.getAppName());
        }
        appConfigService.add(appConfig);
    }
    
    /**
     * 更新应用配置.
     *
     * @param appConfig 应用配置
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public void update(final CloudAppConfiguration appConfig) {
        appConfigService.update(appConfig);
    }
    
    /**
     * 查询应用配置.
     *
     * @param appName 应用配置名称
     * @return 应用配置
     */
    @GET
    @Path("/{appName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response detail(@PathParam("appName") final String appName) {
        Optional<CloudAppConfiguration> appConfig = appConfigService.load(appName);
        if (!appConfig.isPresent()) {
            return Response.status(NOT_FOUND).build();
        }
        return Response.ok(appConfig.get()).build();
    }
    
    /**
     * 查询全部应用配置.
     * 
     * @return 全部应用配置
     */
    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<CloudAppConfiguration> findAllApps() {
        return appConfigService.loadAll();
    }
    
    /**
     * 查询应用是否被禁用.
     * 
     * @param appName 应用名称
     * @return 应用是否被禁用
     * @throws JSONException JSON解析异常
     */
    @GET
    @Path("/{appName}/disable")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isDisabled(@PathParam("appName") final String appName) throws JSONException {
        return disableAppService.isDisabled(appName);
    }
    
    /**
     * 禁用应用.
     *
     * @param appName 应用名称
     */
    @POST
    @Path("/{appName}/disable")
    public void disable(@PathParam("appName") final String appName) {
        if (appConfigService.load(appName).isPresent()) {
            disableAppService.add(appName);
            for (CloudJobConfiguration each : jobConfigService.loadAll()) {
                if (appName.equals(each.getAppName())) {
                    producerManager.unschedule(each.getJobName());
                }
            }
        }
    }
    
    /**
     * 启用应用.
     * 
     * @param appName 应用名称
     * @throws JSONException JSON解析异常
     */
    @DELETE
    @Path("/{appName}/disable")
    public void enable(@PathParam("appName") final String appName) throws JSONException {
        if (appConfigService.load(appName).isPresent()) {
            disableAppService.remove(appName);
            for (CloudJobConfiguration each : jobConfigService.loadAll()) {
                if (appName.equals(each.getAppName())) {
                    producerManager.reschedule(each.getJobName());
                }
            }
        }
    }
    
    /**
     * 注销应用.
     *
     * @param appName 应用名称
     */
    @DELETE
    @Path("/{appName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public void deregister(@PathParam("appName") final String appName) {
        if (appConfigService.load(appName).isPresent()) {
            removeAppAndJobConfigurations(appName);
            stopExecutors(appName);
        }
    }
    
    private void removeAppAndJobConfigurations(final String appName) {
        for (CloudJobConfiguration each : jobConfigService.loadAll()) {
            if (appName.equals(each.getAppName())) {
                producerManager.deregister(each.getJobName());
            }
        }
        disableAppService.remove(appName);
        appConfigService.remove(appName);
    }
    
    private void stopExecutors(final String appName) {
        try {
            Collection<MesosStateService.ExecutorStateInfo> executorBriefInfo = mesosStateService.executors(appName);
            for (MesosStateService.ExecutorStateInfo each : executorBriefInfo) {
                producerManager.sendFrameworkMessage(ExecutorID.newBuilder().setValue(each.getId()).build(),
                        SlaveID.newBuilder().setValue(each.getSlaveId()).build(), "STOP".getBytes());
            }
        } catch (final JSONException ex) {
            throw new JobSystemException(ex);
        }
    }
}
