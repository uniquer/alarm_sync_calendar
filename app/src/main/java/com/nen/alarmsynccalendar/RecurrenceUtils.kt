package com.nen.alarmsynccalendar

import java.util.Calendar

object RecurrenceUtils {
    fun calculateNextOccurrence(triggerTime: Long, type: RecurrenceType, data: Int?): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = triggerTime
        }

        when (type) {
            RecurrenceType.NONE -> return triggerTime
            RecurrenceType.DAILY -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            RecurrenceType.WEEKLY -> {
                val targetDayOfWeek = data ?: calendar.get(Calendar.DAY_OF_WEEK)
                // Ensure we move at least one day forward
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                while (calendar.get(Calendar.DAY_OF_WEEK) != targetDayOfWeek) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            RecurrenceType.MONTHLY -> {
                val targetDayOfMonth = data ?: calendar.get(Calendar.DAY_OF_MONTH)
                
                // Move to next month
                calendar.add(Calendar.MONTH, 1)
                
                // Adjust for end of month (29, 30, 31)
                val maxDays = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                if (targetDayOfMonth > maxDays) {
                    calendar.set(Calendar.DAY_OF_MONTH, maxDays)
                } else {
                    calendar.set(Calendar.DAY_OF_MONTH, targetDayOfMonth)
                }
            }
        }
        
        return calendar.timeInMillis
    }
}
