package org.slf4j.helpers;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exists only so netty's Slf4JLoggerFactory NOP-check (instanceof) links;
 * our LoggerFactory never hands one out.
 */
public class NOPLoggerFactory implements ILoggerFactory {

	@Override
	public Logger getLogger(String name) {
		return LoggerFactory.getLogger(name);
	}

}
