project('wldroid-compositor', 'c',
  version: '0.1.0',
  default_options: [
    'c_std=gnu11',
    'warning_level=2',
    'default_library=static',

    # ── wlroots: headless — our Android backend replaces all platform code ──
    'wlroots:backends=',
    'wlroots:renderers=gles2',
    'wlroots:allocators=',
    'wlroots:session=disabled',
    'wlroots:xwayland=enabled',
    'wlroots:xcb-errors=disabled',
    'wlroots:examples=false',
    'wlroots:color-management=disabled',

    # ── xorgproto ──
    'xorgproto:legacy=false',

    # ── libdrm: headers + base lib only ──
    'libdrm:amdgpu=disabled',
    'libdrm:radeon=disabled',
    'libdrm:intel=disabled',
    'libdrm:nouveau=disabled',
    'libdrm:vmwgfx=disabled',
    'libdrm:omap=disabled',
    'libdrm:exynos=disabled',
    'libdrm:freedreno=disabled',
    'libdrm:tegra=disabled',
    'libdrm:vc4=disabled',
    'libdrm:etnaviv=disabled',
    'libdrm:cairo-tests=disabled',
    'libdrm:man-pages=disabled',
    'libdrm:valgrind=disabled',
    'libdrm:tests=false',

    # ── pixman: minimal ──
    'pixman:gtk=disabled',
    'pixman:libpng=disabled',
    'pixman:tests=disabled',
    'pixman:demos=disabled',

    # ── xkbcommon: keyboard support only, no X11/Wayland compositor ──
    'libxkbcommon:enable-x11=false',
    'libxkbcommon:enable-wayland=false',
    'libxkbcommon:enable-xkbregistry=false',
    'libxkbcommon:enable-tools=false',
    'libxkbcommon:enable-bash-completion=false',
    'libxkbcommon:enable-docs=false',
    'libxkbcommon:enable-cool-uris=false',

    # ── libffi: core library only ──
    'libffi:tests=false',
    'libffi:doc=false',

    # ── wayland: server library only, scanner is built natively ──
    'wayland:tests=false',
    'wayland:documentation=false',
    'wayland:dtd_validation=false',

    # ── wayland-protocols ──
    'wayland-protocols:tests=false',
  ],
)

subdir('src')

# Tests are only built for native (host) builds, not Android cross-compilation.
# When cross-compiling, meson.is_cross_build() is true and tests are skipped.
if not meson.is_cross_build()
    subdir('tests')
endif
