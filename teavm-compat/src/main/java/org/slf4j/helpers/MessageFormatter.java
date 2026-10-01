package org.slf4j.helpers;

/**
 * Sequential {} substitution with slf4j's trailing-throwable rule: a final
 * Throwable argument not consumed by a placeholder becomes the tuple's
 * throwable instead of being formatted in.
 */
public final class MessageFormatter {

	private MessageFormatter() {
	}

	public static FormattingTuple format(String messagePattern, Object arg) {
		return arrayFormat(messagePattern, new Object[] { arg });
	}

	public static FormattingTuple format(String messagePattern, Object arg1, Object arg2) {
		return arrayFormat(messagePattern, new Object[] { arg1, arg2 });
	}

	public static FormattingTuple arrayFormat(String messagePattern, Object[] argArray) {
		if (argArray == null || argArray.length == 0) {
			return new FormattingTuple(messagePattern, argArray, null);
		}
		Throwable throwable = null;
		int usable = argArray.length;
		if (argArray[argArray.length - 1] instanceof Throwable
				&& countPlaceholders(messagePattern) < argArray.length) {
			throwable = (Throwable) argArray[argArray.length - 1];
			usable = argArray.length - 1;
		}
		if (messagePattern == null) {
			return new FormattingTuple(null, argArray, throwable);
		}
		StringBuilder sb = new StringBuilder(messagePattern.length() + 50);
		int start = 0;
		for (int i = 0; i < usable; ++i) {
			int idx = messagePattern.indexOf("{}", start);
			if (idx == -1) {
				break;
			}
			sb.append(messagePattern, start, idx);
			deepAppend(sb, argArray[i]);
			start = idx + 2;
		}
		sb.append(messagePattern, start, messagePattern.length());
		return new FormattingTuple(sb.toString(), argArray, throwable);
	}

	private static void deepAppend(StringBuilder sb, Object arg) {
		if (arg == null) {
			sb.append("null");
			return;
		}
		if (!arg.getClass().isArray()) {
			String s;
			try {
				s = String.valueOf(arg);
			} catch (Throwable t) {
				s = "[FAILED toString()]";
			}
			sb.append(s);
			return;
		}
		if (arg instanceof Object[]) {
			sb.append('[');
			Object[] a = (Object[]) arg;
			for (int i = 0; i < a.length; ++i) {
				if (i > 0) {
					sb.append(", ");
				}
				deepAppend(sb, a[i]);
			}
			sb.append(']');
		} else {
			// primitive arrays
			sb.append('[');
			int len = java.lang.reflect.Array.getLength(arg);
			for (int i = 0; i < len; ++i) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(java.lang.reflect.Array.get(arg, i));
			}
			sb.append(']');
		}
	}

	private static int countPlaceholders(String messagePattern) {
		if (messagePattern == null) {
			return 0;
		}
		int count = 0;
		int idx = 0;
		while ((idx = messagePattern.indexOf("{}", idx)) != -1) {
			++count;
			idx += 2;
		}
		return count;
	}

}
