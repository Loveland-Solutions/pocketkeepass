# PocketKeePass for Windows

A WinUI 3 app on the Windows App SDK, over the same Rust engine (`../core`) as the Android,
iPhone, and Mac apps. Started 2026-11-27; so far it's a window that loads the engine.

| Folder | What it is |
|---|---|
| `Engine/` | The engine for C#: builds `core/` with cargo and generates the C# bindings from it with `uniffi-bindgen-cs`, on every build. Plain `net10.0`, so it also builds on the Mac. The bindings land in `obj/uniffi/` and are never checked in. |
| `Engine.Tests/` | Bridge tests (MSTest), the C# counterpart of `BridgeTest.kt` and `BridgeTests.swift`. Run on Windows and on the Mac. |
| `App/` | The WinUI 3 app. Builds only on Windows. |

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

## The dev VM

The owner's Windows 11 on ARM runs in Parallels on the dev Mac, named "Windows 11". The Mac's
`~/Repositories` is shared into it as `\\Mac\Repositories` (drive Y:). From the Mac:

```sh
prlctl exec "Windows 11" --current-user cmd /c "..."   # as the owner's Windows account
```

Without `--current-user` commands run as SYSTEM, where winget isn't available. Installers that
need administrator rights wait on a UAC prompt in the VM, which only a person can accept.
