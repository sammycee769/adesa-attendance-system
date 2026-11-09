package com.adesa.attendance.payments;
import java.time.*;
public final class PaymentCalendar {
 public static final ZoneId ZONE = ZoneId.of("Africa/Lagos");
 private PaymentCalendar() {}
 public static LocalDate adjusted(YearMonth month, int day) {
  LocalDate date=month.atDay(day);
  return switch(date.getDayOfWeek()) {case SATURDAY -> date.minusDays(1); case SUNDAY -> date.minusDays(2); default -> date;};
 }
 public static ZonedDateTime due(YearMonth month,int day) {return adjusted(month,day).atTime(9,0).atZone(ZONE);}
 public static ZonedDateTime next(Instant now,int day) {
  YearMonth month=YearMonth.from(now.atZone(ZONE));
  while (!due(month,day).toInstant().isAfter(now)) month=month.plusMonths(1);
  return due(month,day);
 }
}
