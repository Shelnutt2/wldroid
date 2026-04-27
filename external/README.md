# External Native Dependencies

This directory contains git submodules for all native C/C++ dependencies used
by the WLDroid compositor, VirGL server, and proot modules.

## Layout

```
external/
├── wlroots/          # Fork — Wayland compositor library (android-0.19.2)
├── virglrenderer/    # Fork — Virtual OpenGL renderer (android-v1.1.0)
├── proot/            # Fork — Proot user-space chroot (android)
├── wayland/          # Upstream — Wayland protocol library
├── wayland-protocols/# Upstream — Wayland protocol definitions
├── libdrm/           # Upstream — Direct Rendering Manager library
├── pixman/           # Upstream — Pixel manipulation library
├── libxkbcommon/     # Upstream — XKB keymap compiler
├── libffi/           # Upstream — Foreign function interface
├── expat/            # Upstream — XML parser (wayland dep)
├── xcb-proto/        # Upstream — XCB protocol descriptions
├── libxcb/           # Upstream — X11 C bindings
├── libxau/           # Upstream — X11 authorization
├── xorgproto/        # Upstream — X.Org protocol headers
├── xcb-util-wm/     # Upstream — XCB window manager utilities
├── libepoxy/         # Upstream — GL dispatch library
└── talloc/           # Upstream — Hierarchical memory allocator (proot dep)
```

## Forked vs Upstream

**3 forked repositories** contain Android-specific patches that have not been
accepted upstream. These track named branches (e.g. `android-0.19.2`) in
the `Shelnutt2` GitHub org. See [FORKS.md](FORKS.md) for patch details.

**14 upstream repositories** are pinned to specific release tags via the
submodule commit SHA. No local patches are applied to these.

## Initialization

Clone with submodules:

```bash
git clone --recurse-submodules https://github.com/user/wldroid.git
```

Or initialize after cloning:

```bash
git submodule update --init --recursive
```

## Meson Integration

The compositor's Meson build expects dependencies in `compositor/native/subprojects/`.
Run the symlink setup script to bridge this directory to the submodules:

```bash
./scripts/setup-meson-subprojects.sh
```

This creates symlinks like `compositor/native/subprojects/wlroots` →
`../../../external/wlroots`.

## Updating a Dependency

To update an upstream dependency to a new tag:

```bash
cd external/<dep>
git fetch
git checkout <new-tag>
cd ../..
git add external/<dep>
git commit -m "deps: update <dep> to <new-tag>"
```

For forked dependencies, update the fork branch first, then update the
submodule pointer.
