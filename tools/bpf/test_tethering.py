import hashlib,struct,unittest
from unittest.mock import patch as mockpatch
import patch_tethering as port
class PortTests(unittest.TestCase):
    def fixture(self,target=80):
        data=bytearray(160);word=0x34000000|(((target-64)//4)<<5)
        struct.pack_into('<I',data,64,word)
        rec={'bytes':len(data),'sha256':port.digest(data),'patches':[{'offset':64,'word':word,'target':target,'window_sha256':port.digest(data[32:100])}]}
        return bytes(data),rec
    def test_exact_branch_skip_changes_only_instruction(self):
        data,rec=self.fixture()
        with mockpatch.dict(port.SPEC,{'test':rec}):result,receipt=port.patch('test',data)
        self.assertEqual(struct.unpack_from('<I',result,64)[0],0x14000004)
        self.assertEqual(result[:64],data[:64]);self.assertEqual(result[68:],data[68:])
        self.assertFalse(receipt['runtime_validation'])
    def test_unknown_hash_rejected(self):
        data,rec=self.fixture();changed=bytearray(data);changed[120]=1
        with mockpatch.dict(port.SPEC,{'test':rec}),self.assertRaisesRegex(ValueError,'Unknown'):port.patch('test',changed)
    def test_wrong_skip_target_rejected(self):
        data,rec=self.fixture();rec['patches'][0]['target']=84
        with mockpatch.dict(port.SPEC,{'test':rec}),self.assertRaisesRegex(ValueError,'skip target'):port.patch('test',data)
    def test_window_rejected_even_with_whole_hash_updated(self):
        data,rec=self.fixture();changed=bytearray(data);changed[40]=1;rec['sha256']=port.digest(changed)
        with mockpatch.dict(port.SPEC,{'test':rec}),self.assertRaisesRegex(ValueError,'window'):port.patch('test',changed)
    def test_nop_preserves_surrounding_bytes(self):
        data,rec=self.fixture();rec['patches'][0]['target']=None
        with mockpatch.dict(port.SPEC,{'test':rec}):result,_=port.patch('test',data)
        self.assertEqual(struct.unpack_from('<I',result,64)[0],0xd503201f)
        self.assertEqual(result[:64],data[:64]);self.assertEqual(result[68:],data[68:])
    def test_signed_branch_target(self):
        self.assertEqual(port.branch_target(0x35ffffe8,100),96)
if __name__=='__main__':unittest.main()
