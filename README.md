# Tel · ਤੇਲ

**Tel** (Punjabi/Hindi for *oil*) is an offline fuel, mileage and cost tracker for Android.

Photograph your dashboard before and after filling up, photograph the pump display, check the numbers the app read, and save. Tel works out distance, mileage, cost per km and your spending trends. Everything happens on the phone.

- **No internet permission.** The APK cannot open a network connection. CI fails the build if one appears.
- **No account, no analytics, no ads, no cloud.** Works in airplane mode.
- **On-device OCR** using the bundled ML Kit text model that ships inside the APK.
- **OCR is optional.** Every value can be typed or corrected by hand.

## Install

Download the latest `tel-x.y.z.apk` from [Releases](../../releases) and open it on your phone. Android will ask you to allow installs from your browser or file manager. Requires Android 8.0 or newer.

## Using it

1. **First launch:** name your vehicle, choose fuel type, tank size, units and currency.
2. **Add refuelling:** add up to three photos. For each, either *Take photo* (the system camera) or *Choose photo* (the system photo picker).
   1. Dashboard **before** filling (odometer, fuel level)
   2. Dashboard **after** filling
   3. The **pump display** (amount, litres, price)
3. **Read photos.** Tel shows every value it found and where it came from: *Read from photo*, *Needs review*, *Edited* or *Calculated*. Tap a value to correct it, or tap one of the "other numbers in the photo".
4. **Save.** The dashboard updates immediately.

The first refuelling only sets the starting point. Mileage appears from the second one.

Tip: switch on **Filled the tank to full** whenever the pump clicks off. Two full fills in a row give a *measured* mileage instead of an estimate.

## How the numbers are calculated

Distance is the odometer now minus the odometer when you left the previous fill. Fuel used over that distance is worked out with the best method the data allows. The method and its quality are shown in each refuelling's details.

| Quality | When | Fuel used |
|---|---|---|
| Measured | Both fills to full, or the gauge after both fills reads the same | Fuel added now (pump-measured) |
| Estimated | Gauge after the fills differs and tank capacity is known | Fuel added − (gauge change × tank capacity) |
| Estimated | No fuel-added figure, but gauges and capacity are known | Gauge drop × tank capacity |
| Partial | Only fuel added is known | Fuel added (assumes same level as last time) |
| Insufficient | Odometer or fuel missing | Not calculated |

Fuel gauges are not linear, so gauge-based figures are always labelled *Estimated*. Dashboard averages are sums, not averages of averages: average mileage = total distance / total fuel used, average price = total spent / total litres, cost per km = spend on fills with a known distance / that distance.

Values are stored once, in kilometres, litres and price per litre, as exact decimals (no floating-point money). Switching between **Metric** and **US** (miles, US gallons at 3.785411784 L, MPG) only changes what is displayed.

## How the photo reading works

```
photo → upright + downscale + strip EXIF (incl. GPS)
      → 5 variants: original · contrast-stretched & sharpened · Otsu threshold · inverted · segment gaps closed
      → on-device ML Kit text recognition on each
      → parsers → confidence → you review
```

- **Pump display:** Tel collects every number and looks for the three that satisfy *litres × price ≈ amount*, allowing for display rounding. It works with any layout. Labels like "Amount", "₹", "Litres", "Rate" and "₹/L" refine the choice, and density readings are ignored. If the decimal point was missed ("408610"), Tel tests 4086.10 against the arithmetic. If nothing adds up, the values are flagged *Needs review*.
- **Dashboard:** the odometer is the number next to "ODO" or followed by km/mi (never km/h, trip meters or range). The fuel level is a "%" value or a fraction such as 3/4. Many cars only have a needle gauge; in that case type the level in.
- Values seen in more than one variant get higher confidence. Blurry or dark photos get a hint to retake them.
- **Settings → Photo reading** controls how eagerly values are marked *Needs review*.

## Your data

Settings → *Your data*:

- **Back up (data + photos)** creates one `.zip` file at a location you choose. **Restore** shows what's inside (date, vehicles, refuellings, photos) before you choose *Merge* or *Replace*.
- **Export history (CSV)** and **Export all data (JSON)**.
- **Delete all data** requires typing `DELETE`.

The database and photos live in the app's private storage, encrypted at rest by Android. Android cloud backup and device-to-device transfer are disabled for the app, so nothing is copied off the phone unless you export it.

Photos are stored per refuelling as `files/data/vehicles/<vehicle>/transactions/<refuelling>/{before,after,pump}.jpg`. Each one can be deleted on its own without losing the values.

## Permissions

None that need approving. Photos are taken through the system camera app and chosen through the system photo picker, so Tel never asks for camera, storage or location access. Network permissions added by libraries are stripped in the manifest, and the CI build checks the final APK.

## Project layout

```
app/src/main/java/app/tel/
  domain/            pure Kotlin, no Android: models, units, formatting,
    calc/            calculation engine + dashboard statistics
    validation/      input checks (errors block saving, warnings ask)
    ocr/             number extraction, pump-meter and dashboard parsers
  ocr/               image preprocessing (pure) + ML Kit adapter + OCR service
  data/              Room/SQLite schema, repositories, photo store, backup/export
  ui/                Jetpack Compose screens: dashboard, refuel, history, detail, settings, vehicles
```

UI → ViewModel → domain → repositories → SQLite. The calculation engine and parsers have no Android dependencies and are covered by JVM unit tests.

## Build

Requirements: JDK 17, Android SDK (API 35). Android Studio works, or:

```bash
./gradlew testDebugUnitTest        # unit tests: calculations, units, validation, OCR parsing
./gradlew connectedDebugAndroidTest  # on a device/emulator: database, backup/restore, UI flow
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

Every push to `main` runs both test suites on GitHub Actions and publishes a signed APK as a GitHub Release. See [PACKAGING.md](PACKAGING.md) for signing with your own key.

## Limitations

- Android only. The domain layer (calculations, units, parsers) is plain Kotlin and could move to a Kotlin Multiplatform module for desktop or iOS; the UI, OCR adapter and storage are Android-specific.
- No automatic perspective correction or cropping. Fill the frame with the display for best results.
- OCR uses the Latin-script model. Digits on Indian, European and US pumps are fine; labels in other scripts are ignored, and the arithmetic check still works.
- Seven-segment displays under glare can still be misread. That's why every value is shown for review.
