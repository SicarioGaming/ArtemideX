"""Compare two ELF shared libraries, and say how close they are.

    python3 elf_compare.py COMMITTED REBUILT

Prints one word, then a short explanation:

  identical  the two files are the same, byte for byte
  build-id   they differ only in the 20-byte GNU build ID
  code       every byte that Android loads into memory is the same once the build ID is set
             aside. Only parts the loader never reads differ, such as the .comment section
             where the compiler writes its own name.
  different  the code or data differ

The build ID is a hash the linker works out over the whole unstripped file, debug info
included, and the debug info holds the folder the build ran in. So the same source built in
two folders, or by the Windows and Linux versions of the same NDK, can come out different
there and only there.

Exit status: 0 identical, 1 build-id, 2 code, 3 different, 4 not a 64-bit little-endian ELF.
"""
import struct
import sys

PT_LOAD = 1
PT_NOTE = 4
NT_GNU_BUILD_ID = 3


class NotElf(Exception):
    pass


def program_headers(data):
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise NotElf("not a 64-bit little-endian ELF file")
    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    headers = []
    for i in range(e_phnum):
        start = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = \
            struct.unpack_from("<IIQQQQQQ", data, start)
        headers.append((p_type, p_offset, p_filesz, data[start:start + e_phentsize]))
    return headers


def zero_build_id(data):
    """The file with the build ID's bytes set to zero. Found through the PT_NOTE segments,
    so it works on stripped files too."""
    buf = bytearray(data)
    for p_type, offset, size, _ in program_headers(data):
        if p_type != PT_NOTE:
            continue
        pos, end = offset, offset + size
        while pos + 12 <= end:
            namesz, descsz, ntype = struct.unpack_from("<III", data, pos)
            name_start = pos + 12
            desc_start = name_start + ((namesz + 3) & ~3)
            name = bytes(data[name_start:name_start + namesz]).rstrip(b"\0")
            if name == b"GNU" and ntype == NT_GNU_BUILD_ID:
                buf[desc_start:desc_start + descsz] = bytes(descsz)
            pos = desc_start + ((descsz + 3) & ~3)
    return bytes(buf)


def loaded_image(data):
    """What the Android loader reads: the program headers and the bytes of every PT_LOAD
    segment, with the build ID zeroed. The ELF header's section table fields (e_shoff, e_shnum,
    e_shstrndx) are zeroed too: they move when a section the loader ignores changes size."""
    buf = bytearray(zero_build_id(data))
    buf[0x28:0x30] = bytes(8)
    buf[0x3C:0x40] = bytes(4)
    parts = []
    for p_type, offset, size, header in program_headers(data):
        parts.append(header)
        if p_type == PT_LOAD:
            parts.append(bytes(buf[offset:offset + size]))
    return b"".join(parts)


def compare(a, b):
    if a == b:
        return "identical", 0
    if zero_build_id(a) == zero_build_id(b):
        return "build-id", 1
    if loaded_image(a) == loaded_image(b):
        return "code", 2
    return "different", 3


EXPLAIN = {
    "identical": "the files are the same, byte for byte",
    "build-id": "the files differ only in the 20-byte build ID",
    "code": "everything Android loads is the same; only the build ID and sections it never "
            "reads (such as the compiler name in .comment) differ",
    "different": "the code or data differ",
}


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 4
    with open(argv[1], "rb") as f:
        a = f.read()
    with open(argv[2], "rb") as f:
        b = f.read()
    try:
        word, status = compare(a, b)
    except (NotElf, struct.error) as e:
        print(f"not-elf {e}")
        return 4
    print(f"{word} {EXPLAIN[word]}")
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv))
