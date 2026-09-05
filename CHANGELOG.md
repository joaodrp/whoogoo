# Changelog

## [1.0.1](https://github.com/joaodrp/whoogoo/compare/v1.0.0...v1.0.1) (2026-09-05)


### Bug Fixes

* **app:** stop claiming the sync needs Google Health in front of you ([e3742ac](https://github.com/joaodrp/whoogoo/commit/e3742acffda3678c9ce6a7ca0bb1f3e2f0e1fdbb))
* **docs:** point the header mark at a path that resolves ([bc0b28b](https://github.com/joaodrp/whoogoo/commit/bc0b28b58182ffbf5b326a0116f80cfed58915c8))

## [1.0.0](https://github.com/joaodrp/whoogoo/compare/v0.3.0...v1.0.0) (2026-09-05)


### ⚠ BREAKING CHANGES

* **app:** sign releases with a key held outside the repository
* import only what survives the trip intact

### Features

* **app:** choose the months to import ([541833d](https://github.com/joaodrp/whoogoo/commit/541833d91bfeeff9b3aecc3632f96bab624dea87))
* **app:** say what each record type carries, and what it does not ([a79118c](https://github.com/joaodrp/whoogoo/commit/a79118cf6ab8d6a4183c0d7d73be01ef5366dd40))
* **app:** sign releases with a key held outside the repository ([be0195d](https://github.com/joaodrp/whoogoo/commit/be0195de52a413062b4736f6d5c2ab5876ce5d99))
* **app:** skip what another app already has ([ca6c6ad](https://github.com/joaodrp/whoogoo/commit/ca6c6adde07d8f1c94052246bcfc2046826f0fa8))
* **app:** take an import back out ([d1848e8](https://github.com/joaodrp/whoogoo/commit/d1848e80a22b5413602ee6d7047a9c05f9577679))
* choose what to import ([e62c0f0](https://github.com/joaodrp/whoogoo/commit/e62c0f00acb5a092918bf9771c5e076de4e98a05))
* **cli:** add --dry to import ([15c7870](https://github.com/joaodrp/whoogoo/commit/15c7870534e936b687bbe49843634e0b85c34fc9))
* import only what survives the trip intact ([90e01c9](https://github.com/joaodrp/whoogoo/commit/90e01c9cb571cbd4d20fe14e1e379721d4633944))
* let emu pass a -gpu mode to the emulator ([2404c96](https://github.com/joaodrp/whoogoo/commit/2404c96e6912469462ee33706be2fa08527ed551))
* move the emulator to Android 17 ([e2d36d5](https://github.com/joaodrp/whoogoo/commit/e2d36d542a6df6dac0fcb0974f07792abc13cfc8))
* stop inventing sleep stages ([111aa7d](https://github.com/joaodrp/whoogoo/commit/111aa7da67ae55a610e0ce43ce289aa86ed3595d))


### Bug Fixes

* **app:** clear the duplicate check when another export is opened ([94c64b4](https://github.com/joaodrp/whoogoo/commit/94c64b4141c47c1c08c124d4be68012a388fad57))
* **app:** derive versionCode from any tag shape ([4f49c92](https://github.com/joaodrp/whoogoo/commit/4f49c9219b2336b08d3c5f5a97dcb4d2efe0d880))
* **app:** let a re-import actually update a record ([25ed38a](https://github.com/joaodrp/whoogoo/commit/25ed38ade27d85165fbd206a1f740403baf5b1f1))
* **app:** remove the staged export, and scan whole days ([be21abf](https://github.com/joaodrp/whoogoo/commit/be21abfb9a1699e823611fc471b3db1cf21ea433))
* **app:** skip a sleep that was still in progress ([1caa7d2](https://github.com/joaodrp/whoogoo/commit/1caa7d2e008c6996a4d86365e3cab730e3e205df))
* **app:** stop asking for first-run setup after every import ([f24565a](https://github.com/joaodrp/whoogoo/commit/f24565a96f8307b53badd759af5c9a039e7499e7))
* **app:** survive the activity being recreated mid-import ([8f9e8a8](https://github.com/joaodrp/whoogoo/commit/8f9e8a8116d0776f61ba207e19d59a610f3c6571))
* **app:** tolerate a byte order mark, and keep timestamps out of errors ([41db35c](https://github.com/joaodrp/whoogoo/commit/41db35cb9daf4e140c99ac6f4b517b0e582dba9a))
* **app:** treat a missing CSV as empty ([9c0fd4c](https://github.com/joaodrp/whoogoo/commit/9c0fd4c55d73650ca25a7a50f54d1b21407056d8))
* **cli:** check state and code on the OAuth callback ([d2d44e8](https://github.com/joaodrp/whoogoo/commit/d2d44e8f06fa42a0c79b96dea3bd52a37d4e4fb8))
* **cli:** don't panic when adb fails to start ([4faf9f7](https://github.com/joaodrp/whoogoo/commit/4faf9f767e08d169b25783843042f2ae3b3c6203))
* **cli:** fail fast when no device is attached ([67ae892](https://github.com/joaodrp/whoogoo/commit/67ae8921b6d000a9ec9bf2182ec746e12eb7d40b))
* **cli:** ignore records with no timestamp when bounding the query ([f608614](https://github.com/joaodrp/whoogoo/commit/f608614ba47a8278eb225c83b1283e257354054d))
* **cli:** keep the pulled records private ([aef2c8a](https://github.com/joaodrp/whoogoo/commit/aef2c8ac9669ec75a133d3e61ad9584cff1d3fb9))
* **cli:** only count this app's own records as matches ([99dd007](https://github.com/joaodrp/whoogoo/commit/99dd0077043367498c5f3d68ecf0d84999686c6c))
* **cli:** put deadlines on every network wait ([057fce3](https://github.com/joaodrp/whoogoo/commit/057fce34698e13316049bee904941d0a5b97f39c))
* **cli:** recover from a revoked refresh token ([5216945](https://github.com/joaodrp/whoogoo/commit/5216945f2d93cc18ad141a9c702161a16a92f50a))
* **cli:** report an app crash instead of waiting out the timeout ([0dadac1](https://github.com/joaodrp/whoogoo/commit/0dadac14202e7f587203e72cd35976932943849f))
* **cli:** report why creating the virtual device failed ([e5db049](https://github.com/joaodrp/whoogoo/commit/e5db049c4674e44b13ec90931089f8b8ad1741fe))
* keep avdmanager and the emulator on the same AVD directory ([d5c7b13](https://github.com/joaodrp/whoogoo/commit/d5c7b13fe418ed9fa7e2b46ea17d1be7d6c22b5b))
* let the host keyboard type into the emulator ([2055157](https://github.com/joaodrp/whoogoo/commit/20551574a7c9c2980ddf4a2dcbe8b58db713e65a))
* report a missing records.json field instead of panicking ([c746d6f](https://github.com/joaodrp/whoogoo/commit/c746d6fbc13a3ea99096c3e8bd3923859284b693))

## [0.3.0](https://github.com/joaodrp/whoogoo/compare/v0.2.1...v0.3.0) (2026-09-04)


### Features

* **app:** ultramarine design language ([994aad6](https://github.com/joaodrp/whoogoo/commit/994aad6a819c46086e806930e5d5f13caea793d2))
* make the app standalone and let the CLI drive it ([1320ff4](https://github.com/joaodrp/whoogoo/commit/1320ff4d6fbe76ce948b0f6abe369459129eb85c))

## [0.2.1](https://github.com/joaodrp/whoogoo/compare/v0.2.0...v0.2.1) (2026-09-04)


### Bug Fixes

* launch SDK tools with the SDK root exported ([849b6fb](https://github.com/joaodrp/whoogoo/commit/849b6fbbf98aa0222d71bc4f6a67538bdc6e3534))
* version the APK asset and fetch the one matching the CLI ([5237260](https://github.com/joaodrp/whoogoo/commit/523726035e0e3d6a8443bc2861f353d10bee6785))

## [0.2.0](https://github.com/joaodrp/whoogoo/compare/v0.1.0...v0.2.0) (2026-09-04)


### Features

* import WHOOP export into Google Health via Health Connect ([8f9b8e4](https://github.com/joaodrp/whoogoo/commit/8f9b8e425ad2ded218f6ab9b324ad19369c0371c))
* rewrite the CLI in Go and ship static binaries ([0f88d4c](https://github.com/joaodrp/whoogoo/commit/0f88d4ce528db6751f66be6fd89f2a2a3143174e))
* verify the account sync through the Google Health API ([c16af8c](https://github.com/joaodrp/whoogoo/commit/c16af8c47c63cbfcf9098a0f70c4305d0a28c104))


### Bug Fixes

* find the Homebrew Android SDK on macOS ([f879386](https://github.com/joaodrp/whoogoo/commit/f879386a9ddcb8f4a15f3d32b0cd04b001e463d4))
