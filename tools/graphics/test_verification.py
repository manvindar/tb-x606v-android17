import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
from common import dynamic_symbols, dump, extract_fs, protobuf_fields, run
from verify_final import compatibility_failed, ImageInventory, missing_symbols, unproven_flag_provider_imports, unresolved_mapper_interface


def library(imports=(), exports=(), needed=()):
    return {'abi': 'arm64', 'imports': set(imports), 'exports': set(exports), 'needed': list(needed)}


class VerificationTests(unittest.TestCase):
    def test_measured_arm64_indirect_function_type_preserves_version(self):
        row = '   516: 00000000000e7580   100 <OS specific>: 10 GLOBAL DEFAULT   15 memcpy@@LIBC'
        self.assertEqual(dynamic_symbols(row), [('GLOBAL', '15', 'memcpy@@LIBC')])
        row = '    39: 0000000000000000     0 FUNC    GLOBAL DEFAULT  UND sync_merge@LIBSYNC (4)'
        self.assertEqual(dynamic_symbols(row), [('GLOBAL', 'UND', 'sync_merge@LIBSYNC')])
        self.assertEqual(dynamic_symbols('Symbol table .dynsym contains entries:\nNum: Value Size Type Bind Vis Ndx Name'), [])

    def test_donor_failure_alone_still_rejects_final_candidate(self):
        keys = ('donor_failures', 'consumer_failures', 'flag_provider_consumers', 'mapper_callsite_candidates')
        clean = dict.fromkeys(keys, [])
        self.assertFalse(compatibility_failed(clean))
        for category in keys:
            failed = dict(clean, **{category: [{'missing': 'symbol'}]})
            self.assertTrue(compatibility_failed(failed), category)
        with self.assertRaises(KeyError):
            compatibility_failed({})

    def test_unversioned_provider_does_not_satisfy_versioned_import(self):
        self.assertEqual(missing_symbols({'memcpy@LIBC'}, [library(exports={'memcpy'})]), ['memcpy@LIBC'])

    def test_inline_mapper_access_requires_callsite_review(self):
        symbol = '_ZN7android9SingletonINS_19GraphicBufferMapperEE11getInstanceEv'
        self.assertEqual(unresolved_mapper_interface({symbol}, set()), [symbol])
        self.assertEqual(unresolved_mapper_interface(set(), {symbol}), [symbol])

    def test_direct_stable_mapper_wrapper_is_not_inline_access(self):
        self.assertEqual(unresolved_mapper_interface({'_ZN7android19GraphicBufferMapper9getStrideEPK13native_handlePj'}, set()), [])

    def test_changed_vtable_interface_rejected_despite_matching_export(self):
        names = {'_ZN3com7android8graphics5libui5flags12get_providerEv', '_ZTVN3com7android8graphics5libui5flags13flag_providerE'}
        self.assertEqual(set(unproven_flag_provider_imports(names)), names)
        self.assertEqual(missing_symbols(names, [library(exports=names)]), [])

    def test_unrelated_graphics_method_not_flag_provider(self):
        self.assertEqual(unproven_flag_provider_imports({'_ZN7android19GraphicBufferMapperC2Ev'}), [])

    def test_debugfs_partial_failure_rejected_even_with_exit_zero(self):
        from subprocess import CompletedProcess
        failure = CompletedProcess(['debugfs'], 0, 'some extracted files', 'rdump: Input/output error')
        with patch('common.subprocess.run', return_value=failure):
            with self.assertRaisesRegex(RuntimeError, 'Input/output error'):
                run('debugfs', '-R', 'rdump / destination', 'image')

    def test_zero_exit_missing_file_rejected(self):
        with tempfile.TemporaryDirectory() as scratch, patch('common.run', return_value=''):
            with self.assertRaisesRegex(RuntimeError, 'empty extraction'):
                dump('image', '/missing', Path(scratch) / 'missing')

    def test_zero_exit_empty_filesystem_rejected(self):
        with tempfile.TemporaryDirectory() as scratch, patch('common.run', return_value=''):
            image = Path(scratch) / 'image'
            data = bytearray(2048); data[1080:1082] = b'\x53\xef'; image.write_bytes(data)
            with self.assertRaisesRegex(RuntimeError, 'empty filesystem'):
                extract_fs(image, Path(scratch) / 'empty')

    def test_symbol_version_must_match(self):
        self.assertEqual(missing_symbols({'method@ABI2'}, [library(exports={'method@ABI1'})]), ['method@ABI2'])

    def test_codec_cannot_use_undeclared_system_dependency(self):
        inventory = ImageInventory.__new__(ImageInventory)
        inventory.contexts = {'codec': {'require': []}, 'system': {'provide': []}}
        inventory.own = lambda context, name, abi: None
        with self.assertRaisesRegex(RuntimeError, 'not required by APEX manifest'):
            inventory.resolve('codec', 'mapper2.so', 'arm64')

    def test_declared_but_missing_dependency_rejected(self):
        inventory = ImageInventory.__new__(ImageInventory)
        inventory.contexts = {'codec': {'require': ['mapper2.so'], 'provide': []}, 'system': {'provide': []}}
        inventory.own = lambda context, name, abi: None
        with self.assertRaisesRegex(RuntimeError, 'unresolved or ambiguous'):
            inventory.resolve('codec', 'mapper2.so', 'arm64')

    def test_transitive_own_namespace_and_missing_symbol(self):
        inventory = ImageInventory.__new__(ImageInventory)
        inventory.contexts = {'codec': {'require': [], 'provide': []}}
        mapper = library(exports={'mapper_method'}, needed=['common.so'])
        common = library(exports={'common_method'})
        objects = {'mapper2.so': mapper, 'common.so': common}
        inventory.own = lambda context, name, abi: ('codec', '/' + name, objects[name]) if name in objects else None
        ui = library(imports={'mapper_method', 'common_method', 'new_lock_method'}, needed=['mapper2.so'])
        providers, edges = inventory.closure('codec', '/libui.so', ui)
        self.assertEqual(len(edges), 2)
        self.assertEqual(missing_symbols(ui['imports'], providers), ['new_lock_method'])

    def test_corrupt_namespace_manifest_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Truncated'):
            protobuf_fields(b'\x42\x05a')


if __name__ == '__main__':
    unittest.main()
