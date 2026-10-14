# reup-mobile

The Android side of [Reup](https://github.com/NATTAYAx/reup) — an app that
tracks what resets, and when.

Games reset dailies at 04:00 and weeklies on Monday. Bills, subscriptions and
chores work the same way. The desktop app has handled that for months; this is
the half that can reach you when you are not sitting at the computer, which for
most people is most of the time.

Kotlin Multiplatform, Android only so far. The three tabs and both forms are
Jetpack Compose; the sync settings and the slip reader are still hand-built
views and move over one at a time. The shared module compiles for iOS and CI
proves it every push, but nobody has run it on an iPhone because nobody here
owns one.

---

## Where it is

It is the app on one phone now, not a proof that alarms fire. They still do -
close the app, lock the phone, put it down, and it buzzes at the right time -
and the rest has been built on top of that:

- the reset engine, ported from the desktop and verified against 3,440 cases
- the horizon scheduler that decides which alarms the OS gets told about,
  registered with `AlarmManager` and rebuilt on launch, on fire and on boot
- the desktop's own database schema in SQLite on the phone, and sync with the
  desktop through Google Drive or a WebDAV server, encrypted before anything
  leaves the device
- three tabs - what to do today, what money went where, how the phone is set
  up - and a form each for adding or changing a task and for writing money
  down, where a time is picked on a clock and a day on a calendar
- slips read on the phone, picked by hand or arriving on their own from a
  bank app's album, each waiting for one press before it is a row
- money coming in, read off K PLUS's and Krungthai NEXT's notifications, with
  the person's own money moving between accounts left out
- notifications with quiet hours applied, and six themes shared with the
  desktop
- a widget for the home screen in three sizes - the time, what is next, the
  rest of the day and what is still open further out - on the theme's colours
  or on a picture or a frame from a clip, with light that crosses it now and
  then

Not there yet:

- **money out without a slip.** A card payment or a direct debit comes with a
  bank's notification and no slip, and nothing reads it as spending yet. K
  PLUS's notices of money going out are read only to be compared with money
  coming in.
- **two old screens.** Sync settings and the slip reader are still views built
  by hand, on `Ui.kt`, which goes when they move.

What would have to change before anybody else uses it - other banks, a phone
without the desktop, Google's consent screen, a second language - is in
[LATER.md](LATER.md).

---

## Layout

```
shared/src/commonMain/kotlin/app/reup/
  core/    the reset engine, the scheduler, the palette, how money is printed,
           what the widget says (WidgetFace.kt)
  sync/    the database rows, sync, storage and crypto, the slip rules, and
           what each screen says (Face.kt)
shared/src/jvmTest/       the tests, most of them against the vector files in
                          resources/, which the desktop generates
shared/src/commonTest/    HorizonTest, WidgetFaceTest

app/src/main/java/app/reup/
  ui/                  the Compose screens, the models they are drawn from,
                       Kit.kt and FormKit.kt for the pieces they share
  MainActivity.kt      the host for the three tabs
  AddTaskActivity.kt   the host for the task form
  AddMoneyActivity.kt  the host for the money form
  Scheduler.kt         builds the queue, hands it to AlarmManager
  Alarmreceiver.kt     fires the notification, rebuilds the queue
  Bootreceiver.kt      reschedules after a restart or an app update
  Repo.kt              the one way into the database
  SlipWatch.kt         notices a new slip in a bank's album
  SlipOcr.kt           reads it
  BankNoticeListener.kt
                       reads a bank's notification of money coming in
  BankNotices.kt       keeps what it read until somebody says yes or no
  ReupWidget.kt        the home-screen widget: when it is drawn, and how
  WidgetLook.kt        its picture, its light, and the frame picked from a clip
  HomeRows.kt          the tasks as rows, for the Today tab and the widget alike
  SyncActivity.kt      sync settings, still hand-built
  ScanSlipActivity.kt  the slip reader, still hand-built
```

`commonMain` imports nothing from Android, nothing from the JVM and nothing from
a browser. That is what lets it compile for iOS, and CI is what stops it quietly
ceasing to be true.

---

## Building it

Android Studio brings the JDK, the SDK, Gradle and `adb`. Nothing else is
needed.

```
gradlew.bat :shared:jvmTest      # all of the interesting logic, and its vectors
gradlew.bat :app:installDebug    # onto a plugged-in phone
```

Two things worth getting right on a Windows machine with a small C: drive —
point the SDK at another drive during setup, and set `GRADLE_USER_HOME`
somewhere other than `C:\Users\you\.gradle`. Between them they run to 15–20 GB.

Skip the emulator. It cannot reproduce Doze, a phone maker's battery manager or
a reboot, which are the three things that actually break this app.

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

The other vector files work the same way: crypto, the sync engine, the merge
and the SQL the store runs are each pinned by what the desktop produced, so a
rule written in both languages cannot quietly come to differ. The slip tests run
on fixtures made from real slips with every name, account number and reference
replaced by a made-up one, and the notice tests on notices written the way the
real ones were, with made-up accounts; this repository is public, and a test has
no need for the real ones.

---

## Decisions

**Nothing runs in the background. Ever.** No foreground service, no periodic
work, no polling, no sockets. The app computes when it should ring, hands those
instants to the OS, and dies. The OS wakes it. Between the moment it closes and
the moment an alarm fires, it uses nothing at all — which on a phone with a worn
battery is the difference between an app that stays installed and one that gets
uninstalled. Two things can be switched on that bend this, slips and money
coming in, each off until somebody turns it on and each with a section below
saying what it costs. The widget does not bend it: it is drawn when something
on it changes, and otherwise by an alarm that does not wake the phone.

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

## Reading slips

Settings → อ่านสลิป reads a payment slip or a receipt from a screenshot, on the
phone, with nothing sent anywhere. The picture is turned black and white
(`SlipImage.kt`), read by Tesseract (`SlipOcr.kt`), and the text is turned into
an amount, a currency, a date and a reference by rules that refuse rather than
guess (`SlipText.kt`). Nothing is saved until a person presses save on the money
screen it opens.

The rules know how money, dates and labels are written in Thailand and in most
of the countries around it and beyond: baht and Buddhist years, `S$12.50`,
`Rp 150.000`, `1.234,56 €`, `Oct 8, 2026`, `2026年10月8日`. The phone's own
currency settles a sign several currencies share — `$`, `¥`, `kr` — and its
region settles whether `03/04` is March, except on a Thai slip, which is always
day first.

The APK carries Thai and English language data, which covers Thai and every
language written in Latin letters. For another script, put that language's file
from [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) into
`app/src/main/assets/tessdata/` — `jpn.traineddata`, `kor.traineddata`,
`chi_sim.traineddata`, `vie.traineddata` — and rebuild. Every file in that
folder is used, Thai first; the words those slips print are already in
`SlipText.kt`.

Money signs the English data has no letter for (₱ ฿ ₩ ₹ ₫) come back as a
letter or as nothing. `P500.00` is read as 500 in whatever currency the rest of
the slip, or the phone, says; a bare `500` with nothing beside it is refused.

### Slips that arrive on their own

Settings → สลิปเข้า Reup เอง. Bank apps such as K PLUS and Krungthai NEXT save
every slip into an album the moment a transfer goes through. With this on, the
app watches the albums picked there (`SlipWatch.kt`), reads each new slip with
the same rules, and puts it in the shade and at the top of the money tab, with a
save button when nothing on it is left to check. Nothing is written until that
button, or the form, is pressed.

What was typed into บันทึกช่วยจำ at the bank becomes the note, and when its
first word is a category — `อาหาร ข้าวมันไก่`, `อาหารทดสอบ` — that becomes the
category. A transfer between the payer's own accounts is recognised and never
offered as spending. The rules for all of it are in `SlipInbox.kt`, with tests.

Each waiting slip shows its own picture at the head of its row; tapping the
picture opens it whole, with two-finger and double-tap zoom, and with the row's
save, edit and don't-save underneath (`SlipPicture.kt`). The form a slip opens
in has the picture at the top. The picture is never copied: it is read from
where the bank's app saved it, by the gallery id the slip is kept under.

The memo is the one line on a slip no rule can check, so it is read twice: once
with the page, and once cut out on its own with a threshold of its own
(`SlipOcr.memoAgain`, `betterMemo` in `SlipText.kt`). On a real Krungthai slip
the page read ทดสอบ101 as เทดสอบ101 and the line on its own read it right. It is
still a recogniser reading a loopless Thai font: on 400 test slips made from a
real Krungthai and a real K PLUS slip, about three memos in four come back
exactly as typed, and most of the rest are a letter or a mark out. The note can
always be fixed on the form before saving.

It is one of two things in the app that wake up by themselves, and it is shaped
to cost next to nothing: off until switched on, woken by the system only when a
picture is added, and the recogniser runs only for a new picture inside a chosen
album. Opening the app reads anything a battery manager held back. It needs the
whole gallery — Android 14's "selected photos" never includes tomorrow's slip —
and says so when it does not have it.

### Money coming in

Settings → เงินเข้า Reup เอง. A bank's app saves a slip for money going out and
nothing for money coming in, but it posts a notification both ways. With this
on, and with Reup allowed to read notifications on the phone's own settings
page, the app reads K PLUS's รายการเงินเข้า and Krungthai NEXT's รับเงินสำเร็จ
(`BankNoticeListener.kt`), makes each an amount, a minute and a bank
(`BankNotice.kt`), and puts it under รอบันทึก on the money tab, beside the
slips, with a save button. Nothing is written until that button, or the form,
is pressed. The note says which bank it went into; neither notice prints who
paid, so ได้รับจาก is left for the form.

Money moved between the person's own accounts is not income, and is not shown.
Krungthai prints the account money came from, masked, and K PLUS prints the
person's own on every notice: when the two agree on the digits both show, the
money only moved. A slip to oneself for the same amount a few minutes either
side says the same, and is the only way money arriving at K PLUS from Krungthai
is known to be the person's own: K PLUS says nothing about where its money came
from. Money that left another account at the same moment for the same amount is
shown without its one-press save, as a maybe. The last notice read, and what
became of it, is under the switch: on a day when everything that came in was the
person's own money, that line is the only sign the feature is working. It also
says when a notice titled as money in could not be read, which is how a bank
changing its wording gets noticed.

Android cannot be asked for two apps' notifications, only for kinds of
notification, so a listener is sent everybody's. The manifest asks for as few
kinds as the system allows - never conversations, which is what a chat app's
messages are, and nothing ongoing - and the package is the first thing looked
at: anything not from the two banks, LINE's bank accounts included, leaves at
that line, unread. While it is on, Android keeps the app's process alive to hand
it notifications - memory rather than battery, since nothing runs until one is
posted - and that is the cost of the feature, and why it starts off. Switched
off in Reup, the listener asks to be let go, and no notification passes through
the app at all.

What is kept is an amount, a minute, the banks, the person's own accounts as
K PLUS masks them, and, for an hour while it is compared, the masked account
the money came from. No text from a notice, and no names.

---

## The widget

Hold the home screen, widgets, Reup - or Settings → วิดเจ็ต → วางบนหน้าจอหลัก,
on a home screen that can be asked. Two by two, four by two, four by four, or
anything between: the layout is cut for whatever size it is dragged to
(`ReupWidget.kt`).

What it says is the Today tab's, decided by the same functions
(`WidgetFace.kt`, with tests): the clock, the date, what is next or, when
nothing is left today, which round comes after; the rest of what is due within
a day; and, at four by four, what is still open further out - weeklies,
events - by when they run out, in amber in their last two days. A bar counts
what was ticked today against the day's load. A moon is up during quiet hours,
and a wallet with a number when slips or money in are waiting. A tap opens the
app on Today, and the wallet on the money tab. The month's money can be added
at four by four, and is off until it is: a home screen is looked at by whoever
is beside the phone.

**What it costs.** Nothing is drawn on a timer. The clock is the system's own
and ticks itself. Everything else is drawn when it changes - after a tick, a
sync, an alarm, a slip, a notice, a theme - and at the one moment something on
it would go stale by itself: a round passing, midnight, a weekly turning amber,
quiet hours starting or ending. That moment is an alarm that does not wake the
phone; it arrives the next time the screen is on, which is the next time
anybody could see the widget be wrong.

**The picture.** Settings → วิดเจ็ต → พื้นหลัง opens the system's photo picker,
which hands over one picture or one clip and nothing else - no permission, no
look at the rest of the gallery. A clip opens a slider to pick one frame from.
Either way it is kept as the app's own JPEG, no longer than 1440 pixels, and cut
to the widget's size when it is drawn; the original is not touched. Over a
picture the words are white on a scrim, so a bright one stays readable.

**The light.** A widget cannot play a video; it can animate a progress bar's
spinner, so the spinner is given frames instead (`widget_shine.xml`): a soft
band crossing the card in under a second, a glint, then five and a half seconds
of nothing - no frames, no timer, nothing awake. The home screen draws it, and
stops while it is not on show. It is off in Settings, and off whenever the
phone's own animations are.

---

## Samsung

This was first developed against a Galaxy A73, and Samsung's software is more
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

MIT, for the code here.

Two things bundled into the app are not this repository's and keep their own
licences, whose texts are in `third_party/`:

- IBM Plex Sans Thai and IBM Plex Mono, in `app/src/main/res/font`, under the
  SIL Open Font License 1.1
- icons from Lucide, redrawn as vector drawables in `app/src/main/res/drawable`
  (the `ic_` files other than `ic_ring`, `ic_done` and `ic_notify`), under ISC
