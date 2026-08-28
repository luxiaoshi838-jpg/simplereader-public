# v731 Android 16 install compatibility fix

v731 keeps the v730 functional contents (including the operation-log `LinearLayout.addView(View)` crash fix) and corrects the APK packaging pipeline.

Root cause of the Android 16 “incompatible” install error: the v730 APK was validly signed with v2/v3, but the ZIP layout was not 4-byte aligned after repacking. Android official `zipalign -c -v 4` reported failures for resources including `resources.arsc`, PNG and WebP entries.

v731 production order is fixed to:

1. apply the authorized binary/version changes;
2. remove stale signing metadata;
3. run Android official `zipalign -f 4`;
4. sign locally with Android official `apksigner` using the existing release keystore (v1 disabled, v2/v3 enabled);
5. require both `zipalign -c -v 4` and `apksigner verify --verbose --print-certs` to pass before delivery.

Final v731 verification:

- package: `com.simplereader.app`
- versionCode: `2098000731`
- versionName: `731`
- minSdk: 26
- targetSdk: 35
- APK Signature Scheme v2: true
- APK Signature Scheme v3: true
- signer certificate SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- `zipalign -c -v 4`: Verification successful
- operation-log helper DEX: `LinearLayout.addView(View)` only; the invalid `addView(View, LinearLayout.LayoutParams)` call is absent
- non-META comparison against corrected v730 functional contents: only `AndroidManifest.xml` differs (730 -> 731); no compression-method changes

Production APKs, keystores and passwords remain local and are not stored in the public repository.
