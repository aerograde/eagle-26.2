package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.sql;

/**
 * java.sql.Date — thin wrapper over java.util.Date (reached from DFU/gson date
 * handling; no real JDBC exists in the browser runtime).
 */
public class TDate extends java.util.Date {

	public TDate(long date) {
		super(date);
	}

	@Deprecated
	public TDate(int year, int month, int day) {
		super(year, month, day);
	}

	public static TDate valueOf(String s) {
		String[] parts = s.split("-");
		if (parts.length != 3) {
			throw new IllegalArgumentException(s);
		}
		return new TDate(Integer.parseInt(parts[0]) - 1900, Integer.parseInt(parts[1]) - 1,
				Integer.parseInt(parts[2]));
	}

}
