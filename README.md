<p align="center">
  <img src="docs/logo.svg" alt="AA Bridge" width="480" />
</p>

AA Bridge brings Android Auto to cars whose screen only supports CarbitLink (also called EasyConnection) or QDLink phone mirroring.

The app runs on your phone. It starts a local Android Auto session and sends it to the car, over a USB cable or wirelessly over Wi-Fi Direct. Touches on the car screen go back to Android Auto.

<p align="center">
  <img src="docs/screenshots/android-auto-starting.webp" alt="Android Auto starting on the screen of a 2026 Geely Emgrand" width="640" />
</p>

## Screenshots

<p align="center">
  <img src="docs/screenshots/home-searching.png" alt="AA Bridge looking for the car" height="440" />
  <img src="docs/screenshots/nearby-cars.jpg" alt="Choosing the car from the Nearby cars list, with the car screen on its phone-link page" height="440" />
  <img src="docs/screenshots/home-connected.jpg" alt="AA Bridge connected, with Android Auto on the car screen" height="440" />
</p>
<p align="center">
  <img src="docs/screenshots/car-screen.jpg" alt="Android Auto with Google Maps and media on the car screen" width="640" />
</p>
<p align="center"><sub>2026 Geely Emgrand (head unit EC-KY-4350), connected wirelessly.</sub></p>

## Will it work in my car?

- **Tested:** 2026 Geely Emgrand (Egypt, head unit EC-KY-4350), over cable and wireless. One other CarbitLink head unit over cable. A 2026 Geely Emgrand with QDLink over cable.
- **Likely to work:** cars from Geely, Chery, Jetour, BAIC, JAC, GAC and Proton whose screen has CarbitLink, EasyConnection or a "phone link" app. This list comes from adapter listings and owner guides, not from our own tests.
- **Tried it in your car?** Open an [issue](../../issues) with the car model and year, the name of the phone-link app on the car screen, cable or wireless, and whether it worked.

## What you need

- An Android 10 or newer phone with Android Auto
- A car whose screen has CarbitLink, EasyConnection or QDLink
- A USB data cable, or Wi-Fi turned on for wireless

## Download

