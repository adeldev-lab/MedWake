#!/bin/bash
PACKAGE="org.wakegated.medication.debug"
NOW=$(date +%s%3N)
PAST=$((NOW - 600000))

# Insert medication and due event directly into Room DB
adb shell "run-as $PACKAGE sqlite3 /data/data/$PACKAGE/databases/medwake_database.db \"
INSERT OR REPLACE INTO medications (medicationId, name, dosage, unit, notes, wakeGated, wakeThresholdMinutes, soundUri, soundTitle, vibrate, stockQuantity, color, isActive, createdAt)
VALUES (1, 'Vitamin D3 (Morning)', '1', 'tablet', 'Take with breakfast or water', 1, 420, '', 'Default Alarm', 1, 30.0, -16747440, 1, $NOW);

INSERT OR REPLACE INTO schedules (scheduleId, medicationId, timeInMinutes, daysOfWeek, cycleDaysOn, cycleDaysOff, startDate, endDate)
VALUES (1, 1, 420, 127, 0, 0, NULL, NULL);

INSERT OR REPLACE INTO dose_events (doseEventId, medicationId, scheduledAt, dueAt, plannedTriggerAt, actionTimestamp, state, rescheduledFromId, stockHandled, notes)
VALUES (1, 1, $PAST, $PAST, NULL, NULL, 'DUE', NULL, 0, '');
\""

echo "Test dose inserted into database."
