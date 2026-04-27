project(
  'gbm-shim',
  'c',
  version : '18.0.0',
  default_options : ['c_std=gnu11'],
  meson_version : '>= 0.56',
)

cc = meson.get_compiler('c')

# ---- Dependencies ----
dep_drm = dependency('libdrm')

# Android-only libraries — not needed on host (mocks replace them).
is_android = host_machine.system() == 'android' or host_machine.system() == 'linux' and cc.find_library('android', required : false).found()

if is_android
  dep_android = cc.find_library('android', required : true)
  dep_log     = cc.find_library('log', required : true)
  dep_dl      = cc.find_library('dl', required : true)
  gbm_deps = [dep_drm, dep_android, dep_log, dep_dl]
else
  dep_dl = cc.find_library('dl', required : false)
  gbm_deps = [dep_drm]
  if dep_dl.found()
    gbm_deps += dep_dl
  endif
endif

# ---- Include directories ----
gbm_inc = include_directories('include', 'src')
vendor_inc = include_directories('vendor-include')

# ---- Static library (Android cross-build) ----
if is_android
  gbm_lib = static_library(
    'gbm',
    files(
      'src/gbm_ahb.c',
      'src/gbm_ahb_formats.c',
    ),
    include_directories : gbm_inc,
    dependencies : gbm_deps,
    install : true,
  )

  # ---- Install public header ----
  install_headers('include/gbm.h')

  # ---- pkg-config ----
  pkg = import('pkgconfig')
  pkg.generate(
    gbm_lib,
    name : 'gbm',
    description : 'AHardwareBuffer-backed GBM shim for Android',
    requires : ['libdrm'],
  )

  # ---- Declare dependency for subproject consumers ----
  gbm_dep = declare_dependency(
    include_directories : gbm_inc,
    link_with : gbm_lib,
    dependencies : gbm_deps,
  )
endif

# ---- Tests ----
if get_option('tests')
  # Source files compiled into each test executable
  gbm_sources = files('src/gbm_ahb.c', 'src/gbm_ahb_formats.c')
  mock_sources = files('tests/ahb_mock.c')

  test_common_inc = include_directories('include', 'src', 'tests')

  if is_android
    test_inc  = [test_common_inc]
    test_srcs = gbm_sources
    test_deps = gbm_deps
  else
    # Host build: use vendor-include for Android stubs + mock implementations
    test_inc  = [test_common_inc, vendor_inc]
    test_srcs = gbm_sources + mock_sources
    test_deps = [dep_drm]
    if dep_dl.found()
      test_deps += dep_dl
    endif
  endif

  # Export symbols so dlsym(RTLD_DEFAULT, ...) finds mock functions on host.
  test_link_args = not is_android ? ['-rdynamic'] : []

  test_device = executable(
    'test_gbm_device',
    'tests/test_gbm_device.c',
    test_srcs,
    include_directories : test_inc,
    dependencies : test_deps,
    link_args : test_link_args,
  )
  test('gbm_device', test_device)

  test_bo = executable(
    'test_gbm_bo',
    'tests/test_gbm_bo.c',
    test_srcs,
    include_directories : test_inc,
    dependencies : test_deps,
    link_args : test_link_args,
  )
  test('gbm_bo', test_bo)

  test_formats = executable(
    'test_gbm_formats',
    'tests/test_gbm_formats.c',
    'src/gbm_ahb_formats.c',
    include_directories : test_inc,
    dependencies : test_deps,
  )
  test('gbm_formats', test_formats)

  test_map = executable(
    'test_gbm_map',
    'tests/test_gbm_map.c',
    test_srcs,
    include_directories : test_inc,
    dependencies : test_deps,
    link_args : test_link_args,
  )
  test('gbm_map', test_map)
endif
