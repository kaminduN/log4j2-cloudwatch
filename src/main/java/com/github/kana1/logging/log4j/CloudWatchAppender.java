package com.github.kana1.logging.log4j;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.status.StatusLogger;

import com.amazonaws.http.IdleConnectionReaper;
import com.amazonaws.services.logs.model.InputLogEvent;


/**
 * Log4j2 appender for AWS CloudWatch Logs
 *
 */
@Plugin(name = "CloudWatchAppender", category = "Core", elementType = "appender", printObject = true)
public class CloudWatchAppender extends AbstractAppender {
	
	/* 
	 * internal log4j logger.
	 * this uses the org.apache name space to log. so even the Configuration status is given 
	 * the org.apache name space logging configuration get the precedence.
	 */
	private static final StatusLogger LOG = StatusLogger.getLogger();
	
	private static final int DEFAULT_QUEUE_LENGTH = 1024;
	private static final int DEFAULT_MESSAGE_BATCH_SIZE = 128;
	private static final int DAEMON_SLEEP = 20;

	private CloudWatchLogService cloudWatchLogService;

	/**
	 * The queue used to buffer log entries
	 */
	private final LinkedBlockingQueue<LogEvent> logEventsQueue;

	/**
	 * The maximum number of log entries to send in one go to the AWS CloudWatch Log
	 * service
	 */
	private final int messagesBatchSize;

	private final AtomicBoolean appenderInitialised = new AtomicBoolean(false);
	
	private Thread appenderThread;

	// The custom appender needs to declare a factory method
	// annotated with `@PluginFactory`. Log4j will parse the configuration
	// and call this factory method to construct an appender instance with
	// the configured attributes.
	
	// ignoreExceptions True causes exceptions encountered while appending events to
	// be internally logged and then ignored. When set to false exceptions will be
	// propagated to the caller, instead.
	@PluginFactory
	public static CloudWatchAppender createAppender(@PluginAttribute("name") String name,
			@PluginAttribute("logGroupName")  @Required(message = "logGroupName is required") String group, 
			@PluginAttribute(value = "logRegionName", defaultString = "us-east-1") String logRegionName,
			@PluginAttribute(value = "queueLength", defaultInt = DEFAULT_QUEUE_LENGTH) int queueLength,
			@PluginAttribute(value = "messagesBatchSize", defaultInt = DEFAULT_MESSAGE_BATCH_SIZE) int messagesBatchSize,
			@PluginAttribute(value = "ignoreExceptions", defaultBoolean = false) final boolean ignoreExceptions, 
			@PluginElement("Filter") Filter filter, 
			@PluginElement("Layout") Layout<? extends Serializable> layout) {
		
		if (layout == null) {
			layout = PatternLayout.createDefaultLayout();
		}

		LOG.debug("group name : "+ group);
		
		CloudWatchLogService cloudWatchLogService = null;
		try {
			cloudWatchLogService = new CloudWatchLogService(group, logRegionName);
		} catch (Exception e) {
			LOG.error("AWS Cloudwatch client initializing error", e);
			throw e;
		}

		LOG.debug("logRegionName: " + logRegionName);

		return new CloudWatchAppender((name != null) ? name : "cloudwatch",
									  cloudWatchLogService, 
									  queueLength, 
									  messagesBatchSize, 
									  filter,
									  layout,
									  ignoreExceptions);
	}

	protected CloudWatchAppender(String name, 
								 CloudWatchLogService cloudWatchLogService, 
								 int queueLength,
								 int messagesBatchSize, 
								 Filter filter, 
								 Layout<? extends Serializable> layout,
								 final boolean ignoreExceptions) {
		super(name, filter, (layout != null) ? layout : PatternLayout.createDefaultLayout(), ignoreExceptions);

		this.cloudWatchLogService = cloudWatchLogService;
		this.messagesBatchSize = messagesBatchSize;
		logEventsQueue = new LinkedBlockingQueue<>(queueLength);

		initDaemon();
	    appenderInitialised.set(true);
	}


	// The append method is where the appender does the work.
	// Given a log event, you are free to do with it what you want.
	@Override
	public void append(LogEvent event) {
		
		if (appenderInitialised.get()) {
			logEventsQueue.offer(event);
		} else {
			LOG.warn("Cannot append as appender not yet initialised");
		}
	}

	/*
	 * Actual sending of the messages in bulk manner
	 */
	private void sendMessages() {

		Collection<LogEvent> loggingEvents = new ArrayList<>();

		LogEvent polledLoggingEvent = logEventsQueue.poll();

		while (polledLoggingEvent != null && loggingEvents.size() <= messagesBatchSize) {
			loggingEvents.add(polledLoggingEvent);
			polledLoggingEvent = logEventsQueue.poll();
		}

		List<InputLogEvent> inputLogEvents = loggingEvents.stream().map(event -> new InputLogEvent()
				.withTimestamp(event.getTimeMillis()).withMessage(new String(getLayout().toByteArray(event), UTF_8)))
				.collect(toList());

		cloudWatchLogService.sendMessages(inputLogEvents);
	}

	@Override
	public void stop() {
		super.stop();
		while (logEventsQueue != null && !logEventsQueue.isEmpty()) {
			sendMessages();
		}
		try {
			// close down the logging publisher thread
            this.appenderThread.join();
        } catch (InterruptedException e) {
        
        }
		
		try {
			this.cloudWatchLogService.getAwsLogs().shutdown();
		} catch (Exception e) {
			System.err.println("1ex:"+ e);
		}
		
		try {
			// Shutting down AWS IdleConnectionReaper thread...
			// [java-sdk-http-connection-reaper] com.amazonaws.http.IdleConnectionReaper: Unable to close idle connections
			// java.lang.IllegalStateException: zip file closed

			// The logger initializing may be a problem with redeployment in mule servers 
			// https://www.mulesoft.org/jira/browse/MULE-8341
			IdleConnectionReaper.shutdown();
		} catch (Exception e) {
			System.err.println(e);
		}
		
	}
	
	private void initDaemon() {
		this.appenderThread = new Thread(() -> {
			while (true) {
				try {
					if (!logEventsQueue.isEmpty()) {
						sendMessages();
					}
					Thread.sleep(DAEMON_SLEEP);
				} catch (InterruptedException e) {
					System.err.println("CloudWatch appender error" + e);
					// Restore interrupted state...
					Thread.currentThread().interrupt();
				}
			}
		});
		appenderThread.start();
	}
}
