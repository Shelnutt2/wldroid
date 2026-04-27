# xcb-util-wm — ICCCM and EWMH helper libraries for XCB
# Custom meson.build overlay for cross-compilation (upstream uses autotools).
#
# ewmh uses m4 code generation (ewmh.c.m4 → ewmh.c, xcb_ewmh.h.m4 → xcb_ewmh.h).
# m4 runs on the host, so cross-compilation is fine.
project('xcb-util-wm', 'c', version: '0.4.2', default_options: ['c_std=gnu11'])

cc = meson.get_compiler('c')
xcb_dep = dependency('xcb')

# ── Config header ──
config_data = configuration_data()
config_data.set10('HAVE_STDINT_H', cc.has_header('stdint.h'))
config_data.set10('HAVE_INTTYPES_H', cc.has_header('inttypes.h'))
config_data.set_quoted('PACKAGE_VERSION', '0.4.2')
configure_file(output: 'config.h', configuration: config_data)

# ── xcb/ include prefix for consumers (#include <xcb/xcb_icccm.h>) ──
xcb_hdr_dir = meson.current_build_dir() / 'xcb'
run_command('mkdir', '-p', xcb_hdr_dir, check: true)
run_command('ln', '-sfn', meson.current_source_dir() / 'icccm' / 'xcb_icccm.h',
            xcb_hdr_dir / 'xcb_icccm.h', check: true)
# xcb_ewmh.h is generated — symlink target created after custom_target below

# ── ICCCM (plain C, no code generation) ──
icccm_sources = files('icccm/icccm.c')

libxcb_icccm = static_library('xcb-icccm',
  icccm_sources,
  include_directories: include_directories('icccm', '.'),
  dependencies: xcb_dep,
  c_args: ['-DHAVE_CONFIG_H'],
)

xcb_icccm_dep = declare_dependency(
  link_with: libxcb_icccm,
  include_directories: [include_directories('icccm'), include_directories('.')],
  dependencies: xcb_dep,
)
meson.override_dependency('xcb-icccm', xcb_icccm_dep)

# ── EWMH (requires m4 code generation) ──
m4 = find_program('m4')

ewmh_c = custom_target('ewmh-c',
  input: ['ewmh/ewmh.c.m4', 'ewmh/atomlist.m4'],
  output: 'ewmh.c',
  command: [m4, '-I', meson.current_source_dir() / 'ewmh', '@INPUT0@'],
  capture: true,
)

ewmh_h = custom_target('ewmh-h',
  input: ['ewmh/xcb_ewmh.h.m4', 'ewmh/atomlist.m4'],
  output: 'xcb_ewmh.h',
  command: [m4, '-I', meson.current_source_dir() / 'ewmh', '@INPUT0@'],
  capture: true,
)

# Symlink generated ewmh header into xcb/ for consumers
run_command('ln', '-sfn',
            meson.current_build_dir() / 'xcb_ewmh.h',
            xcb_hdr_dir / 'xcb_ewmh.h',
            check: false)  # target may not exist at configure time

libxcb_ewmh = static_library('xcb-ewmh',
  ewmh_c,
  include_directories: include_directories('ewmh', '.'),
  dependencies: xcb_dep,
  c_args: ['-DHAVE_CONFIG_H'],
)

xcb_ewmh_dep = declare_dependency(
  link_with: libxcb_ewmh,
  include_directories: [include_directories('ewmh'), include_directories('.')],
  dependencies: xcb_dep,
  sources: ewmh_h,  # ensure generated header is available to consumers
)
meson.override_dependency('xcb-ewmh', xcb_ewmh_dep)
