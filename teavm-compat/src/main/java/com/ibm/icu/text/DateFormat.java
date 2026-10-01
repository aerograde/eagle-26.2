package com.ibm.icu.text;

import java.util.Date;

import com.ibm.icu.util.Calendar;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.text.DateFormat (see
 * {@link com.ibm.icu.lang.UCharacter}). Only the clock/compass item model
 * ({@code net.minecraft.client.renderer.item.properties.select.LocalTime}) uses
 * it; that code path is not exercised at the title screen, and format() is guarded
 * by a try/catch, so a stub return is safe.
 *
 * NOTE: does NOT extend java.text.Format — that is what re-triggers the sweep.
 */
public class DateFormat {

	public String format(Date date) {
		return "";
	}

	public void setCalendar(Calendar calendar) {
	}
}
