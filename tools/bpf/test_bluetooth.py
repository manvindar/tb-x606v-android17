import copy,struct,unittest
from unittest.mock import patch as mockpatch
import patch_bluetooth as port
class BluetoothTests(unittest.TestCase):
    def fixture(self):
        data=bytearray(512);sites=[]
        for off,target,normal,reg in [(64,128,100,20),(192,256,228,20),(320,384,None,0)]:
            word=0x36000000|(((target-off)//4)<<5)|reg;struct.pack_into('<I',data,off,word)
            sites.append({'offset':off,'word':word,'target':target,'normal_exit':normal})
        for site in sites:site['window_sha256']=port.digest(data[site['offset']-32:site['offset']+36])
        return bytes(data),{'bytes':len(data),'sha256':port.digest(data),'sites':sites}
    def test_status_redirect_preserves_condition_and_other_bytes(self):
        data,spec=self.fixture()
        with mockpatch.object(port,'SPEC',spec):out,receipt=port.patch(data)
        for site in spec['sites'][:2]:
            word=struct.unpack_from('<I',out,site['offset'])[0]
            self.assertEqual(word&0xfff8001f,0x36000014)
            self.assertEqual(port.tbz_target(word,site['offset']),site['normal_exit'])
        allowed={s['offset']+i for s in spec['sites'] for i in range(4)}
        self.assertTrue(all(i in allowed for i,(a,b) in enumerate(zip(data,out)) if a!=b));self.assertFalse(receipt['runtime_validation'])
    def test_ssp_only_is_nop(self):
        data,spec=self.fixture()
        with mockpatch.object(port,'SPEC',spec):out,_=port.patch(data)
        self.assertEqual(struct.unpack_from('<I',out,320)[0],0xd503201f)
    def test_unknown_binary_rejected(self):
        data,spec=self.fixture();changed=bytearray(data);changed[-1]=1
        with mockpatch.object(port,'SPEC',spec),self.assertRaisesRegex(ValueError,'Unknown'):port.patch(changed)
    def test_wrong_fatal_target_rejected(self):
        data,spec=self.fixture();spec['sites'][0]['target']=132
        with mockpatch.object(port,'SPEC',spec),self.assertRaisesRegex(ValueError,'error target'):port.patch(data)
    def test_out_of_range_normal_exit_rejected(self):
        data,spec=self.fixture();spec['sites'][0]['normal_exit']=1024
        with mockpatch.object(port,'SPEC',spec),self.assertRaisesRegex(ValueError,'out of range'):port.patch(data)
    def test_changed_window_rejected_even_with_updated_whole_hash(self):
        data,spec=self.fixture();changed=bytearray(data);changed[40]=1;spec['sha256']=port.digest(changed)
        with mockpatch.object(port,'SPEC',spec),self.assertRaisesRegex(ValueError,'window'):port.patch(changed)
if __name__=='__main__':unittest.main()
