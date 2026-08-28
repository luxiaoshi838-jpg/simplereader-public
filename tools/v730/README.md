# v730 binary overlay

v730 is based on the signed v729 overlay and fixes one Android 16 crash in the operation-log detail dialog.

## Crash

The v729 `classes5.dex` helper encoded this non-existent virtual method signature:

`LinearLayout.addView(View, LinearLayout.LayoutParams)`

On Android 16 / API 36 this throws `java.lang.NoSuchMethodError` when the user taps an operation-log entry and `showDetail()` builds the dialog.

## Fix

The helper now uses the platform-supported one-argument overload:

`LinearLayout.addView(View)`

The binary patch reuses the existing `(View)V` proto already present in `classes5.dex` and removes the unused LayoutParams register from the two `invoke-virtual` instructions. No catalog, pagination, checkpoint, foreground-service, wake-lock, reader UI, or reader behavior code is changed.

Expected non-signature differences from v729:

- `AndroidManifest.xml` — version 729 -> 730 only
- `classes5.dex` — operation-log detail addView compatibility fix only

## Android 16 signing requirement

The first v730 delivery attempt was incorrectly packaged with only the APK Signature Scheme v2 signer block. The installable replacement keeps the exact same 1,785 ZIP entries but is re-signed with both the v2 (`0x7109871a`) and v3 (`0xf05368c0`) signer blocks, matching the installable v729 signing structure and the existing release certificate.

Run `verify_signing_block.py <apk>` before delivery. A production APK must contain both v2 and v3. A v2-only package is rejected by the delivery guard and must not be shipped.

Production APKs and signing material are not stored in the public repository.
