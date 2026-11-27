# PocketKeePass for Windows

A WinUI 3 app on the Windows App SDK, over the same Rust engine (`../core`) as the Android,
iPhone, and Mac apps. Started 2026-11-27. So far it opens and unlocks a database (password, key
file, or both) and browses and searches it, in English and Mongolian.

| Folder | What it is |
|---|---|
| `Engine/` | The engine for C#: builds `core/` with cargo and generates the C# bindings from it with `uniffi-bindgen-cs`, on every build. Plain `net10.0`, so it also builds on the Mac. The bindings land in `obj/uniffi/` and are never checked in. |
| `Engine.Tests/` | Bridge tests (MSTest), the C# counterpart of `BridgeTest.kt` and `BridgeTests.swift`. Run on Windows and on the Mac. |
| `App/` | The WinUI 3 app, packaged as MSIX (`Package.appxmanifest`). Builds only on Windows. |
| `run-dev.ps1` | Builds the app, registers the package from the build folder, and starts it. |
| `tools/uia.ps1` | Reads and drives the app's window through UI Automation (development only). |

## What you need

- **.NET 10 SDK** (the long-term support release; `global.json` takes 10.0.400 or later).
- **Rust** from rustup, with `cargo` on the PATH. On Windows also the targets:
  `rustup target add aarch64-pc-windows-msvc x86_64-pc-windows-msvc`.
- **uniffi-bindgen-cs 0.12.0+v0.32.0** (Nord Security), the generator for our UniFFI 0.32.
  The build checks the version and stops if it differs. `--locked` matters: without it a newer
  dependency breaks its build.

  ```sh
  cargo install uniffi-bindgen-cs --git https://github.com/NordSecurity/uniffi-bindgen-cs \
      --tag v0.12.0+v0.32.0 --locked
  ```

- On Windows, **Visual Studio Build Tools 2026** with the C++ tools for ARM64 and x64 (Rust's
  MSVC targets link with them) and the Windows SDK. With winget:

  ```
  winget install --id Microsoft.VisualStudio.BuildTools -e --override "--quiet --wait --norestart --add Microsoft.VisualStudio.Workload.VCTools --add Microsoft.VisualStudio.Component.VC.Tools.ARM64 --add Microsoft.VisualStudio.Component.VC.Tools.x86.x64 --includeRecommended"
  ```

## Building

```sh
cd windows/Engine.Tests && dotnet test           # Mac or Windows: engine, bindings, bridge tests
cd windows/App && dotnet build -p:Platform=ARM64  # Windows: the app (or -p:Platform=x64)
```

On Windows, build output goes to the local disk rather than the repository: .NET's `bin` and
`obj` to `%LOCALAPPDATA%\PocketKeePass\build\<project>` (`Directory.Build.props`), cargo's to
`%LOCALAPPDATA%\PocketKeePass\cargo-target`. The repository is usually a Parallels shared
folder, which is slow, and the Mac builds into the same `bin`, `obj`, and `core/target` there. The engine is built for the app's platform (`RustTarget`), so an x64 app built on an
ARM64 PC gets an x64 engine.

## Running

The app is packaged (MSIX), so it runs with a package identity, which per-app settings, Windows
Hello, and the Store need. A debug build isn't signed: Windows' developer mode registers it
straight from the build folder (Settings › System › For developers; on in the dev VM).

```
powershell -ExecutionPolicy Bypass -File windows\run-dev.ps1           # build, register, start
powershell -ExecutionPolicy Bypass -File windows\run-dev.ps1 -NoBuild  # register and start
```

A change to the package itself (a new language, for one) can't be registered over the old
registration; the script then removes that first, keeping the app's data. The package identity
(`LovelandSolutions.PocketKeePass.Dev`) is a placeholder until the app is reserved in Partner Center.

Strings are in `App/Strings/<language>/Resources.resw`: `en-US` and `mn-MN`. The Mongolian folder
must be `mn-MN`: one named plain `mn` was built into the package without a language, so it was
never chosen. Every string gets its Mongolian translation in the same change (Android's, where
it has one).

## Checking the app from the Mac

Parallels' screen capture of the VM is black, and the window keeps itself out of captures anyway,
so the app is checked through UI Automation: `tools/uia.ps1 dump` lists every element with its
name and value, and `click`, `type`, `key`, and `wait` drive it. From the Mac:

```sh
prlctl exec "Windows 11" --current-user powershell -NoProfile -ExecutionPolicy Bypass \
    -File '\\Mac\Repositories\passthekey\windows\tools\uia.ps1' dump
```

(`\\Mac\Repositories` rather than `Y:`: the drive letter doesn't exist for `prlctl exec`'s
PowerShell. `prlctl exec` sometimes fails with "Invalid argument" and runs nothing; run it again.)

UI Automation can't type into the system file picker, so debug builds take choices from files in
the package's `LocalState` folder (`%LOCALAPPDATA%\Packages\LovelandSolutions.PocketKeePass.Dev_*\LocalState`),
read and deleted at the next launch:

- `debug-open.txt`: the database's path on the first line, a key file's on the second (optional);
- `debug-language.txt`: `mn-MN`, or `en-US` to go back (an empty value is refused by Windows).

Debug builds also write every unexpected exception to `LocalState\crash.log` (an error in XAML
crashes inside the framework without reaching the app's unhandled-exception handler). Release
builds have none of this.

What UI Automation shows is also what any program running as the user can read: the master
password field gives one dot per character, as Android's does; everything else on screen (group
and entry names, usernames) is readable. Windows has no equivalent of Android 14's
`isAccessibilityTool` to hide it from all but assistive tools.

## The dev VM

The owner's Windows 11 on ARM runs in Parallels on the dev Mac, named "Windows 11". The Mac's
`~/Repositories` is shared into it as `\\Mac\Repositories` (drive Y:). From the Mac:

```sh
prlctl exec "Windows 11" --current-user cmd /c "..."   # as the owner's Windows account
```

Without `--current-user` commands run as SYSTEM, where winget isn't available. Installers that
need administrator rights wait on a UAC prompt in the VM, which only a person can accept.
