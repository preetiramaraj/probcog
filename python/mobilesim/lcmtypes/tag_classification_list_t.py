"""LCM type definitions
This file automatically generated by lcm.
DO NOT MODIFY BY HAND!!!!
"""

try:
    import cStringIO.StringIO as BytesIO
except ImportError:
    from io import BytesIO
import struct

import mobilesim.lcmtypes.tag_classification_t

class tag_classification_list_t(object):
    __slots__ = ["utime", "num_classifications", "classifications"]

    def __init__(self):
        self.utime = 0
        self.num_classifications = 0
        self.classifications = []

    def encode(self):
        buf = BytesIO()
        buf.write(tag_classification_list_t._get_packed_fingerprint())
        self._encode_one(buf)
        return buf.getvalue()

    def _encode_one(self, buf):
        buf.write(struct.pack(">qi", self.utime, self.num_classifications))
        for i0 in range(self.num_classifications):
            assert self.classifications[i0]._get_packed_fingerprint() == mobilesim.lcmtypes.tag_classification_t._get_packed_fingerprint()
            self.classifications[i0]._encode_one(buf)

    def decode(data):
        if hasattr(data, 'read'):
            buf = data
        else:
            buf = BytesIO(data)
        if buf.read(8) != tag_classification_list_t._get_packed_fingerprint():
            raise ValueError("Decode error")
        return tag_classification_list_t._decode_one(buf)
    decode = staticmethod(decode)

    def _decode_one(buf):
        self = tag_classification_list_t()
        self.utime, self.num_classifications = struct.unpack(">qi", buf.read(12))
        self.classifications = []
        for i0 in range(self.num_classifications):
            self.classifications.append(mobilesim.lcmtypes.tag_classification_t._decode_one(buf))
        return self
    _decode_one = staticmethod(_decode_one)

    _hash = None
    def _get_hash_recursive(parents):
        if tag_classification_list_t in parents: return 0
        newparents = parents + [tag_classification_list_t]
        tmphash = (0xffc072a4e3f6c6cf+ mobilesim.lcmtypes.tag_classification_t._get_hash_recursive(newparents)) & 0xffffffffffffffff
        tmphash  = (((tmphash<<1)&0xffffffffffffffff)  + (tmphash>>63)) & 0xffffffffffffffff
        return tmphash
    _get_hash_recursive = staticmethod(_get_hash_recursive)
    _packed_fingerprint = None

    def _get_packed_fingerprint():
        if tag_classification_list_t._packed_fingerprint is None:
            tag_classification_list_t._packed_fingerprint = struct.pack(">Q", tag_classification_list_t._get_hash_recursive([]))
        return tag_classification_list_t._packed_fingerprint
    _get_packed_fingerprint = staticmethod(_get_packed_fingerprint)

