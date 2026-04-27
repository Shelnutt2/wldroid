# xcb-proto — X11 protocol XML descriptions + Python xcbgen module
# Custom meson.build overlay (upstream uses autotools).
# This is a data-only package: no C compilation, just expose paths
# so libxcb's code generator can find the XML files and Python module.
project('xcb-proto', 'c', version: '1.17.0')

xcb_proto_dep = declare_dependency(
  variables: {
    'xcbgen_dir': meson.current_source_dir() / 'xcbgen',
    'xml_dir': meson.current_source_dir() / 'src',
  },
)

meson.override_dependency('xcb-proto', xcb_proto_dep)
