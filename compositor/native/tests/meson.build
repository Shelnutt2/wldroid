# Compositor unit tests — run on the host (not cross-compiled for Android).
# These test pure-logic components that don't depend on Android APIs.

libdrm_dep = dependency('libdrm')
wayland_server_dep = dependency('wayland-server')
wayland_client_dep = dependency('wayland-client')

# ── XDG shell protocol generation (needed by test_client) ─────────────
wayland_scanner = find_program('wayland-scanner')
wayland_protos  = dependency('wayland-protocols')
wl_protocol_dir = wayland_protos.get_variable('pkgdatadir')
xdg_shell_xml   = wl_protocol_dir / 'stable/xdg-shell/xdg-shell.xml'

xdg_shell_c = custom_target('xdg-shell-protocol-c',
    input: xdg_shell_xml,
    output: 'xdg-shell-protocol.c',
    command: [wayland_scanner, 'private-code', '@INPUT@', '@OUTPUT@'])

xdg_shell_h = custom_target('xdg-shell-protocol-h',
    input: xdg_shell_xml,
    output: 'xdg-shell-client-protocol.h',
    command: [wayland_scanner, 'client-header', '@INPUT@', '@OUTPUT@'])

# ── Test targets ──────────────────────────────────────────────────────

test_drm = executable('test_drm_format',
    'test_drm_format.c',
    dependencies: [libdrm_dep],
    include_directories: [include_directories('../src'), include_directories('stubs')])
test('drm_format', test_drm)

test_toplevel = executable('test_toplevel_tracking',
    'test_toplevel_tracking.c',
    dependencies: [wayland_server_dep])
test('toplevel_tracking', test_toplevel)

test_wl_client = executable('test_client',
    'test_client.c', xdg_shell_c, xdg_shell_h,
    dependencies: [wayland_client_dep],
    c_args: ['-D_GNU_SOURCE'])

test_keycode = executable('test_keycode_map',
    'test_keycode_map.c',
    include_directories: [include_directories('../src')])
test('keycode_map', test_keycode)

test_ring = executable('test_ring_buffer',
    'test_ring_buffer.c')
test('ring_buffer', test_ring)
