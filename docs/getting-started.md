# Nocked and Anchored: getting started

Anchored is the small box on your bow sight. Nocked is the app on your phone. Together they count your shots, show the angle and the cant of the bow, light the pin when it gets dark, and keep a diary of your training. This guide takes you from the box in your hand to your first scored end. It assumes nothing: no computer, no technical words. Where a word in the app matters, it is written *like this*, exactly as it appears on the screen.

## What you need

- The Anchored sight, mounted on your bow, with a charged battery. It charges through the USB-C socket with any phone charger; a full charge takes about two hours. The small light near the socket shows charging.
- An Android phone (Android 8 or newer) with Bluetooth. iPhones are not supported.
- The Nocked app. You get it as an APK file from whoever built your sight (or from the project page). Open the file on the phone and allow the installation when Android asks; this needs the one-time permission "install unknown apps" for the app you opened the file with, usually your browser or files app.

## 1. Wake the sight

The sight sleeps while the bow lies still, to save its battery. **Pick up the bow and move it**; the sight wakes within a second and stays awake while the bow moves, and for five minutes after. Whenever the app says it cannot find the sight, this is the first thing to check: the bow was lying still.

## 2. Open the app and choose your sight

Open Nocked. On the first start it asks for **Bluetooth** (and on newer phones "Nearby devices"); allow it, the app cannot find the sight without it. Android 13 and newer also ask about **notifications**; allowing them lets the app tell you about the session while the phone is in your pocket. You can say no, the app works without them.

Then a window called **Connect a sight** opens. It lists every Anchored sight in range that is awake, with its name and battery. If the list stays empty, move your bow. **Tap your sight.** From now on the app connects to this sight and no other, even when club mates' sights are in the same room. (If you ever change your mind: *Settings → Sights → Connect a new sight*.)

## 3. Name it and say which bow it is on

Once the app has talked to the sight for the first time, a window called **A new sight** appears. Two things:

- **Name of this sight**: anything you like, for example the bow's name. It helps when you have more than one sight.
- **Bow**: *Compound*, *Recurve*, *Barebow* or *Traditional* (hunting bow, longbow). Pick the one you shoot. The app then sets the sight up for that bow: how much cant counts as cant, whether the pin blinks when you cant, and for bows without a sight it switches the light off. Every one of these can be changed later under *Settings*, so a wrong choice costs nothing.

Tap **Done**. The status screen now shows the sight: battery, light, cant, last shot.

## 4. Calibrate the cant once

The sight has to learn how it sits on your bow. This takes a minute and is done once:

1. Go to *Settings → Cant and aiming range → Calibration* and tap **Calibrate**.
2. **Step 1:** put the bow in a stand or hold it very still, level it (the bubble in your sight, or just by eye), and aim straight ahead. Tap **Measure** and keep the bow still until the app says so.
3. **Step 2:** keep the bow level but aim clearly upwards, arrow tip up, at least 20 degrees, like shooting at a far target. Tap **Measure** again, keep still.
4. Tap **Done**.

Check it later during shooting: with the bubble centred, the cant reading on the status screen should stay near 0°.

## 5. Shoot

Nothing to start: the first shot starts a session by itself, and the sight counts every shot. After each end, the app asks you for the scores. The **Scoring** card on the *Training* tab is a keypad: tap the arrows in any order (**X**, **10**, **9** … **M** for a miss), then **Save**. If you prefer, **Score from photo** photographs the face and marks the arrows for you once the app has learned to recognise them (see the end of this guide).

Two things worth knowing:

- **Distance**: tap the distance on the Training tab before the first end if the app did not recognise it. The sight remembers the aiming angle per distance and warns with a double or triple blink when the angle does not fit the distance, so an arrow does not fly over the target.
- **Hints**: under the last end the app says what the numbers show, for example a group sitting left, an unsteady hold, a canted bow. Always compared with your own earlier ends, never with anybody else.

A session ends by itself after an hour without a shot, or with **End session** on the Training tab.

## 6. What the light does

With a compound or recurve sight, the pin's UV light comes on by itself when it is dark and a session is running (that is, after the first shot, or after **Start session now**). It blinks when you cant the bow, faster the more you cant; one double blink or triple blink while aiming means the aiming angle is too low or too high for the distance you set. *Settings → LED* changes all of this; *On* lights it all the time, *Off* never.

## 7. Afterwards: the history

The *History* tab lists every session with its ends, average and X count, and shows your progress over time. Everything stays on your phone; the sight keeps a copy of the sessions, so the app can pick them up again after a phone change. **Backup** on the History tab saves everything to a file, **CSV** exports it for a spreadsheet.

## Everyday things

- **Battery**: the status screen shows it. The sight warns when it gets low. Charging through USB-C; the sight keeps working while it charges.
- **Not connected**: move the bow and tap **Connect** on the *Status* tab. The app also reconnects on its own when you save scores.
- **Phone in the pocket**: with the session notification allowed, the connection stays alive and the notification shows the running end.
- **A second sight, or a club mate's**: *Settings → Sights → Connect a new sight*. The app never connects to a sight it has not been introduced to.
- **Sharing a sight in the club**: *Settings → Mode* lets several archers use one sight with their own names and lets a trainer see everyone; see the project page for that.

## Scoring from photos and the arrow detector

**Score from photo** on the Training tab photographs the face. You mark the centre of the face and four points on the edge of the blue ring, then the arrows where they enter the face; the app reads the rings. Once enough photos have been collected (*Settings → Photo scoring → Collect training data*), a model can be trained on a computer that finds the face and the arrows on its own; the app then proposes the marks, and you only check them. That part is described on the project page under "Detection models".

## If something does not work

| What you see | What to do |
|---|---|
| The sight is not in the list of *Connect a sight* | Move the bow, wait two seconds, look again. Check that Bluetooth is on and the app has the Bluetooth permission (Android settings → Apps → Nocked → Permissions). |
| *Connected. Waiting for data from the sight…* for a long time | Move the bow once; if nothing changes, tap *Connect* again. |
| The cant reading is far from 0° with a level bow | Calibrate again (section 4). |
| Shots are not counted, or counted twice | *Settings → Shot detection and sessions*: raise the shot threshold if the sight counts bumps, lower it if it misses shots. |
| The light does not come on | It only lights when it is dark **and** a session is running. Set *LED* to *On* in the settings to test it. For a barebow or traditional bow it is off on purpose. |
| The app lost its settings after an update | The debug version of the app and the signed version cannot be installed over each other. Install the signed APK once by hand; later versions update over it. |

Enjoy your training.
