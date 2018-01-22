package com.github.kana1.logging.log4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.UUID;

public class LoggingUtils {

	static public String getUUID()
	{
		UUID uuid = UUID.randomUUID();		
		return uuid.toString().replace("-", "");
	}
	
	
	static String retrieveInstance() {
		// TODO: putting the instance name as part of the log stream name ?
		//https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html
		try {
			
			URL url = new URL("http://169.254.169.254/latest/meta-data/instance-id");
			URLConnection conn = url.openConnection();
			conn.setConnectTimeout(10000);
			
			try (InputStream in = conn.getInputStream()) {
				BufferedReader r = new BufferedReader(new InputStreamReader(in));
				String instance = r.readLine();
			
				if (instance != null) {
					return instance;
				} else {
					throw new IOException("Instance is null");
				}
			}
			
		} catch (IOException e) {
			
			try {
				return InetAddress.getLocalHost().getHostName();
			} catch (UnknownHostException e1) {
				throw new RuntimeException(e1);
			}

		}
	}
}
