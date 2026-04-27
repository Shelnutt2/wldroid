# ---- Dependencies (resolved via meson subprojects from external/) ----
wlroots    = dependency('wlroots-0.19')
wayland_sv = dependency('wayland-server')
xkbcommon  = dependency('xkbcommon')
pixman     = dependency('pixman-1')
drm        = dependency('libdrm')

# XWayland support — xcb headers are needed by our xwayland_surface.c when
# wlroots is built with xwayland=enabled (wlr/xwayland/xwayland.h includes xcb).
have_xwayland = wlroots.get_variable('have_xwayland', default_value: 'false') == 'true'
if have_xwayland
  xcb = dependency('xcb')
else
  xcb = dependency('', required: false)
endif

# wlroots generates protocol headers (xdg-shell-protocol.h etc.) at build time.
# Its declare_dependency only exposes include/, not the build-time protocol/ dir.
# We add the protocol build directory to our include path via c_args.
# NOTE: Update this path when upgrading the wlroots version in external/.
# Meson subprojects don't expose their source dir programmatically, so we hardcode it.
wlroots_protocol_dir = meson.project_build_root() / 'subprojects' / 'wlroots-0.19.2' / 'protocol'

# ---- Text input protocol generation ----
wayland_scanner_prog = find_program('wayland-scanner', native: true)
wayland_protos_dep = dependency('wayland-protocols')
wl_protocol_dir = wayland_protos_dep.get_variable('pkgdatadir')

text_input_xml = wl_protocol_dir / 'unstable/text-input/text-input-unstable-v3.xml'

text_input_v3_c = custom_target('text-input-v3-protocol-c',
    input: text_input_xml,
    output: 'text-input-unstable-v3-protocol.c',
    command: [wayland_scanner_prog, 'private-code', '@INPUT@', '@OUTPUT@'])

text_input_v3_h = custom_target('text-input-v3-protocol-h',
    input: text_input_xml,
    output: 'text-input-unstable-v3-server-protocol.h',
    command: [wayland_scanner_prog, 'server-header', '@INPUT@', '@OUTPUT@'])

# ---- xdg-shell client protocol (for built-in test client) ----
wayland_cl = dependency('wayland-client')

xdg_shell_xml = wl_protocol_dir / 'stable/xdg-shell/xdg-shell.xml'

xdg_shell_client_c = custom_target('xdg-shell-client-protocol-c',
    input: xdg_shell_xml,
    output: 'xdg-shell-client-protocol.c',
    command: [wayland_scanner_prog, 'private-code', '@INPUT@', '@OUTPUT@'])

xdg_shell_client_h = custom_target('xdg-shell-client-protocol-h',
    input: xdg_shell_xml,
    output: 'xdg-shell-client-protocol.h',
    command: [wayland_scanner_prog, 'client-header', '@INPUT@', '@OUTPUT@'])

# ---- Sources ----
compositor_sources = files(
  'android_backend.c',
  'android_output.c',
  'ahb_allocator.c',
  'ahb_registry.c',
  'ahb_registry_receiver.c',
  'compositor_server.c',
  'input_handler.c',
  'text_input_handler.c',
  'jni_bridge.c',
  'xwayland_surface.c',
  'test_client.c',
)

# ---- Shared library (Android cross-compile only) ----
# The shared library links Android NDK libraries (-landroid, -llog, -lnativewindow, etc.)
# that are not available on host. Host builds only need protocol targets for tests.
if meson.is_cross_build()
  cc = meson.get_compiler('c')
  android_libs = [
    cc.find_library('android'),
    cc.find_library('log'),
  ]

  egl_dep = cc.find_library('EGL', required: false)
  glesv2_dep = cc.find_library('GLESv2', required: false)
  nativewindow_dep = cc.find_library('nativewindow', required: false)

  shared_library('wldroid-compositor',
    compositor_sources, text_input_v3_c, text_input_v3_h,
    xdg_shell_client_c, xdg_shell_client_h,
    dependencies: [wlroots, wayland_sv, wayland_cl, xkbcommon, pixman, drm, xcb] + android_libs + [egl_dep, glesv2_dep, nativewindow_dep],
    c_args: ['-DWLR_USE_UNSTABLE', '-I' + wlroots_protocol_dir],
    install: true,
  )
endif
