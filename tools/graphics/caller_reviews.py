"""Exact affected-interface evidence recognition; not general caller/ROM approval."""
import json
from pathlib import Path


def load_reviews():
    path = Path(__file__).resolve().parents[2] / 'docs/MAPPER-INTERFACE-REVIEWS.json'
    records = json.loads(path.read_text())
    if records['schema'] != 1 or not records['records']:
        raise RuntimeError('Invalid affected mapper review ledger')
    seen = set()
    for row in records['records']:
        key = (row['context'], row['file'], row['abi'], row['sha256'])
        if key in seen or row['scope'] != 'affected_mapper_receiver_interface_only' or row['runtime_validation'] is not False:
            raise RuntimeError('Duplicate or overbroad mapper interface review')
        seen.add(key)
        if not (path.parent / row['evidence']).is_file():
            raise RuntimeError('Missing affected-interface proof document')
    return records['records']


def match_review(records, context, virtual, abi, identity, size, symbols, binding):
    for row in records:
        if (context, virtual, abi, identity, size) != (row['context'], row['file'], row['abi'], row['sha256'], row['bytes']):
            continue
        if sorted(symbols) != sorted(row['interface_symbols']):
            return None
        if binding.get('namespace') != 'system' or binding.get('libui_sha256') != row['patched_libui_sha256'] or binding.get('latest_libui_sha256') != row['latest_libui_sha256']:
            return None
        if row['decoder_sha256'] and binding.get('decoder_sha256') != row['decoder_sha256']:
            return None
        if binding.get('missing_imports') != [] or binding.get('error'):
            return None
        return dict(row, binding=binding)
    return None
