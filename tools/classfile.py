"""Read classfile structure without loading game classes or executing downloaded code."""
import struct


def methods(data, include_fields=False):
    """Return class name, methods and references; optionally append declared fields."""
    offset = 0

    def number(fmt):
        nonlocal offset
        result = struct.unpack_from('>' + fmt, data, offset)[0]
        offset += struct.calcsize(fmt)
        return result

    def skip(size):
        nonlocal offset
        offset += size
        if offset > len(data):
            raise ValueError('truncated classfile')

    if number('I') != 0xCAFEBABE:
        raise ValueError('not a classfile')
    skip(4)
    count = number('H')
    pool = [None] * count
    index = 1
    while index < count:
        tag = number('B')
        if tag == 1:
            length = number('H')
            pool[index] = data[offset:offset + length].decode('utf-8', errors='replace')
            skip(length)
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, number('H'))
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, number('H'), number('H'))
        elif tag in (3, 4):
            skip(4)
        elif tag in (5, 6):
            skip(8)
            index += 1
        elif tag == 15:
            skip(3)
        else:
            raise ValueError('unknown classfile tag ' + str(tag))
        index += 1
    skip(2)
    class_name = pool[pool[number('H')][1]]
    skip(2)
    skip(number('H') * 2)

    def members():
        result = []
        for _ in range(number('H')):
            access = number('H')
            name, descriptor = pool[number('H')], pool[number('H')]
            attributes = {}
            for _ in range(number('H')):
                kind, size = pool[number('H')], number('I')
                attributes[kind] = data[offset:offset + size]
                skip(size)
            result.append((name, descriptor, access, attributes))
        return result

    fields = members()
    result = members()
    refs = []
    refs_by_index = {}
    for index, entry in enumerate(pool):
        if isinstance(entry, tuple) and entry[0] in (9, 10, 11):
            owner = pool[pool[entry[1]][1]]
            name_type = pool[entry[2]]
            ref = (owner, pool[name_type[1]], pool[name_type[2]])
            refs.append(ref)
            refs_by_index[index] = ref
    for _, _, _, attributes in result:
        if "Code" in attributes:
            attributes["_invocations"] = code_invocations(attributes["Code"], refs_by_index)
    if include_fields:
        return class_name, result, refs, fields
    return class_name, result, refs


def code_invocations(code_attribute, references):
    """Decode instruction boundaries; return member calls without matching operand bytes."""
    length = struct.unpack_from('>I', code_attribute, 4)[0]
    code = code_attribute[8:8 + length]
    at = 0
    result = []
    two = {16, 18, *range(21, 26), *range(54, 59), 169, 188}
    three = {17, 19, 20, 132, *range(153, 169), *range(178, 185), 187, 189, 192, 193, 198, 199}
    while at < len(code):
        opcode = code[at]
        size = 1
        if opcode in (182, 183, 184, 185):
            index = struct.unpack_from('>H', code, at + 1)[0]
            result.append(references[index])
        if opcode in two:
            size = 2
        elif opcode in three:
            size = 3
        elif opcode in (185, 186, 200, 201):
            size = 5
        elif opcode == 197:
            size = 4
        elif opcode == 196:
            size = 6 if code[at + 1] == 132 else 4
        elif opcode in (170, 171):
            start = (at + 4) & ~3
            if opcode == 170:
                low, high = struct.unpack_from('>ii', code, start + 4)
                size = start + 12 + 4 * (high - low + 1) - at
            else:
                pairs = struct.unpack_from('>i', code, start + 4)[0]
                size = start + 8 + 8 * pairs - at
        if size <= 0 or at + size > len(code):
            raise ValueError('invalid JVM instruction length')
        at += size
    return result
