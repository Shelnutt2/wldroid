project(
  'drm-shim',
  'c',
  version : '0.1.0',
  default_options : ['c_std=c11'],
  meson_version : '>= 0.56',
)

cc = meson.get_compiler('c')

# ---- Dependencies ----
dep_drm = dependency('libdrm')
dep_pthread = dependency('threads')

drm_shim_deps = [dep_drm, dep_pthread]

# ---- Include directories ----
drm_shim_inc = include_directories('include')

# ---- Shared library (loaded via LD_PRELOAD) ----
drm_shim_lib = shared_library(
  'drm-shim',
  files('src/drm_shim.c'),
  include_directories : drm_shim_inc,
  dependencies : drm_shim_deps,
  gnu_symbol_visibility : 'default',
  install : true,
)

# ---- Declare dependency for subproject consumers ----
drm_shim_dep = declare_dependency(
  include_directories : drm_shim_inc,
  link_with : drm_shim_lib,
  dependencies : drm_shim_deps,
)

# ---- Tests (disabled by default) ----
if get_option('tests')
  test_common_inc = include_directories('include', 'tests')
  test_deps = [dep_drm, dep_pthread]

  test_drm_devices = executable(
    'test_drm_devices',
    'tests/test_drm_devices.c',
    'src/drm_shim.c',
    include_directories : test_common_inc,
    dependencies : test_deps,
  )
  test('drm_devices', test_drm_devices)

  test_drm_version = executable(
    'test_drm_version',
    'tests/test_drm_version.c',
    'src/drm_shim.c',
    include_directories : test_common_inc,
    dependencies : test_deps,
  )
  test('drm_version', test_drm_version)

  test_drm_prime = executable(
    'test_drm_prime',
    'tests/test_drm_prime.c',
    'src/drm_shim.c',
    include_directories : test_common_inc,
    dependencies : test_deps,
  )
  test('drm_prime', test_drm_prime)
endif
