# libXau — X11 authorization protocol library
# Custom meson.build overlay for cross-compilation (upstream uses autotools).
project('libXau', 'c', version: '1.0.11', default_options: ['c_std=gnu11'])

cc = meson.get_compiler('c')
xorgproto = dependency('xorgproto')

# Generate config.h with feature checks
config_h = configuration_data()
config_h.set('HAVE_STDINT_H', cc.has_header('stdint.h'))
config_h.set('HAVE_UNISTD_H', cc.has_header('unistd.h'))
config_h.set_quoted('PACKAGE_STRING', 'libXau 1.0.11')

# Check for optional functions
config_h.set('HAVE_PATHCONF', cc.has_function('pathconf'))

configure_file(output: 'config.h', configuration: config_h)

libxau_sources = files(
  'AuDispose.c',
  'AuFileName.c',
  'AuGetAddr.c',
  'AuGetBest.c',
  'AuLock.c',
  'AuRead.c',
  'AuUnlock.c',
  'AuWrite.c',
)

libxau_inc = include_directories('include', '.')

libxau = static_library('Xau',
  libxau_sources,
  include_directories: libxau_inc,
  dependencies: xorgproto,
  c_args: ['-DHAVE_CONFIG_H'],
)

xau_dep = declare_dependency(
  link_with: libxau,
  include_directories: include_directories('include'),
  dependencies: xorgproto,
)

meson.override_dependency('xau', xau_dep)
