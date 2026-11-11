package com.udunus.dexplus.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Rewrites two integer entries in the launcher's DataStore preferences file. */
public final class HomeGrid {
    private HomeGrid() {}

    public static int readInt(File file, String key) throws IOException {
        byte[] data = read(file);
        int i = 0;
        while (i < data.length) {
            int start = i;
            long[] tag = readVarint(data, i);
            i = (int) tag[1];
            int field = (int) (tag[0] >> 3);
            int wire = (int) (tag[0] & 7);
            if (wire != 2) {
                i = skip(data, i, wire);
                continue;
            }
            long[] len = readVarint(data, i);
            int content = (int) len[1];
            int end = content + (int) len[0];
            if (field == 1 && key.equals(entryName(data, content, end))) {
                return entryInt(data, content, end);
            }
            i = end;
            if (i < start) {
                break;
            }
        }
        return -1;
    }

    public static void writeInt(File file, String key, int value) throws IOException {
        byte[] data = read(file);
        byte[] next = replace(data, key, value);
        File temp = new File(file.getParentFile(), file.getName() + ".internaldex");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(next);
            out.flush();
        } finally {
            out.close();
        }
        if (!temp.renameTo(file)) {
            FileOutputStream direct = new FileOutputStream(file);
            try {
                direct.write(next);
                direct.flush();
            } finally {
                direct.close();
            }
            temp.delete();
        }
    }

    private static byte[] read(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return data;
        } finally {
            in.close();
        }
    }

    private static byte[] replace(byte[] data, String key, int value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(data.length + 16);
        int i = 0;
        boolean found = false;
        while (i < data.length) {
            int start = i;
            long[] tag = readVarint(data, i);
            i = (int) tag[1];
            int field = (int) (tag[0] >> 3);
            int wire = (int) (tag[0] & 7);
            if (wire != 2) {
                int end = skip(data, i, wire);
                out.write(data, start, end - start);
                i = end;
                continue;
            }
            long[] len = readVarint(data, i);
            int content = (int) len[1];
            int end = content + (int) len[0];
            if (field == 1 && key.equals(entryName(data, content, end))) {
                byte[] entry = encodeEntry(key, value);
                out.write(entry, 0, entry.length);
                found = true;
            } else {
                out.write(data, start, end - start);
            }
            i = end;
        }
        if (!found) {
            byte[] entry = encodeEntry(key, value);
            out.write(entry, 0, entry.length);
        }
        return out.toByteArray();
    }

    private static byte[] encodeEntry(String key, int value) {
        byte[] name = key.getBytes(StandardCharsets.UTF_8);
        byte[] val = concat(new byte[] {24}, writeVarint(value));
        byte[] body = concat(
                concat(new byte[] {10}, writeVarint(name.length), name),
                concat(new byte[] {18}, writeVarint(val.length), val));
        return concat(new byte[] {10}, writeVarint(body.length), body);
    }

    private static String entryName(byte[] data, int start, int end) {
        int i = start;
        while (i < end) {
            long[] tag = readVarint(data, i);
            i = (int) tag[1];
            int field = (int) (tag[0] >> 3);
            int wire = (int) (tag[0] & 7);
            if (wire == 2) {
                long[] len = readVarint(data, i);
                int content = (int) len[1];
                if (field == 1) {
                    return new String(data, content, (int) len[0], StandardCharsets.UTF_8);
                }
                i = content + (int) len[0];
            } else {
                i = skip(data, i, wire);
            }
        }
        return null;
    }

    private static int entryInt(byte[] data, int start, int end) {
        int i = start;
        while (i < end) {
            long[] tag = readVarint(data, i);
            i = (int) tag[1];
            int field = (int) (tag[0] >> 3);
            int wire = (int) (tag[0] & 7);
            if (wire == 2) {
                long[] len = readVarint(data, i);
                int content = (int) len[1];
                int innerEnd = content + (int) len[0];
                if (field == 2) {
                    int j = content;
                    while (j < innerEnd) {
                        long[] inner = readVarint(data, j);
                        j = (int) inner[1];
                        if (((int) (inner[0] >> 3)) == 3 && ((int) (inner[0] & 7)) == 0) {
                            long[] number = readVarint(data, j);
                            return (int) number[0];
                        }
                        j = skip(data, j, (int) (inner[0] & 7));
                    }
                }
                i = innerEnd;
            } else {
                i = skip(data, i, wire);
            }
        }
        return -1;
    }

    private static int skip(byte[] data, int i, int wire) {
        if (wire == 0) {
            return (int) readVarint(data, i)[1];
        }
        if (wire == 1) {
            return i + 8;
        }
        if (wire == 5) {
            return i + 4;
        }
        if (wire == 2) {
            long[] len = readVarint(data, i);
            return (int) len[1] + (int) len[0];
        }
        throw new IllegalArgumentException("wire " + wire);
    }

    /** @return [value, nextIndex] */
    private static long[] readVarint(byte[] data, int i) {
        long n = 0;
        int shift = 0;
        while (i < data.length) {
            int b = data[i++] & 0xff;
            n |= (long) (b & 0x7f) << shift;
            if (b < 0x80) {
                return new long[] {n, i};
            }
            shift += 7;
        }
        return new long[] {n, i};
    }

    private static byte[] writeVarint(int n) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4);
        long value = n & 0xffffffffL;
        while (true) {
            int b = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return out.toByteArray();
            }
        }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            n += parts[i].length;
        }
        byte[] out = new byte[n];
        int p = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, p, parts[i].length);
            p += parts[i].length;
        }
        return out;
    }
}
