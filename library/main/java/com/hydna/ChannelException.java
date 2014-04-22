package com.hydna;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharacterCodingException;

public class ChannelException extends Exception {

    private static final long serialVersionUID = -7144874937032709941L;
    private int mCode;
    private boolean mOpenDenied = false;

    public ChannelException(String message, int code) {
        super(message);
        mCode = code;
    }

    ChannelException(String message, int code, boolean openDenied) {
        this(message, code);
        mOpenDenied = openDenied;
    }

    public ChannelException(String message) {
        this(message, -1);
    }


    public int getCode() {
        return mCode;
    }

    boolean isOpenDeniedError() {
        return mOpenDenied;
    }

    static ChannelException fromOpenError(Frame frame) {
        int code;
        String message = "";
        ByteBuffer data;

        code = frame.getFlag();

        if (code < 7) {
            message = "Not allowed to open channel";
        }

        if (frame.hasPayload() && frame.isUtfPayload()) {
            data = frame.getData();

            Charset charset = Charset.forName("UTF-8");
            CharsetDecoder decoder = charset.newDecoder();
            int pos = data.position();

            try {
                message = decoder.decode(data).toString();
            } catch (CharacterCodingException ex) {
            } finally {
                data.position(pos);
            }
        }

        return new ChannelException(message, code, true);
    }

    static ChannelException fromFrame(Frame frame) {
        String message = "";
        ByteBuffer data;

        message = "Uknown error";

        data = frame.getData();

        if (frame.getContentType() == Frame.UTF8 && data != null) {

            Charset charset = Charset.forName("UTF-8");
            CharsetDecoder decoder = charset.newDecoder();
            int pos = data.position();

            try {
                message = decoder.decode(data).toString();
            } catch (CharacterCodingException ex) {
            } finally {
                data.position(pos);
            }
        }

        return new ChannelException(message);
    }

    static ChannelException unexpectedResponseCode(int code) {
        return new ChannelException("Unexpected response code, " + code, 0);
    }

    static ChannelException badHttpResponse() {
        return new ChannelException("Bad HTTP response from server");
    }

    static ChannelException protocolError() {
        return new ChannelException("Protocol error");
    }

    static ChannelException badPermission(String type) {
        return new ChannelException("You do not have permission to " + type);
    }

    static ChannelException notConnected() {
        return new ChannelException("Channel is not connected");
    }

    static ChannelException unableToConnect(String host, int port) {
        return new ChannelException("Could not connect to the host \"" +
                                host + "\" on the port " + port);
    }

    static ChannelException unableToResolve(String host) {
        return new ChannelException("The host \"" + host +
                                "\" could not be resolved");
    }
}
