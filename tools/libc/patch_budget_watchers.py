#!/usr/bin/env python3
"""Retain the established memory-budget workaround, using actual registered JNI methods.
Patches only package/process watcher trampolines proven by their JNI table entries,
signatures, instruction sequence and common branch target. No adjacency assumption.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys

SIGNATURE = '(Landroid/app/MemoryBudgetManager$OnOverBudgetListener;)J'
METHODS = {'nativeRegisterPackageBudgetWatcher': 1, 'nativeRegisterProcessBudgetWatcher': 2}
BTI_C = 0xd503245f


class Elf64:
    def __init__(self, data):
        self.data = data
        if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<HH', data, 16) != (3, 183):
            raise ValueError('Expected little-endian ARM64 shared ELF')
        phoff, shoff = struct.unpack_from('<QQ', data, 32)
        phsize, phnum, shsize, shnum, strings_index = struct.unpack_from('<HHHHH', data, 54)
        if phsize != 56 or shsize != 64 or strings_index >= shnum:
            raise ValueError('Unexpected ELF headers')
        self.segments = []
        for i in range(phnum):
            kind, flags, offset, address, _, size, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phsize)
            if kind == 1:
                if offset + size > len(data):
                    raise ValueError('Truncated ELF load segment')
                self.segments.append((offset, address, size, flags))
        sections = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * shsize) for i in range(shnum)]
        strings = sections[strings_index]
        names = data[strings[4]:strings[4] + strings[5]]
        self.relro = []
        for section in sections:
            name = names[section[0]:].split(b'\0', 1)[0]
            if name == b'.data.rel.ro':
                offset, size = section[4], section[5]
                if offset + size > len(data):
                    raise ValueError('Truncated JNI data section')
                self.relro.append((offset, size))
        if len(self.relro) != 1:
            raise ValueError('JNI registration data section missing or ambiguous')

    def offset(self, address, size=1, executable=False):
        matches = [offset + address - start for offset, start, length, flags in self.segments
                   if start <= address and address + size <= start + length and (not executable or flags & 1)]
        if len(matches) != 1:
            raise ValueError('Unmapped or ambiguous ELF address')
        return matches[0]

    def address(self, offset):
        matches = [start + offset - file_offset for file_offset, start, length, _ in self.segments
                   if file_offset <= offset < file_offset + length]
        if len(matches) != 1:
            raise ValueError('Unmapped ELF offset')
        return matches[0]

    def string(self, address):
        start = self.offset(address)
        end = self.data.find(b'\0', start)
        if end < 0 or end - start > 200:
            raise ValueError('Invalid JNI string')
        return self.data[start:end].decode('ascii')

    def registration(self, method):
        entries = []
        offset, size = self.relro[0]
        for cursor in range(offset, offset + size - 23, 8):
            name, signature, function = struct.unpack_from('<QQQ', self.data, cursor)
            try:
                if self.string(name) != method:
                    continue
                if self.string(signature) != SIGNATURE:
                    raise ValueError('Budget watcher JNI signature changed')
                self.offset(function, 16, executable=True)
                entries.append((cursor, function))
            except (UnicodeDecodeError, ValueError) as error:
                if str(error) == 'Budget watcher JNI signature changed':
                    raise
                continue
        if len(entries) != 1:
            raise ValueError('Budget watcher JNI registration missing or ambiguous: ' + method)
        return entries[0]


def patch(data):
    image = Elf64(data)
    records = []
    for method, kind in METHODS.items():
        entry, function = image.registration(method)
        offset = image.offset(function, 16, executable=True)
        bti = struct.unpack_from('<I', data, offset)[0] == BTI_C
        if bti:
            offset += 4
        move_arg, move_kind, branch = struct.unpack_from('<III', data, offset)
        if move_arg != 0xaa0203e1 or move_kind != 0x52800002 | (kind << 5) or branch >> 26 != 5:
            raise ValueError('Registered watcher trampoline instruction sequence changed')
        immediate = branch & 0x3ffffff
        if immediate & (1 << 25):
            immediate -= 1 << 26
        target = image.address(offset + 8) + immediate * 4
        image.offset(target, 4, executable=True)
        records.append({'method': method, 'signature': SIGNATURE,
                        'registration_address': hex(image.address(entry)), 'function_address': hex(function),
                        'patch_offset': offset, 'bti_preserved': bti, 'register_target': hex(target)})
    if records[0]['register_target'] != records[1]['register_target'] or records[0]['patch_offset'] == records[1]['patch_offset']:
        raise ValueError('Registered watchers do not share one unique registration routine')
    output = bytearray(data)
    for record in records:
        struct.pack_into('<III', output, record['patch_offset'], 0xd2800020, 0xd65f03c0, 0xd503201f)
    return bytes(output), records


if __name__ == '__main__':
    source, destination = sys.argv[1:3]
    original = Path(source).read_bytes()
    output, records = patch(original)
    Path(destination).write_bytes(output)
    receipt = {'source_sha256': hashlib.sha256(original).hexdigest(), 'output_sha256': hashlib.sha256(output).hexdigest(),
               'methods': records, 'scope': 'Same established no-events dummy-handle workaround; JNI-anchored target selection',
               'device_validation': False}
    Path(destination + '.budget-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))
