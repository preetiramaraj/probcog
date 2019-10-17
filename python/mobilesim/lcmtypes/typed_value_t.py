"""LCM type definitions
This file automatically generated by lcm.
DO NOT MODIFY BY HAND!!!!
"""

try:
    import cStringIO.StringIO as BytesIO
except ImportError:
    from io import BytesIO
import struct

class typed_value_t(object):
    __slots__ = ["type", "value"]

    TYPE_INT = 1
    TYPE_DOUBLE = 2
    TYPE_STRING = 3
    TYPE_BOOL = 4
    TYPE_SHORT = 5
    TYPE_LONG = 6
    TYPE_BYTE = 7
    TYPE_FLOAT = 8

    def __init__(self):
        self.type = 0
        self.value = ""

    def encode(self):
        buf = BytesIO()
        buf.write(typed_value_t._get_packed_fingerprint())
        self._encode_one(buf)
        return buf.getvalue()

    def _encode_one(self, buf):
        buf.write(struct.pack(">i", self.type))
        __value_encoded = self.value.encode('utf-8')
        buf.write(struct.pack('>I', len(__value_encoded)+1))
        buf.write(__value_encoded)
        buf.write(b"\0")

    def decode(data):
        if hasattr(data, 'read'):
            buf = data
        else:
            buf = BytesIO(data)
        if buf.read(8) != typed_value_t._get_packed_fingerprint():
            raise ValueError("Decode error")
        return typed_value_t._decode_one(buf)
    decode = staticmethod(decode)

    def _decode_one(buf):
        self = typed_value_t()
        self.type = struct.unpack(">i", buf.read(4))[0]
        __value_len = struct.unpack('>I', buf.read(4))[0]
        self.value = buf.read(__value_len)[:-1].decode('utf-8', 'replace')
        return self
    _decode_one = staticmethod(_decode_one)

    _hash = None
    def _get_hash_recursive(parents):
        if typed_value_t in parents: return 0
        tmphash = (0x74e2726dfb7e734) & 0xffffffffffffffff
        tmphash  = (((tmphash<<1)&0xffffffffffffffff)  + (tmphash>>63)) & 0xffffffffffffffff
        return tmphash
    _get_hash_recursive = staticmethod(_get_hash_recursive)
    _packed_fingerprint = None

    def _get_packed_fingerprint():
        if typed_value_t._packed_fingerprint is None:
            typed_value_t._packed_fingerprint = struct.pack(">Q", typed_value_t._get_hash_recursive([]))
        return typed_value_t._packed_fingerprint
    _get_packed_fingerprint = staticmethod(_get_packed_fingerprint)

