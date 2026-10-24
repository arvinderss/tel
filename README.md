# Tel · ਤੇਲ

**Tel** (Punjabi/Hindi for *oil*) is an offline fuel, mileage and cost tracker for Android.

Log each fill-up in a few seconds: amount, rate and volume from the pump (enter any two and the third is calculated) plus the odometer. Tel works out distance, mileage, cost per km and your spending trends, all on the phone.

- **No internet permission.** The APK cannot open a network connection. CI fails the build if one appears.
- **No account, no analytics, no ads, no cloud.** Works in airplane mode.
- **Photos as proof.** Attach up to 3 photos (pump display, dashboard, receipt) to any fill-up. They're stored, not read.

## Install

Download the latest `tel-x.y.z.apk` from [Releases](../../releases) and open it on your phone. Android will ask you to allow installs from your browser or file manager. Requires Android 8.0 or newer.

## Using it

1. **First launch:** name your vehicle, choose fuel type, units and currency.
2. **Add fill-up.** The form, top to bottom:
   - **Date**: now, tap to change
   - **Tank full**: on by default; turn off for a partial fill
   - **Amount**, **Rate**, **Volume**: type any two and the third is calculated. The rate is prefilled from your last fill-up, so usually you only type the amount.
   - **Odometer**: one reading at the pump, with your previous reading shown underneath. A reading lower than an earlier fill-up (or higher than a later one) is an error.
   - **Photos** and **Notes** (optional)
   - **Missed logging a fill-up**: turn on if you forgot to record one since the last entry
3. **Save.** Only a completely empty entry is refused, so a missing odometer never stops you recording the cost.

### Amount, rate and volume

Once two are given, the third is calculated and marked *Calculated*. If you then type the third yourself, the value you entered *least recently* is recalculated instead, so your latest entries always win. A prefilled rate counts as the oldest. Calculated values are rounded the way pumps show them (2 decimals; 3 for US gallons).

## How mileage is calculated

Mileage is always measured, never estimated, using the full-tank-to-full-tank method:

```
km/L = (odometer at this full tank − odometer at the previous full tank)
       ÷ all litres added after the previous full tank, up to and including this one
```

- **Partial fills** add their litres and cost to the stretch and show "Partial fill"; the figure appears at the next full tank.
- **Missed a fill-up** starts a new stretch, because the unlogged fuel is unknown. The fill-up still counts towards spending.
- A missing odometer or volume inside a stretch makes it unmeasurable; the next full tank starts over.
- Dashboard averages are sums, not averages of averages: mileage = total measured distance ÷ total fuel in those stretches; cost per km = money spent in those stretches ÷ their distance; average price = total spent ÷ total litres.

Values are stored once, in kilometres, litres and price per litre, as exact decimals (no floating-point money). Switching between **Metric** and **US** (miles, US gallons at 3.785411784 L, MPG) only changes what is displayed.

## Your data

Settings → *Your data*:

- **Back up (data + photos)** creates one `.zip` file at a location you choose. **Restore** shows what's inside (date, vehicles, fill-ups, photos) before you choose *Merge* or *Replace*.
- **Export history (CSV)** and **Export all data (JSON)**.
- **Delete all data** requires typing `DELETE`.

The database and photos live in the app's private storage, encrypted at rest by Android. Android cloud backup and device-to-device transfer are disabled for the app, so nothing is copied off the phone unless you export it.

Photos are stored per fill-up as `files/data/vehicles/<vehicle>/transactions/<fill-up>/photo-<id>.jpg`, re-encoded without location or camera metadata. Each one can be deleted on its own without losing the values.

## Permissions

None that need approving. Photos are taken through the system camera app and chosen through the system photo picker, so Tel never asks for camera, storage or location access. The CI build checks the final APK for network permissions.

## Project layout

```
app/src/main/java/app/tel/
  domain/            pure Kotlin, no Android: models, units, formatting
    calc/            mileage engine, dashboard statistics, amount/rate/volume calculator
    validation/      input checks (errors block saving, warnings ask)
  data/              Room/SQLite schema + migrations, repositories, photo store, backup/export
  ui/                Jetpack Compose screens: dashboard, fill-up form, history, detail, settings, vehicles
```

UI → ViewModel → domain → repositories → SQLite. The domain layer has no Android dependencies and is covered by JVM unit tests.

## Build

Requirements: JDK 17, Android SDK (API 35). Android Studio works, or:

```bash
./gradlew testDebugUnitTest          # unit tests: mileage, amount/rate/volume, units, validation
./gradlew connectedDebugAndroidTest  # on a device/emulator: database, migration, backup/restore, UI flow
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

Every push to `main` runs both test suites on GitHub Actions and publishes a signed APK as a GitHub Release. See [PACKAGING.md](PACKAGING.md) for signing with your own key.

## Upgrading from 1.0.x

The first versions read values from photos and recorded before/after odometer readings and fuel levels. On upgrade (and when restoring an old backup):

- the odometer becomes the "before" reading, or the "after" reading if "before" was empty;
- fuel levels and photo-reading results are dropped; photos are kept;
- old entries are marked **Tank full**, because the old switch defaulted to off and the old maths treated those fills as refilled to the same level. Turn it off on any entry that was really a partial fill.

## Limitations

- Android only. The domain layer (mileage, units, validation) is plain Kotlin and could move to a Kotlin Multiplatform module for desktop or iOS.
