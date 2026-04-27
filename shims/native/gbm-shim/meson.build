project(
  'gbm-shim',
  'c',
  version : '18.0.0',
  default_options : ['c_std=c11'],
  meson_version : '>= 0.56',
)

cc = meson.get_compiler('c')

# ---- Dependencies ----
dep_drm = dependency('libdrm')
dep_android = cc.find_library('android', required : true)
dep_log     = cc.find_library('log', required : true)
dep_dl      = cc.find_library('dl', required : true)

gbm_deps = [dep_drm, dep_android, dep_log, dep_dl]

# ---- Include directories ----
gbm_inc = include_directories('include', 'src')

# ---- Static library ----
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

# ---- Tests (on-device only; disabled by default) ----
if get_option('tests')
  test_deps = gbm_deps

  test_common_inc = include_directories('include', 'src', 'tests')

  test_device = executable(
    'test_gbm_device',
    'tests/test_gbm_device.c',
    'src/gbm_ahb.c',
    'src/gbm_ahb_formats.c',
    include_directories : test_common_inc,
    dependencies : test_deps,
  )
  test('gbm_device', test_device)

  test_bo = executable(
    'test_gbm_bo',
    'tests/test_gbm_bo.c',
    'src/gbm_ahb.c',
    'src/gbm_ahb_formats.c',
    include_directories : test_common_inc,
    dependencies : test_deps,
  )
  test('gbm_bo', test_bo)

  test_formats = executable(
    'test_gbm_formats',
    'tests/test_gbm_formats.c',
    'src/gbm_ahb_formats.c',
    include_directories : test_common_inc,
    dependencies : test_deps,
  )
  test('gbm_formats', test_formats)

  test_map = executable(
    'test_gbm_map',
    'tests/test_gbm_map.c',
    'src/gbm_ahb.c',
    'src/gbm_ahb_formats.c',
    include_directories : test_common_inc,
    dependencies : test_deps,
  )
  test('gbm_map', test_map)
endif
