package com.github.kana1.logging.log4j;


import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.status.StatusLogger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClient;
import com.amazonaws.services.logs.model.CreateLogGroupRequest;
import com.amazonaws.services.logs.model.CreateLogStreamRequest;
import com.amazonaws.services.logs.model.DescribeLogGroupsRequest;
import com.amazonaws.services.logs.model.DescribeLogGroupsResult;
import com.amazonaws.services.logs.model.DescribeLogStreamsRequest;
import com.amazonaws.services.logs.model.DescribeLogStreamsResult;
import com.amazonaws.services.logs.model.InputLogEvent;
import com.amazonaws.services.logs.model.InvalidSequenceTokenException;
import com.amazonaws.services.logs.model.LogGroup;
import com.amazonaws.services.logs.model.LogStream;
import com.amazonaws.services.logs.model.PutLogEventsRequest;
import com.amazonaws.services.logs.model.PutLogEventsResult;
import com.amazonaws.services.logs.model.ServiceUnavailableException;

public class CloudWatchLogService {

	private static final StatusLogger LOG = StatusLogger.getLogger();
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

	private final String logGroupName;
	private final String logStreamName;
	private final AWSLogs awsLogs;

	private final AtomicReference<String> lastSequenceToken = new AtomicReference<>();
	
	CloudWatchLogService(String logGroupName, String logRegionName) {

		this(logGroupName, initClient(logRegionName), Clock.systemUTC());
	}

	CloudWatchLogService(String logGroupName, 
						 AWSLogsClient awsLogsClient,
						 Clock clock) throws ServiceUnavailableException {

		this.logGroupName = logGroupName;
		this.awsLogs = awsLogsClient;
		this.logStreamName = buildLogStreamName(clock);

		try {
			setupLogGroup();
			setupLogStream();
		}  catch (AmazonClientException ex) {
			/* 
			 * The appender creation might fail here.
			 * in such cases user will see following log entry on application start
			 * ERROR Null object returned for CloudWatchAppender in Appenders.
			 */
			LOG.error(ex);
			throw ex;
		}
		
	}

	public AWSLogs getAwsLogs() {
		return awsLogs;
	}

	private static AWSLogsClient initClient(String logRegionName) {
		
		Regions region = null;

		try {
			region = Regions.fromName(logRegionName);	
		} catch (Exception e) {
			LOG.warn(e);
			System.err.println("Invalid region name given: "+ logRegionName + ". Switching to default region.");
			region = Regions.DEFAULT_REGION;
		}

		// NOTE: seems the client is lazy initialized. 
		//       So actual error wont be thrown at this point !!.
		return new AWSLogsClient().withRegion(region);
	}

	private String buildLogStreamName(Clock clock) {
		// using random uuid name as prefix so every restart/deployment of the application 
		// will create a new stream.
		String date = FORMATTER.format(LocalDate.now(clock));
	    return String.format("%s/%s", date, LoggingUtils.getUUID());
	}


	private void setupLogGroup() throws AmazonClientException {

		System.out.println("setupLogGroup: " + this.logGroupName);
		DescribeLogGroupsRequest request = new DescribeLogGroupsRequest().withLogGroupNamePrefix(this.logGroupName);
		DescribeLogGroupsResult groupsResult = null;

//		try {
			groupsResult = this.awsLogs.describeLogGroups(request);
//		} catch (AmazonClientException ex) {
//			System.err.println("1, " + ex);
//			throw ex;
//		}

		System.out.println("groupsResult  :" + groupsResult);
		if (groupsResult != null) {

			Optional<LogGroup> existing = groupsResult.getLogGroups().stream()
					.filter(logGroup -> logGroup.getLogGroupName().equalsIgnoreCase(this.logGroupName)).findFirst();

			if (!existing.isPresent()) {

				System.out.println("Creates LogGroup: " + this.logGroupName);
				awsLogs.createLogGroup(new CreateLogGroupRequest().withLogGroupName(this.logGroupName));

			}else {
				LOG.debug("Log group "+ this.logGroupName + " already exists.");
			}
		} 
//		else {
//			System.out.println("groupsResult  " + groupsResult);
//		}

	}

	private void setupLogStream() throws AmazonClientException {

		DescribeLogStreamsRequest request = new DescribeLogStreamsRequest()
													.withLogGroupName(this.logGroupName)
													.withLogStreamNamePrefix(this.logStreamName);

		DescribeLogStreamsResult streamsResult = this.awsLogs.describeLogStreams(request);

		if (streamsResult != null) {
			Optional<LogStream> existing = streamsResult.getLogStreams().stream()
					.filter(logStream -> logStream.getLogStreamName().equalsIgnoreCase(this.logStreamName)).findFirst();

			if (!existing.isPresent()) {
				System.out.println("Creates LogStream: " + this.logStreamName + " in LogGroup: " + this.logGroupName);

				this.awsLogs.createLogStream(new CreateLogStreamRequest().withLogGroupName(this.logGroupName)
						.withLogStreamName(this.logStreamName));
			} else {
				// get the continuation sequence if the stream already exists.
				lastSequenceToken.set(this.getStreamNextToken(this.logGroupName, this.logStreamName));
			}
		}

	}
	
	private String getStreamNextToken(String groupName, String streamName) {
		
		DescribeLogStreamsRequest req = new DescribeLogStreamsRequest();
		req.setLogGroupName(groupName);
		req.setLogStreamNamePrefix(streamName);
		
		try{
			
			DescribeLogStreamsResult results = this.awsLogs.describeLogStreams(req);
			return results.getLogStreams().get(0).getUploadSequenceToken();
			
		}catch (Exception e) {
			System.err.println(e);
			// return whatever the last set value.
			return lastSequenceToken.get();
		}
//		catch(ServiceUnavailableException sue)
//		{
//			throw new Exception(sue);
//		}
		
	}


	private void send(PutLogEventsRequest request) {
		
		try {
			PutLogEventsResult result = this.awsLogs.putLogEvents(request);
			lastSequenceToken.set(result.getNextSequenceToken());
			
		} catch (InvalidSequenceTokenException e) {

			System.err.println("InvalidSequenceTokenException while sending logs"+ e);
			System.err.println("lastSequenceToken: "+ lastSequenceToken.get());
			request.setSequenceToken(e.getExpectedSequenceToken());
			PutLogEventsResult result = this.awsLogs.putLogEvents(request);
			lastSequenceToken.set(result.getNextSequenceToken());
		}
	}


	void sendMessages(List<InputLogEvent> inputLogEvents) {

		try {
			PutLogEventsRequest request = new PutLogEventsRequest(this.logGroupName, this.logStreamName, inputLogEvents)
					.withSequenceToken(this.lastSequenceToken.get());
			send(request);

		} catch (Exception e) {
			System.err.println("Error while sending logs:" + e);
		}
	}
}
