# Before anybody else uses it

Reup is built for one person, with her two banks, her Google project and her
desktop, and in places it shows. None of that is wrong for her. This is the
list of what would go wrong first for somebody else, and what to do about it,
kept here so that it is not found again one bug report at a time.

---

## It assumes the desktop

The phone was built as the desktop's other half, and a few things only the
desktop does:

- **Categories.** The phone never seeds them; they arrive by sync. A phone on
  its own files every expense under `other` and shows no chips.
- **The unit.** Until the desktop has said which currency the books are kept
  in, the phone counts in baht, and says so.
- **Quiet hours** are set on the desktop and arrive by sync.
- **Sync itself** needs the pairing code, and the desktop is what makes one.

Somebody with only the phone needs the nine categories seeded on first launch,
a currency to pick, quiet hours that can be set here, and a pairing code the
phone can make - or the desktop, set up first.

---

## Money coming in

- **Other banks.** `BankNotice.kt` reads K PLUS and Krungthai NEXT and nothing
  else, because every bank words its notifications its own way and the reader
  has to know which number is the amount and which is the balance. Another
  bank is its package name, a reader of a few lines, and a test written from
  one real notification. Take the package name from the bank's Play listing;
  never guess it.
- **A bank nobody has written a reader for.** Let the person pick their bank
  apps, the way albums are picked for slips, and read those with a general
  rule - an amount before บาท, near words for money coming in - whose rows never
  get the one-press save. The picking is what keeps "no other app is read"
  true.
- **Money only moving between two of somebody's accounts.** The surest check
  needs one bank that prints where money came from and one that prints the
  person's own account. Each new bank has to be looked at for which of the two
  it does, if either.

---

## Slips

- **Album suggestions** (`SlipWatch.BANKS`) are the names Thai banks' and
  wallets' apps give their albums. Picking by hand always works; the
  suggestion is only what makes the first time easy.
- **Language data.** The APK carries Thai and English. Slips in Japanese,
  Korean, Chinese or Vietnamese need that language's file built in (see the
  README) or, for a store build, fetched when the phone first needs it.

---

## Google

- **The client id** comes from `local.properties`, but the manifest's redirect
  scheme is the same id written backwards, by hand. Somebody building with
  their own id has to change both, and until they do, the manifest carries one
  that is not theirs. Generating the scheme from the same property, through a
  manifest placeholder, would make it one setting.
- **The consent screen.** While the Google Cloud project is in Testing, only
  the test users listed there can sign in, and with the Drive scope a sign-in
  lapses after seven days. It has to be published before anybody else uses
  Drive. `drive.appdata` is non-sensitive, so that means the basic
  verification, not the long review.

---

## The widget

- **Its rows were fitted on one phone.** How many rows fit is worked out from
  the widget's height in `ReupWidget`, with Thai text at about one and a half
  times its size; a home screen with other cell sizes, or a phone whose Thai
  font is taller, can cut the last row or leave a gap. A widget cannot measure
  its own text, so the numbers are the place to change.
- **Its font is the phone's.** A home screen will not load a font out of
  another app, so the widget is never in IBM Plex, whatever the app is in.

---

## A release

- **One language.** Every word on screen is Thai, written in the Kotlin rather
  than in resources. A second language starts with moving them out.
- **Installing from an APK.** From Android 13, notification access can be
  greyed out for an app installed outside a store until "allow restricted
  settings" is pressed on its page in the phone's settings. Anybody given an
  APK will meet that on the first day.
- **A store listing** needs a privacy policy that says what is read - two
  banks' notifications, the albums picked, and the gallery permission that
  takes, and the one picture or clip picked for the widget - and that none of
  it leaves the phone except through the person's own sync. Google Play's rules on notification access, photo access and exact
  alarms want reading before anything is submitted.
- **`applicationId`** is `app.reup`, and after the first release it can never
  change.
