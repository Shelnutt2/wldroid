# External Native Dependencies — Forked Repositories

This directory contains git submodules **only** for the 3 forked native
dependencies that carry Android-specific patches. All upstream (unmodified)
dependencies are managed by Meson WrapDB `.wrap` files committed in
`compositor/native/subprojects/`.

## Layout

```
external/
├── wlroots/          # Fork — Wayland compositor library (android-0.19.2)
├── virglrenderer/    # Fork — Virtual OpenGL renderer (android-v1.1.0)
└── proot/            # Fork — Proot user-space chroot (android)
```

See [FORKS.md](FORKS.md) for full patch documentation.

## Why only forks?

The 3 forked repositories contain Android-specific patches that have not been
accepted upstream. These track named branches (e.g. `android-0.19.2`) in the
`Shelnutt2` GitHub org and must be kept as git submodules.

All other upstream dependencies (wayland, libdrm, pixman, libxkbcommon, etc.)
are pinned via Meson `.wrap` files in `compositor/native/subprojects/`. Meson
downloads and builds them automatically — no submodules needed.

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
Run the setup script to create symlinks for the 3 forks:

```bash
./scripts/setup-meson-subprojects.sh
```

This creates symlinks like `compositor/native/subprojects/wlroots` →
`../../../external/wlroots`. Upstream deps are resolved from `.wrap` files
automatically by `meson setup`.

## Updating Dependencies

**Forked deps** — update the fork branch first, then update the submodule pointer:

```bash
cd external/wlroots
git fetch origin
git checkout <new-branch>
cd ../..
git add external/wlroots
git commit -m "deps: update wlroots fork to <new-branch>"
```

**Upstream deps** — edit the `.wrap` file in `compositor/native/subprojects/`
to point at the new version and update the hash.
