# Kern PDF bridge (kernkraft native engine)

Kern Android's PDF editor and tools call kernkraft, the shared Rust PDF
library of [verycareful/kernkraft](https://github.com/verycareful/kernkraft), through
JNI. kernkraft is AGPL-3.0, like Kern Android, so the bridge files carry the AGPL-3.0
header only.

## The two halves

| Side | File | Symbol |
|---|---|---|
| Kotlin | [KernkraftPdf.kt](KernkraftPdf.kt) | `dev.kern.pdfbridge.KernkraftPdf` |
| Rust | `crates/kernkraft-jni` in kernkraft | `Java_dev_kern_pdfbridge_KernkraftPdf_*` |

The natives are bound by name. R8 keeps them through the native-methods rule of
`proguard-android-optimize.txt`, which `build.gradle.kts` uses. Every call is one
JSON object in and one JSON object out; `crates/kernkraft-pdf-engine/src/` in kernkraft
holds the request types, and requests with unknown fields are refused.

The native code is the only one in the app: every other format is pure Kotlin/JVM.
kernkraft draws every PDF page Kern shows, and reads its text, links and annotations.
Android's own PDF renderer is used only for the page count at open, for page widths, and
to show the pages of a file kernkraft cannot open.

## Building the .so

`libkernkraft_jni.so` is not checked in (`jniLibs/` is gitignored). CI builds it with
`.github/actions/build-pdf-bridge`, from the kernkraft release tag pinned in that action's
`kernkraft-ref` input (fetched as a tag, so a branch of the same name is never built). To build it locally you need Rust with the Android targets,
`cargo-ndk` and an Android NDK:

```
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=/path/to/Android/Sdk/ndk/<version>

# from a kernkraft checkout at the tag the action pins
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o /path/to/kern-android/jniLibs \
    build --release -p kernkraft-jni --locked
```

`-o .../jniLibs` writes `jniLibs/<abi>/libkernkraft_jni.so`, the layout Gradle reads
(`jniLibs.srcDirs` in `build.gradle.kts`, with matching `abiFilters`). The 64-bit
libraries are aligned to 16 KB pages. When the file is absent,
`KernkraftPdf.available` is false and the tools sheet says the engine is not bundled.

The emulator is x86_64, so a `jniLibs/` with arm64-v8a alone leaves the engine missing
there. Build just `arm64-v8a` (`-t arm64-v8a`) for a quick phone-only iteration.

## Tests

`tests/pdf-bridge/KernkraftPdfTest.kt` covers the answer parsing on the JVM.
`tests/instrumented/pdf-bridge/KernkraftPdfInstrumentedTest.kt` runs merge and extract
through the real library on a device or emulator (`connectedDebugAndroidTest`), and the
instrumented tests under `tests/instrumented/editors/pdf/` drive the editor through it.

## Licence

Bridge code is AGPL-3.0 (see the root `LICENSE`). The kernkraft source offer and the
notices for the data and Rust crates inside the engine are in `licenses/kernkraft/`.
