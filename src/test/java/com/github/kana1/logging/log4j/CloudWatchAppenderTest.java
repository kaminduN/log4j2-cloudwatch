package com.github.kana1.logging.log4j;


import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.junit.Before;
import org.junit.Test;

import junit.framework.TestCase;

public class CloudWatchAppenderTest extends TestCase {

	  @Mock
	  private CloudWatchLogService cloudWatchLogService;

	  @Before
	  public void initMocks() {
	    MockitoAnnotations.initMocks(this);
	  }

	  @Test
	  public void testLogger() {
		  assertTrue( true );
	  }
	
}
