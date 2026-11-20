# PRoot shipped with Axiko

Axiko includes the PRoot executable and both loaders from version 0.15 of the green-green-avk Android build. Axiko renamed the files for Android packaging. Their bytes match the files in the upstream archives.

## Source and build

The repository and release include `proot-android-0.15-corresponding-source.tar.gz`. Inside the APK, Android packages the same tar archive as `assets/licenses/proot/proot-android-0.15-corresponding-source.tar`. It contains the upstream build scripts, PRoot sources and the sources of the statically linked talloc library. Extract it on Linux. Install the Android NDK 23.2.8568313 in `~/Android/Sdk/ndk/23.2.8568313`, or set `NDK` in `config` to the correct local path. With the host build tools for the included upstream scripts installed (including Bash, Make, tar and a Python interpreter compatible with talloc 2.1.14's Waf), run `./build.sh` from the extracted directory. The source directories that `get-proot.sh` and `get-talloc.sh` expect are already under `build/`. The matching upstream build path runs `make-proot.sh`. The outputs are `packages/proot-android-aarch64.tar.gz` and `packages/proot-android-x86_64.tar.gz`.

Build scripts and upstream package binaries: https://github.com/green-green-avk/build-proot-android/tree/01f83b8841358450c78333d1b33ab30d4943bec4

PRoot tag `v0.15_release`, commit `b40accf05dfddbaf44758aa666ade79c57255005`: https://github.com/green-green-avk/proot/tree/b40accf05dfddbaf44758aa666ade79c57255005

PRoot source archive: https://codeload.github.com/green-green-avk/proot/tar.gz/refs/tags/v0.15_release

Talloc 2.1.14: https://download.samba.org/pub/talloc/talloc-2.1.14.tar.gz . The source archive was obtained from the Debian Snapshot mirror of the upstream original (`talloc_2.1.14.orig.tar.gz`), https://snapshot.debian.org/file/9d563b768148b620bdae1c97b36cfc30928a1044 . Its SHA-256 matches the Debian source package's checksum record: https://snapshot.debian.org/file/9ad151848a3ce091557157edd700402bcd35e729 .

## Copyright and licenses

PRoot's `COPYING` lists the authors and gives the GNU General Public License, version 2. The source headers for PRoot and its loaders permit version 2 or any later version. Talloc's library is licensed under the GNU Lesser General Public License, version 3 or later. The collection includes the GNU GPLv3 and LGPLv3 texts, PRoot's original `COPYING`, the talloc copyright notice, and the MIT license for the Android build scripts. Individual source files carry additional notices. See `licenses/` and the complete sources.

## SHA-256

| Input archive | SHA-256 |
|---|---|
| `packages/proot-android-aarch64.tar.gz` | `9629eb30cdf86e95c6ba681f8ab89c6fdaa9eca093d5577163513c99af5ca281` |
| `packages/proot-android-x86_64.tar.gz` | `f7ecbd4ee1041eefc2d843aa9c080a583fd6c3bb010b3bb7b7522ba05e06bf95` |
| `proot-0.15_release.tar.gz` | `d678603aa80aea84969d2897d2a4f98acc8f6008defca960e40ce4ad4e8e893e` |
| `talloc-2.1.14.tar.gz` | `b185602756a628bac507fa8af8b9df92ace69d27c0add5dab93190ad7c3367ce` |

| Android path under `jniLibs/` | Architecture and upstream member | SHA-256 |
|---|---|---|
| `arm64-v8a/libproot.so` | `aarch64/root/bin/proot` | `297abc237247682a84a3fd4283b28f69506502b4b852faf71fd726fb5d955d60` |
| `arm64-v8a/libproot-loader.so` | `aarch64/root/libexec/proot/loader` | `de0ace1adc76ab0555b3dbbfbff0e78c1ac14017b255262707867aea70c49437` |
| `arm64-v8a/libproot-loader32.so` | `aarch64/root/libexec/proot/loader32` | `1cf92eb379447ca19e13848be1e6dd57832c7d241a6fb8e2bad06df4523721ca` |
| `x86_64/libproot.so` | `x86_64/root/bin/proot` | `46ca97b11d67f63b7dfe49f5ba50da89ab69b255f00a911c36499377a58f40b0` |
| `x86_64/libproot-loader.so` | `x86_64/root/libexec/proot/loader` | `c1f8d7afe1f72951c8afee2650fce3b80f5b6daa98a6f228ed1a2e8a70775bd3` |
| `x86_64/libproot-loader32.so` | `x86_64/root/libexec/proot/loader32` | `957d8484a5d491823b8cec24701cb64377672000e800bfc400971744a50d0919` |