Download [AA-Bridge.apk](https://github.com/alimorgaan/AA-Bridge/releases/latest/download/AA-Bridge.apk), the latest release. Android asks once to allow installs from your browser or chat app. The app checks for updates by itself.

## Setup

The app walks you through this the first time:

1. Allow notifications, location and the microphone.
2. Turn on Android Auto developer mode: open Android Auto settings and tap Version 10 times.
3. Start Android Auto's server: tap ⋮, then Start head unit server.
4. Connect to your car:
   - **Cable:** plug the phone into the car's USB port, then choose AA Bridge and Always.
   - **Wireless:** tap Connect without a cable, then tap your car. Keep the car screen on the phone-link page.

## Every drive

- **Cable:** plug in. Android Auto opens on the car screen by itself.
- **Wireless:** open AA Bridge. It finds your car and connects. You can close the app after that. With Connect when I get in the car turned on, it starts by itself.

Plugging in the cable while connected wirelessly switches over without restarting Android Auto. Music, navigation voice and calls play through the phone's Bluetooth to the car, so keep the phone paired with the car.

## Options

In the app's Settings:

- **Sharper picture:** draws Android Auto at 1080p instead of 720p. It looks crisper on wide car screens and works the phone harder. If the phone reports severe heat, Android Auto restarts once at 720p and stays there for the rest of the drive.
- **Car buttons:** while connected, press a button on the car or the steering wheel. It appears in the list, and you choose what it does: back, home, voice assistant, play or pause, next, previous, answer, end call, or close Android Auto. Back goes back and Home closes Android Auto until you change them. Buttons that already work through the car's Bluetooth don't need setting.
- **Connect when I get in the car:** appears once a car is remembered for wireless. Choose the car from your paired Bluetooth devices, and the wireless connection starts when the phone joins it. Android does not give the microphone or location to a connection started this way, so open AA Bridge once during the drive if the voice button can't hear you. On Android 11 and 12 a connection started this way can't scan for the car at all, so a notification asks for one tap instead.

When the car connects, its screen opens on AA Bridge's home screen: the time, the speed, music buttons, and tiles. The car's Home button, or Exit in Android Auto, comes back to it. (Advanced > Open on the car home screen, off, opens Android Auto straight away as before.)

Every one of these screens has a small pill at its left edge with Home (⌂) and, where it applies, Back (←), so nothing depends on the car's own buttons, which some cars keep for themselves. Android Auto gets a Home button there too (Advanced > Home button over Android Auto).

- **Open Android Auto.**
- **YouTube** and **Claude:** each opens as a web page on the car screen. Nothing to set up, the phone stays free, and sound plays through the phone as usual. To sign in, use Settings > YouTube on the car screen (or Claude) on the phone, where there is a keyboard; the car then stays signed in. Google may refuse sign-in inside an app, in which case YouTube still works signed out. The car's Back button goes back a page. Advanced > Extra web page adds a tile for any other address.
- **Apps:** the phone's apps as a grid. The one you pick opens on the car screen itself, laid out for it, while the phone stays free. The car's Back button is the app's Back. This needs [Shizuku](https://shizuku.rikka.app/), a free app that lends AA Bridge adb's rights: install it, start it (it walks you through wireless debugging, and has to be started again after the phone restarts), then tap Apps on the car screen under Settings in AA Bridge to allow it. Some apps refuse to run on a second screen, and the keyboard appears on the phone.
- **Phone screen:** a picture of whatever is on your phone. The phone has to be unlocked and stay on. Each drive, the phone asks once to allow screen sharing; tap the AA Bridge notification to see the question. Touch from the car works when Shizuku is set up; without it this is view-only. Web pages and the phone screen are covered while the car moves, with sound still playing, unless you turn that off in Advanced.

With Connect when I get in the car set, a wireless link also stops as soon as the phone leaves the car's Bluetooth, and its notification goes with it. Android requires the notification while a link is running.

Under Advanced:

- **Side strip next to Android Auto** (off): a strip with the time, the speed and a Home button. Android Auto is drawn for the space that is left.
- **Speed in the corner of Android Auto** (off).
- **Weather on the car home screen** (off): shows the temperature; it sends your position, rounded to about a kilometre, to open-meteo.com.

- **Parked detection** (on): Android Auto is told the car is parked only while GPS shows it stopped, so the keyboard works then. While the car moves, or without a GPS fix, the normal driving limits apply. There is no way to report the car as always parked.
- **Remember settings per car** (on): screen, video and car button settings are kept for each car and come back when that car connects.
- **Lower video when the phone overheats** (on): the heat step-down described above.

## If something goes wrong

- **"Android Auto needs a tap":** Android Auto's server stops when the phone restarts. Tap the notification, then ⋮ and Start head unit server.
- **Stuck on "Starting…":** go to Settings → Apps → Android Auto → Force stop, then start the server again.
- **The QDLink app opens instead:** go to Settings → Apps → QDLink → Open by default, clear it, then plug in again and choose AA Bridge.
- **Can't find your car:** make sure the car screen is on and showing the phone-link page, Wi-Fi is on, and the phone is close by.

## Known limits

- Android Auto's developer mode is not an official feature, so an Android Auto update can break it.
- QDLink cars work over cable only.

## Building

Needs JDK 17 and the Android SDK.

```bash
./gradlew assembleDebug
```

Release builds are signed only when `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) is in the project root. It is gitignored.

## Credits

Based on [CarAALink](https://github.com/JObersi10/CarAALink) by JObersi10. The Android Auto head unit certificate comes from aasdk, see [PROVENANCE.md](app/src/main/assets/aa/PROVENANCE.md).

## License

GPL-3.0, see [LICENSE](LICENSE).

Android Auto is a trademark of Google LLC. AA Bridge is not affiliated with Google or the makers of CarbitLink or QDLink.
