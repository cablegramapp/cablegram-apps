# Manual metadata device checks

2026-10-03. Pixel 8 Pro and Chromecast; isolated `.metatest` app packages and two generated three-second video fixtures in a separate household.

Passed on the phone: cancel, normal and numeric titles, saving all four fields, clearing year and summary, source filename/video preservation, offline save followed by force-stop/reopen, reconnect plus Retry, concurrent edit rejection, and explicit review/save recovery. Wi-Fi and mobile data were both restored to their original enabled settings after the offline check.

The second writer was simulated through the authenticated catalog API while the real phone editor was open. Two physical phones and a live artwork provider were not covered.

TV testing found a staleness signature that omitted year, overview, and type. The fix adds these fields without changing the catalog protocol. Regression tests cover metadata-only changes and intentional clearing. Real-device follow-up verifies a focused card updates its year and summary while its title stays the same, and verifies movie/series transitions and cleared metadata.

Validation: phone JVM 157 tests, TV JVM 115 tests, Android lint and debug APK builds pass. Backend deployment and live patch requests succeeded before installing the regular phone/TV builds. Regular APK updates use `adb install -r` and retain existing app data; prior installed APKs are backed up for rollback. Temporary test packages and their fixture household are removed after testing.

Build a separate device-test package with `-PCABLEGRAM_APP_ID_SUFFIX=.metatest`; omit the property for the regular application ID. This avoids overwriting an owner's app during fixture testing.
