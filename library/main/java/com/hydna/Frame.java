package com.hydna;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class Frame {
    static final Frame nullFrame = new Frame();

    static final short HEADER_SIZE = 0x05;

    // Content types
    static final int UTF8 = 0x00;
    static final int BINARY = 0x01;

    // Opcodes
    static final int KEEPALIVE = 0x00;
    static final int OPEN = 0x01;
    static final int DATA = 0x02;
    static final int SIGNAL = 0x03;
    static final int RESOLVE = 0x04;

    // Open Flags
    static final int OPEN_ALLOW = 0x0;
    static final int OPEN_REDIRECT = 0x1;
    static final int OPEN_DENY = 0x7;

    // Signal Flags
    static final int SIG_EMIT = 0x0;
    static final int SIG_END = 0x1;
    static final int SIG_ERROR = 0x7;

    // Bit masks
    static int FLAG_BITMASK = 0x7;

    static int OP_BITPOS = 3;
    static int OP_BITMASK = (0x7 << OP_BITPOS);

    static int CTYPE_BITPOS = 6;
    static int CTYPE_BITMASK = (0x1 << CTYPE_BITPOS);
    

    // Upper payload limit (10kb)
    static final int PAYLOAD_MAX_LIMIT = 0xFFFF - HEADER_SIZE;

    private int mPtr;
    private int mCtype;
    private int mOp;
    private int mFlag;
    private byte[] mData;

    Frame() {}

    public Frame(int ptr,
                 int ctype,
                 int op,
                 int flag,
                 byte[] data) {
        super();

        if (data != null && data.length > PAYLOAD_MAX_LIMIT) {
            throw new IllegalArgumentException("Payload max limit reached");
        }

        mPtr = ptr;
        mCtype = ctype;
        mOp = op;
        mFlag = flag;
        mData = data;
    }

    public static Frame create(int ptr, int ctype, int op, int flag) {
        return new Frame(ptr, ctype, op, flag, null);
    }

    public static Frame create(int ptr,
                               int ctype,
                               int op,
                               int flag,
                               byte[] data) {
        return new Frame(ptr, ctype, op, flag, data);
    }

    public static Frame resolveFrame(byte[] data) {
        return new Frame(0, UTF8, RESOLVE, 0, data);
    }

    public static Frame openFrame(int ptr, int mode, byte[] data) {
        return new Frame(ptr, UTF8, OPEN, mode, data);
    }

    public static Frame dataFrame(int ptr, int ctype, int prio, byte[] data) {
        return new Frame(ptr, ctype, DATA, prio, data);
    }

    public static Frame emitFrame(int ptr, int ctype, byte[] data) {
        return new Frame(ptr, ctype, SIGNAL, SIG_EMIT, data);
    }

    public static Frame endFrame(int ptr) {
        return new Frame(ptr, UTF8, SIGNAL, SIG_END, null);
    }

    public static Frame endFrame(int ptr, int ctype, byte[] data) {
        return new Frame(ptr, ctype, SIGNAL, SIG_END, data);
    }

    public static Frame fromHeader(ByteBuffer header, ByteBuffer buffer) {
    	int ptr = header.getInt(); 
        byte of = header.get();
        byte[] data = null;
        if (buffer != null) {
        	data = new byte[buffer.remaining()];
        	buffer.get(data);
    	}
        return new Frame(ptr,
                         (of & CTYPE_BITMASK) >> CTYPE_BITPOS,
                         (of & OP_BITMASK) >> OP_BITPOS,
                         (of & FLAG_BITMASK),
                         data);
    }

    boolean isNullFrame() {
        return this == nullFrame;
    }

    boolean hasPayload() {
        return mData != null && mData.length > 0;
    }

    boolean isUtfPayload() {
        return mCtype == UTF8;
    }

    int getPtr() {
        return mPtr;
    }

    int getContentType() {
        return mCtype;
    }

    int getOp() {
        return mOp;
    }

    int getFlag() {
        return mFlag;
    }

    public Frame clone() {
        byte[] data = null;
        if (mData != null) {
            data = mData.clone();
        }
        return new Frame(mPtr, mCtype, mOp, mFlag, data);
    }

    ByteBuffer getData() {
        return ByteBuffer.wrap(mData);
    }

    ByteBuffer getBytes() {
        short length = HEADER_SIZE;

        if (mData != null) {
            length += (short)(mData.length);
        }


        ByteBuffer bytes = ByteBuffer.allocate(length + 2);
        bytes.order(ByteOrder.BIG_ENDIAN);

        bytes.putShort(length);
        bytes.putInt(mPtr);
        bytes.put((byte)((mCtype << CTYPE_BITPOS) | (mOp << OP_BITPOS) | mFlag));

        if (mData != null) {
            bytes.put(mData);
        }

        bytes.flip();

        return bytes;
    }
}
