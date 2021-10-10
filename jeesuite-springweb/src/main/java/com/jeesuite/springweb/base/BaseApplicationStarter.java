package com.jeesuite.springweb.base;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import com.jeesuite.common.async.AsyncInitializer;
import com.jeesuite.common.util.NodeNameHolder;
import com.jeesuite.common.util.ResourceUtils;
import com.jeesuite.spring.ApplicationStartedListener;
import com.jeesuite.spring.InstanceFactory;
import com.jeesuite.springweb.client.SimpleRestTemplateBuilder;
import com.jeesuite.springweb.ext.feign.CustomLoadBalancerFeignClient;
import com.jeesuite.springweb.logging.LogProfileManager;

import feign.Client;

public class BaseApplicationStarter{

	
	@Bean("restTemplate")
	@LoadBalanced
	RestTemplate restTemplate() {
		int readTimeout = ResourceUtils.getInt("restTemplate.readTimeout.ms", 30000);
		return SimpleRestTemplateBuilder.build(readTimeout);
	}
	
	@Bean
	@ConditionalOnProperty(value = "feign.custom-loadbalance-mapping.enabled",havingValue = "true")
	public Client feignClient(LoadBalancerClient loadBalancer) {
		return new CustomLoadBalancerFeignClient(loadBalancer);
	}

	protected static long before() {
		LogProfileManager.initialize();
		System.setProperty("client.nodeId", NodeNameHolder.getNodeId());
		return System.currentTimeMillis();
	}

	protected static void after(long starTime) {
        //
		LogProfileManager.reload();
		
		long endTime = System.currentTimeMillis();
		long time = endTime - starTime;
		System.out.println("\nStart Time: " + time / 1000 + " s");
		System.out.println("...............................................................");
		System.out.println("..................Service starts successfully (port:"+ResourceUtils.getProperty("server.port")+")..................");
		System.out.println("...............................................................");

		Map<String, ApplicationStartedListener> interfaces = InstanceFactory.getInstanceProvider()
				.getInterfaces(ApplicationStartedListener.class);
		if (interfaces != null) {
			for (ApplicationStartedListener listener : interfaces.values()) {
				System.out.println(">>>begin to execute listener:" + listener.getClass().getName());
				listener.onApplicationStarted(InstanceFactory.getContext());
				System.out.println("<<<<finish execute listener:" + listener.getClass().getName());
			}
		}
		
		//执行异步初始化
		Map<String, AsyncInitializer> asyncInitializers = InstanceFactory.getInstanceProvider().getInterfaces(AsyncInitializer.class);
			if(asyncInitializers != null){
			for (AsyncInitializer initializer : asyncInitializers.values()) {
				System.out.println(">>>begin to execute AsyncInitializer:" + initializer.getClass().getName());
				initializer.process();
			}
		}
	}
}
