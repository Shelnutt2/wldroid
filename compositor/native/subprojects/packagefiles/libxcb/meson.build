# libxcb — X11 C bindings library
# Custom meson.build overlay for cross-compilation (upstream uses autotools).
#
# c_client.py (from the libxcb source) generates C/H files from xcb-proto XML.
# It runs on the *host* (Python), not the target, so cross-compilation is fine.
project('libxcb', 'c', version: '1.17.0', default_options: ['c_std=gnu11'])

cc = meson.get_compiler('c')
xau_dep = dependency('xau')
xcb_proto_dep = dependency('xcb-proto')

# ── Paths from xcb-proto ──
xcbgen_dir = xcb_proto_dep.get_variable('xcbgen_dir')
xml_dir = xcb_proto_dep.get_variable('xml_dir')

# ── Config header ──
config_data = configuration_data()
config_data.set10('HAVE_ABSTRACT_SOCKETS',
  cc.compiles('''
    #include <sys/socket.h>
    #include <sys/un.h>
    int main(void) { struct sockaddr_un addr; addr.sun_path[0] = 0; return 0; }
  ''', name: 'abstract sockets'))
config_data.set10('HAVE_SENDMSG', cc.has_function('sendmsg'))
config_data.set10('HAVE_GETADDRINFO', cc.has_function('getaddrinfo'))
config_data.set10('GCC_HAS_VISIBILITY',
  cc.has_argument('-fvisibility=hidden'))
config_data.set10('HAVE_DLFCN_H', cc.has_header('dlfcn.h'))
config_data.set10('HAVE_INTTYPES_H', cc.has_header('inttypes.h'))
config_data.set10('HAVE_STDINT_H', cc.has_header('stdint.h'))
config_data.set10('HAVE_UNISTD_H', cc.has_header('unistd.h'))
# XDM auth is not needed for our use case — do NOT define HASXDMAUTH
# (upstream uses #ifdef, so even =0 would enable it)
# Buffer queue size (default from upstream configure.ac)
config_data.set('XCB_QUEUE_BUFFER_SIZE', 16384)
config_data.set_quoted('PACKAGE_STRING', 'libxcb 1.17.0')

configure_file(output: 'config.h', configuration: config_data)

# ── Python code generator ──
python3 = find_program('python3')
c_client_py = meson.current_source_dir() / 'src' / 'c_client.py'

# ── xcb/ include directory for consumers (#include <xcb/xcb.h>) ──
# Create <build_dir>/xcb/ directory and populate with source header symlinks.
# Generated headers are also symlinked here after the custom_target loop.
xcb_hdr_dir = meson.current_build_dir() / 'xcb'
run_command('mkdir', '-p', xcb_hdr_dir, check: true)
foreach hdr : ['xcb.h', 'xcbext.h', 'xcb_windefs.h']
  run_command('ln', '-sfn', meson.current_source_dir() / 'src' / hdr,
              xcb_hdr_dir / hdr, check: true)
endforeach

# Protocol XMLs to generate — core + extensions needed by wlroots xwayland
protocols = [
  'xproto',      # core X11 protocol (always needed)
  'bigreq',      # big requests extension (needed by core)
  'xc_misc',     # XC-MISC extension (needed by core)
  'ge',          # generic events (needed by xfixes)
  'shm',         # shared memory
  'shape',       # shape extension (dependency of xfixes)
  'composite',   # compositing extension
  'render',      # render extension
  'xfixes',      # xfixes extension (depends on render + shape)
  'res',         # resource extension
]

gen_sources = []
gen_headers = []

foreach proto : protocols
  gen = custom_target('gen-' + proto,
    output: [proto + '.c', proto + '.h'],
    command: [
      'sh', '-c',
      'cd "$1" && shift && exec "$@"',
      '--', '@OUTDIR@',
      python3, c_client_py,
      '-c', 'libxcb 1.17.0',
      '-l', 'X Version 11',
      '-s', '3',
      '-p', xcbgen_dir,
      xml_dir / proto + '.xml',
    ],
    # c_client.py writes to cwd, so we cd to @OUTDIR@ first.
    # XML input is passed by path (not @INPUT@) to avoid meson sandbox
    # restrictions between subprojects.  This is safe because xcb-proto
    # is a static data package that won't change during the build.
  )
  gen_sources += gen[0]
  gen_headers += gen[1]

  # Also symlink generated header into xcb/ for #include <xcb/proto.h>
  run_command('ln', '-sfn',
              meson.current_build_dir() / proto + '.h',
              xcb_hdr_dir / proto + '.h',
              check: false)  # may not exist yet at configure time; that's OK
endforeach

# ── Core library sources ──
core_sources = files(
  'src/xcb_conn.c',
  'src/xcb_out.c',
  'src/xcb_in.c',
  'src/xcb_ext.c',
  'src/xcb_xid.c',
  'src/xcb_list.c',
  'src/xcb_util.c',
  'src/xcb_auth.c',
)

# src/ for internal #include "xcb.h" / "xcbint.h" (used within libxcb itself)
libxcb_inc = include_directories('src')
# build dir for generated headers AND the xcb/ subdirectory for consumers
libxcb_build_inc = include_directories('.')

libxcb = static_library('xcb',
  core_sources + gen_sources + gen_headers,
  include_directories: [libxcb_inc, libxcb_build_inc],
  dependencies: [xau_dep],
  c_args: ['-DHAVE_CONFIG_H'],
)

# ── Declare core xcb dependency ──
xcb_dep = declare_dependency(
  link_with: libxcb,
  include_directories: [libxcb_inc, libxcb_build_inc],
  sources: gen_headers,  # ensure generated headers are available to consumers
)
meson.override_dependency('xcb', xcb_dep)

# ── Extension dependencies ──
# Each extension is compiled into the same library; the dependency just
# ensures the generated header is available.
extension_deps = {
  'xcb-composite': 'composite',
  'xcb-render': 'render',
  'xcb-xfixes': 'xfixes',
  'xcb-res': 'res',
}

foreach dep_name, proto : extension_deps
  meson.override_dependency(dep_name, xcb_dep)
endforeach
