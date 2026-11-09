"""
Self-contained, zero-dependency Minecraft NBT reader & writer (Python Standard Library).
Strictly adheres to standard Big-Endian binary NBT specification and Minecraft Structure Template format.
"""

import gzip
import struct
from io import BytesIO

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class NBTTag:
    def __init__(self, tag_type, value):
        self.tag_type = tag_type
        self.value = value


class TagByte(NBTTag):
    def __init__(self, value):
        super().__init__(TAG_BYTE, int(value))


class TagShort(NBTTag):
    def __init__(self, value):
        super().__init__(TAG_SHORT, int(value))


class TagInt(NBTTag):
    def __init__(self, value):
        super().__init__(TAG_INT, int(value))


class TagLong(NBTTag):
    def __init__(self, value):
        super().__init__(TAG_LONG, int(value))


class TagFloat(NBTTag):
    def __init__(self, value):
        super().__init__(TAG_FLOAT, float(value))


class TagDouble(NBTTag):
    def __init__(self, value):
        super().__init__(TAG_DOUBLE, float(value))


class TagString(NBTTag):
    def __init__(self, value):
        super().__init__(TAG_STRING, str(value))


class TagByteArray(NBTTag):
    def __init__(self, value):
        super().__init__(TAG_BYTE_ARRAY, bytes(value))


class TagIntArray(NBTTag):
    def __init__(self, value):
        super().__init__(TAG_INT_ARRAY, list(value))


class TagList(NBTTag):
    def __init__(self, element_type, items=None):
        super().__init__(TAG_LIST, items or [])
        self.element_type = element_type


class TagCompound(NBTTag):
    def __init__(self, items=None):
        super().__init__(TAG_COMPOUND, items or {})


def _write_string(s: str, out: BytesIO):
    encoded = s.encode("utf-8")
    out.write(struct.pack(">H", len(encoded)))
    out.write(encoded)


def _write_tag_header(tag_type: int, name: str, out: BytesIO):
    out.write(struct.pack(">b", tag_type))
    _write_string(name, out)


def _write_payload(tag: NBTTag, out: BytesIO):
    tt = tag.tag_type
    val = tag.value

    if tt == TAG_BYTE:
        out.write(struct.pack(">b", val))
    elif tt == TAG_SHORT:
        out.write(struct.pack(">h", val))
    elif tt == TAG_INT:
        out.write(struct.pack(">i", val))
    elif tt == TAG_LONG:
        out.write(struct.pack(">q", val))
    elif tt == TAG_FLOAT:
        out.write(struct.pack(">f", val))
    elif tt == TAG_DOUBLE:
        out.write(struct.pack(">d", val))
    elif tt == TAG_BYTE_ARRAY:
        out.write(struct.pack(">i", len(val)))
        out.write(val)
    elif tt == TAG_STRING:
        _write_string(val, out)
    elif tt == TAG_LIST:
        out.write(struct.pack(">b", tag.element_type))
        out.write(struct.pack(">i", len(val)))
        for elem in val:
            _write_payload(elem, out)
    elif tt == TAG_COMPOUND:
        for key, sub_tag in val.items():
            _write_tag_header(sub_tag.tag_type, key, out)
            _write_payload(sub_tag, out)
        out.write(b"\x00")  # TAG_End
    elif tt == TAG_INT_ARRAY:
        out.write(struct.pack(">i", len(val)))
        for x in val:
            out.write(struct.pack(">i", x))
    elif tt == TAG_LONG_ARRAY:
        out.write(struct.pack(">i", len(val)))
        for x in val:
            out.write(struct.pack(">q", x))
    else:
        raise ValueError(f"Unknown NBT tag type: {tt}")


