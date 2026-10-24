# Tel · ਤੇਲ

**Tel** (Punjabi/Hindi for *oil*) is an offline fuel, mileage and cost tracker for Android.

Log each fill-up in a few seconds: amount, rate and volume from the pump (enter any two and the third is calculated) plus the odometer. Tel works out distance, mileage, cost per km and your spending trends, all on the phone.

- **No internet permission.** The APK cannot open a network connection. CI fails the build if one appears.
- **No account, no analytics, no ads, no cloud.** Works in airplane mode.
- **Your data in a plain CSV file** that Tel keeps up to date, survives uninstalling, and opens in any spreadsheet.

## Install

Download the latest `tel-x.y.z.apk` from [Releases](../../releases) and open it on your phone. Android will ask you to allow installs from your browser or file manager. Requires Android 8.0 or newer.

## Using it

1. **First launch:** name your vehicle, choose fuel type, units and currency.
2. **Add fill-up.** The form, top to bottom:
   - **Date**: now, tap to change
   - **Tank full**: on by default; turn off for a partial fill
   - **Amount**, **Rate**, **Volume**: type any two and the third is calculated. The rate is prefilled from your last fill-up, so usually you only type the amount.
   - **Odometer**: one reading at the pump, with your previous reading shown underneath. A reading lower than an earlier fill-up (or higher than a later one) is an error.
   - **Notes** (optional)
   - **Missed logging a fill-up**: turn on if you forgot to record one since the last entry
3. **Save.** Only a completely empty entry is refused, so a missing odometer never stops you recording the cost.

No time at the pump? Take a photo of the odometer and the pump display with your phone's camera, and add the fill-up later, setting the date to when you filled up.

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

### The CSV copy

Tel can keep **one CSV file** of your choice up to date with all your vehicles and fill-ups (Settings → *Your data* → *Create CSV file*, or the reminder card on the dashboard).

- You pick the location in Android's own "save as" dialog. Making a folder just for it, such as `Documents/Tel`, is a good idea.
- Tel gets access to **that one file only**, not to a folder or your storage, and needs no storage permission. The access lasts across updates and restarts.
- The file is rewritten after every save, edit or delete. It is never blanked out automatically, not even by "Delete all data".
- It **stays on your phone when Tel is uninstalled**. After reinstalling, tap **I have a Tel CSV file from before** on the welcome screen to get everything back. Tel then keeps updating the same file.
- Tel's own database stays the working copy. Edits you make to the CSV elsewhere are overwritten on the next change; to bring them in, use *Restore from a file* first. Restoring tolerates spreadsheet edits such as reordered columns, added rows without ids, `4,086.10` and dates without seconds.
- If the file is moved or deleted, the dashboard says so and offers to choose a new file.
- "Not now" hides the reminder for a week; "Don't remind me" hides it for good.

The file holds one row per fill-up, with the vehicle's details on each row (a vehicle with no fill-ups gets a row of its own):

```
date,vehicle,odometer_km,amount,currency,rate_per_litre,volume_litres,tank_full,missed_previous_fill,notes,
distance_since_last_km,mileage_km_per_litre,cost_per_km,mileage_status,vehicle_fuel_type,…,vehicle_id,fill_up_id,created_at,updated_at
2026-10-25 08:41:00+05:30,My Car,143140,4086.1,INR,112.41,36.35,yes,no,,500,13.14,8.43,Measured,PETROL,…
```

Values are always in km, litres and price per litre, whatever the display units. The calculated columns are for reading and are ignored when restoring.

### Other options

- **Export a CSV now** and **Export all data (JSON)**: one-off files saved where you choose.
- **Restore from a file**: a Tel CSV, or a `.zip` backup from 1.0.x. Shows what's inside before you choose *Merge* or *Replace*.
- **Delete all data** requires typing `DELETE`.

The database lives in the app's private storage. Android cloud backup and device-to-device transfer are disabled for the app, so nothing is copied off the phone unless you export it or set up the CSV copy.

## Permissions

None. The CSV copy uses a single file you pick in the system dialog, so Tel never asks for storage, camera or location access. The CI build checks the final APK for network, camera, location and storage permissions.

## Project layout

```
app/src/main/java/app/tel/
  domain/            pure Kotlin, no Android: models, units, formatting
    csv/             the CSV file format (read and write)
    calc/            mileage engine, dashboard statistics, amount/rate/volume calculator
    validation/      input checks (errors block saving, warnings ask)
  data/              Room/SQLite schema + migrations, repositories, CSV copy, export/restore
  ui/                Jetpack Compose screens: dashboard, fill-up form, history, detail, settings, vehicles
```

UI → ViewModel → domain → repositories → SQLite. The domain layer has no Android dependencies and is covered by JVM unit tests.

## Build

Requirements: JDK 17, Android SDK (API 35). Android Studio works, or:

```bash
./gradlew testDebugUnitTest          # unit tests: mileage, amount/rate/volume, CSV format, units, validation
./gradlew connectedDebugAndroidTest  # on a device/emulator: database, migration, CSV copy & restore, UI flow
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

Every push to `main` runs both test suites on GitHub Actions and publishes a signed APK as a GitHub Release. See [PACKAGING.md](PACKAGING.md) for signing with your own key.

## Upgrading from 1.0.x

Installing a new version over an old one keeps your data. The first versions read values from photos and recorded before/after odometer readings and fuel levels. On upgrade (and when restoring an old backup):

- the odometer becomes the "before" reading, or the "after" reading if "before" was empty;
- fuel levels, photo-reading results and stored photos are removed;
- old entries are marked **Tank full**, because the old switch defaulted to off and the old maths treated those fills as refilled to the same level. Turn it off on any entry that was really a partial fill.

## Limitations

- Android only. The domain layer (mileage, units, validation) is plain Kotlin and could move to a Kotlin Multiplatform module for desktop or iOS.
