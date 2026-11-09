package com.adesa.attendance.payments;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class PaymentCalendarTest {
 @Test void weekendAndWeekday() {
  assertThat(PaymentCalendar.adjusted(YearMonth.of(2026,11),28)).isEqualTo(LocalDate.of(2026,11,27));
  assertThat(PaymentCalendar.adjusted(YearMonth.of(2026,6),28)).isEqualTo(LocalDate.of(2026,6,26));
  assertThat(PaymentCalendar.adjusted(YearMonth.of(2026,10),28)).isEqualTo(LocalDate.of(2026,10,28));
  assertThat(PaymentCalendar.adjusted(YearMonth.of(2026,5),16)).isEqualTo(LocalDate.of(2026,5,15));
  assertThat(PaymentCalendar.adjusted(YearMonth.of(2026,6),20)).isEqualTo(LocalDate.of(2026,6,19));
 }
 @Test void monthYearAndTimezoneBoundaries() {
  assertThat(PaymentCalendar.next(Instant.parse("2026-12-29T12:00:00Z"),28).toLocalDate()).isEqualTo(LocalDate.of(2027,1,28));
  assertThat(PaymentCalendar.due(YearMonth.of(2026,11),16).toInstant()).isEqualTo(Instant.parse("2026-11-16T08:00:00Z"));
  assertThat(PaymentCalendar.adjusted(YearMonth.of(2027,5),1)).isEqualTo(LocalDate.of(2027,4,30));
  assertThat(PaymentCalendar.adjusted(YearMonth.of(2028,1),1)).isEqualTo(LocalDate.of(2027,12,31));
  assertThat(PaymentCalendar.adjusted(YearMonth.of(2028,2),28)).isEqualTo(LocalDate.of(2028,2,28));
 }
 @Test void allMonthsOverTenYearsStayOnOrBeforeScheduledDay() {
  for(int year=2026;year<2036;year++)for(int month=1;month<=12;month++)for(int day:new int[]{16,20,28}) {
   var actual=PaymentCalendar.adjusted(YearMonth.of(year,month),day);
   assertThat(actual.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY);
   assertThat(actual).isBetween(YearMonth.of(year,month).atDay(day).minusDays(2),YearMonth.of(year,month).atDay(day));
  }
 }
}