def write_structure_template(file_path: str, size: tuple[int, int, int], palette: list[dict], blocks: list[dict], data_version: int = 3955):
    """
    Writes a Minecraft 1.21.1 Structure Template (.nbt) file.
    
    :param file_path: Output .nbt path.
    :param size: (sizeX, sizeY, sizeZ).
    :param palette: list of {'Name': '...', 'Properties': {...}} (optional Properties).
    :param blocks: list of {'pos': (x, y, z), 'state': int, 'nbt': TagCompound (optional)}.
    :param data_version: DataVersion integer (default 3955 for 1.21.1).
    """
    root = TagCompound()

    # 'size' -> TagList of TagInt
    size_list = TagList(TAG_INT, [TagInt(s) for s in size])
    root.value["size"] = size_list

    # 'entities' -> empty TagList of TagCompound
    root.value["entities"] = TagList(TAG_COMPOUND, [])

    # 'DataVersion' -> TagInt
    root.value["DataVersion"] = TagInt(data_version)

    # 'palette' -> TagList of TagCompound
    pal_list = []
    for p in palette:
        comp = TagCompound()
        comp.value["Name"] = TagString(p["Name"])
        if "Properties" in p and p["Properties"]:
            props = TagCompound()
            for pk, pv in p["Properties"].items():
                props.value[pk] = TagString(str(pv))
            comp.value["Properties"] = props
        pal_list.append(comp)
    root.value["palette"] = TagList(TAG_COMPOUND, pal_list)

    # 'blocks' -> TagList of TagCompound
    block_list = []
    for b in blocks:
        comp = TagCompound()
        comp.value["state"] = TagInt(b["state"])
        x, y, z = b["pos"]
        comp.value["pos"] = TagList(TAG_INT, [TagInt(x), TagInt(y), TagInt(z)])
        if "nbt" in b and b["nbt"] is not None:
            comp.value["nbt"] = b["nbt"]
        block_list.append(comp)
    root.value["blocks"] = TagList(TAG_COMPOUND, block_list)

    # Serialize to buffer
    buf = BytesIO()
    _write_tag_header(TAG_COMPOUND, "", buf)
    _write_payload(root, buf)
    raw_data = buf.getvalue()

    # Gzip compress and write
    with gzip.open(file_path, "wb", compresslevel=9) as f:
        f.write(raw_data)


def read_structure_template(file_path: str) -> dict:
    """Reads a Minecraft structure template .nbt and returns a python dictionary."""
    with gzip.open(file_path, "rb") as f:
        raw = f.read()
    
    stream = BytesIO(raw)
    tag_type = stream.read(1)
    if not tag_type or tag_type == b"\x00":
        return {}
    
    tt = tag_type[0]
    name_len = struct.unpack(">H", stream.read(2))[0]
    name = stream.read(name_len).decode("utf-8")
    
    def _read_payload(s, tag_t):
        if tag_t == TAG_BYTE: return struct.unpack(">b", s.read(1))[0]
        elif tag_t == TAG_SHORT: return struct.unpack(">h", s.read(2))[0]
        elif tag_t == TAG_INT: return struct.unpack(">i", s.read(4))[0]
        elif tag_t == TAG_LONG: return struct.unpack(">q", s.read(8))[0]
        elif tag_t == TAG_FLOAT: return struct.unpack(">f", s.read(4))[0]
        elif tag_t == TAG_DOUBLE: return struct.unpack(">d", s.read(8))[0]
        elif tag_t == TAG_BYTE_ARRAY:
            l = struct.unpack(">i", s.read(4))[0]
            return s.read(l)
        elif tag_t == TAG_STRING:
            l = struct.unpack(">H", s.read(2))[0]
            return s.read(l).decode("utf-8")
        elif tag_t == TAG_LIST:
            sub_t = s.read(1)[0]
            l = struct.unpack(">i", s.read(4))[0]
            return [_read_payload(s, sub_t) for _ in range(l)]
        elif tag_t == TAG_COMPOUND:
            res = {}
            while True:
                sub_tag_byte = s.read(1)
                if not sub_tag_byte or sub_tag_byte == b"\x00":
                    break
                sub_tt = sub_tag_byte[0]
                sub_name_len = struct.unpack(">H", s.read(2))[0]
                sub_name = s.read(sub_name_len).decode("utf-8")
                res[sub_name] = _read_payload(s, sub_tt)
            return res
        elif tag_t == TAG_INT_ARRAY:
            l = struct.unpack(">i", s.read(4))[0]
            return [struct.unpack(">i", s.read(4))[0] for _ in range(l)]
        elif tag_t == TAG_LONG_ARRAY:
            l = struct.unpack(">i", s.read(4))[0]
            return [struct.unpack(">q", s.read(8))[0] for _ in range(l)]
        raise ValueError(f"Unknown read tag type {tag_t}")

    return _read_payload(stream, tt)
