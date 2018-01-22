# Log4j 2.x appender for AWS CloudWatch logs

Sends logs to specified `logGroupName`.   


## Build

```
mvn clean install
```

### How to install and use the logger

To include the logger in your project, add this to your POM file:

```xml
<dependency>
	<groupId>com.github.kana1.logging.log4j</groupId>
	<artifactId>log4j2-cloudwatch</artifactId>
	<version>0.0.1</version>       
</dependency>
```

## log4j2.xml example

```xml
<?xml version="1.0" encoding="utf-8"?>
<Configuration packages="com.github.kana1.logging.log4j" status="DEBUG">

	<Properties>
		<Property name="application-name">cool-app</Property>
		<Property name="log-pattern">%d [%t] %-5p %c{-30}:%L - %m%n</Property>
	</Properties>

    <Appenders>
               
        <CloudWatchAppender name="CloudWatch" 
   							logGroupName="app/my-app-${application-name}" 
   							logRegionName="us-east-1">
		      <PatternLayout>
		        <Pattern>${log-pattern}</Pattern>
		      </PatternLayout>
		  </CloudWatchAppender>

    </Appenders>
   
    <Loggers>

        <!-- Apache Commons tend to make a lot of noise which can clutter the log-->
        <AsyncLogger name="org.apache" level="WARN"/>
        
        <AsyncRoot level="INFO">
            <AppenderRef ref="CloudWatch"/>
        </AsyncRoot>
    </Loggers>
</Configuration>
```


## IAM Security
Your logger will require access to CloudWatch, even if just to support the creation of incidents. Make sure that your IAM permissions include something similar to this.

```javascript
{
	"Effect": "Allow",
	"Action": [
		"logs:CreateLogGroup",
		"logs:CreateLogStream",
		"logs:PutLogEvents"
	],
	"Resource": "arn:aws:logs:*:*:*"
}
```

