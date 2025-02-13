/*
 * Copyright 2016-2020 www.mendmix.com.
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
package com.mendmix.amqp.adapter.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mendmix.amqp.MQContext;
import com.mendmix.amqp.MQContext.ActionType;
import com.mendmix.amqp.MQMessage;
import com.mendmix.amqp.MessageHandler;
import com.mendmix.common.CurrentRuntimeContext;

/**
 * 
 * @description <br>
 * @author <a href="mailto:vakinge@gmail.com">jiangwei</a>
 * @date 2022年5月9日
 */
public class MessageHandlerDelegate {

	private final static Logger logger = LoggerFactory.getLogger("com.zvosframework.adapter.amqp");
	
	private MessageHandler messageHandler;
	
	public MessageHandlerDelegate(String topic, MessageHandler messageHandler) {
		this.messageHandler = messageHandler;
	}


	public void onMessage(String body, String topic) {
		MQMessage message = MQMessage.build(body);
		try {
			//上下文
			if(message.getHeaders() != null) {	
				CurrentRuntimeContext.addContextHeaders(message.getHeaders());
			}
			messageHandler.process(message);
			if(logger.isDebugEnabled())logger.debug("MENDMIX-TRACE-LOGGGING-->> MQ_MESSAGE_CONSUME_SUCCESS ->message:{}",message.toString());
			MQContext.processMessageLog(message, ActionType.sub,null);
		} catch (Exception e) {
			MQContext.processMessageLog(message, ActionType.sub,e);
			logger.error(String.format("MENDMIX-TRACE-LOGGGING-->> MQ_MESSAGE_CONSUME_ERROR ->message:%s",body),e);
		}
		
	}

	
}
