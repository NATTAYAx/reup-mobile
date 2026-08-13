# reup-mobile

The Android side of [Reup](https://github.com/NATTAYAx/reup) — an app that
tracks what resets, and when.

Games reset dailies at 04:00 and weeklies on Monday. Bills, subscriptions and
chores work the same way. The desktop app has handled that for months; this is
the half that can reach you when you are not sitting at the computer, which for
most people is most of the time.

Kotlin Multiplatform, no UI framework yet, Android only so far. The shared
module compiles for iOS and CI proves it every push, but nobody has run it on an
iPhone because nobody here owns one.

---

## Where it is

Alarms fire. That is the milestone that mattered, and it is done: close the app,
lock the phone, put it down, and it still buzzes at the right time.

Working:

- the reset engine, ported from the desktop and verified against 3,440 cases
- the horizon scheduler that decides which alarms the OS gets told about
- registration with `AlarmManager`, rebuilt on launch, on fire, and on boot
- notifications with quiet hours applied

Not there yet:

- **no database.** Tasks are hardcoded in `Samples.kt`. Nothing can be added,
  edited or completed.
- **no sync.** The desktop database already carries UIDs, timestamps and
  tombstones for this, but nothing reads them yet.
- **no UI to speak of.** One screen of monospace text on black. It reports what
  is scheduled and which system settings are about to break it, which is what
  this stage needs; it is not what an app looks like.

---

## Layout

```
shared/src/commonMain/    Wall.kt, Task.kt, ResetSchedule.kt, Horizon.kt
shared/src/commonTest/    HorizonTest.kt
shared/src/jvmTest/       ScheduleVectorsTest.kt + schedule-vectors.json

app/src/main/java/app/reup/
  MainActivity.kt   status screen, permission prompt, test button
  Scheduler.kt      builds the queue, hands it to AlarmManager
  AlarmReceiver.kt  fires the notification, rebuilds the queue
  BootReceiver.kt   reschedules after a restart or an app update
  Notifications.kt  the channel, and its two defaults
  Samples.kt        the hardcoded task list, until there is a database
```

`commonMain` imports nothing from Android, nothing from the JVM and nothing from
a browser. That is what lets it compile for iOS, and CI is what stops it quietly
ceasing to be true.

---

## Building it

Android Studio brings the JDK, the SDK, Gradle and `adb`. Nothing else is
needed.

```
gradlew.bat :shared:jvmTest      # 13 tests, all of the interesting logic
gradlew.bat :app:installDebug    # onto a plugged-in phone
```

Two things worth getting right on a Windows machine with a small C: drive —
point the SDK at another drive during setup, and set `GRADLE_USER_HOME`
somewhere other than `C:\Users\you\.gradle`. Between them they run to 15–20 GB.

Skip the emulator. It cannot reproduce Doze, Samsung's battery manager or a
reboot, which are the three things that actually break this app.

---

## The tests

`schedule-vectors.json` is a spec, not a fixture. Every case in it came from
running the desktop implementation over a matrix of task shapes, instants, task
timezones and app timezones, and recording what it said. The Kotlin port has to
reproduce all 3,440 exactly.

If that suite goes red, the Kotlin is wrong — the vectors come from code that
has been running correctly for months.

One case in there is worth knowing about. Every case stores its own `app_zone`,
because a task with no timezone of its own floats with whatever the app is set
to. The first version of the file left that implicit and therefore baked in the
clock settings of the machine that generated it; 264 cases were wrong by exactly
seven hours. If a vector file ever looks off by a whole number of hours, that is
why.

`HorizonTest` has no desktop equivalent to compare against, so it asserts
properties instead — ordering, bounds, and scenarios worked out by hand. One of
those tests exists because the first version of `horizon()` walked the calendar
until the year overflowed, which is what happens when a loop is bounded by how
many results it has produced and every result is being skipped.

---

## Decisions

**Nothing runs in the background. Ever.** No foreground service, no periodic
work, no polling, no sockets. The app computes when it should ring, hands those
instants to the OS, and dies. The OS wakes it. Between the moment it closes and
the moment an alarm fires, it uses nothing at all — which on a phone with a worn
battery is the difference between an app that stays installed and one that gets
uninstalled.

**Alarms are inexact by default.** `setAndAllowWhileIdle` lets Android batch this
app's wake-up with everyone else's, so the CPU comes up once and serves them all.
The exact variants each force their own wake, and Android's own documentation
says so. The cost is that an alarm can land minutes late, which for "the game
reset" is not a cost. Exact timing becomes a per-task opt-in later, for the
handful that need it. Until then the app needs no special permission at all.

**Recompute everything, never patch.** Launching the app, an alarm firing, a
reboot, an app update — each rebuilds the whole queue from scratch. There is no
stored record of what was scheduled, so there is no stored record to drift out
of step with reality.

**Notifications are silent, and hidden on the lock screen.** Silent because a
reminder app that makes noise gets its notifications switched off wholesale
within a week, after which it notifies about nothing forever while looking
identical to one that works. Hidden because the default everywhere is to print
the whole thing on a locked screen, and a task name is readable by whoever is
sitting nearby whenever the phone is face-up on a table. Most of them are games.
Some of them are a medicine, an appointment, or a line of someone's finances.

**`BootReceiver` exists because Android throws away every alarm on restart** —
and again on app update — and warns nobody. The symptom is an app that worked
for weeks and then simply stopped, with no crash and nothing visibly different.

---

## Samsung

This is developed against a Galaxy A73, and Samsung's software is more
aggressive than stock Android. Their own documentation says an app left unopened
for about three days goes into Sleeping, which restricts alarms, and after
roughly sixteen days into Deep sleeping, which stops them entirely until the app
is opened by hand.

So two things have to be set, and only one of them can be reached from inside the
app:

- **Settings → Apps → Reup → Battery → Unrestricted**
- **Settings → Battery → Background usage limits → Never sleeping apps → add Reup**

The status screen reports whether the first one is set. Nothing can detect the
second, which is why it is written down here.

---

## iOS

`iosArm64` and `iosSimulatorArm64` are declared in `shared/build.gradle.kts`
even though a Windows machine cannot build them. Gradle skips them locally; the
macOS runner in CI does not.

The point is not that iOS is coming soon. It is that the day an `import
android.*` lands in `commonMain`, the build goes red that day rather than a year
later with eight thousand lines wrapped around it. The door stays open because
something breaks when it closes.

When there is an iPhone to run it on, the scheduler will need one change: iOS
refuses to hold more than 64 pending notification requests and silently discards
the rest. `horizon()` already takes a limit and defaults to 50 for that reason.

---

## Not a medical device

Some of the design here is about wellbeing. That does not make this a health
app. It does not assess, screen, diagnose or treat anything, and it is not a
substitute for care from an actual person.

---

## Licence

MIT.