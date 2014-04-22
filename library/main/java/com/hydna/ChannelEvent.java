package com.hydna;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharacterCodingException;

public class ChannelEvent {

    private Channel mTarget;

    private ByteBuffer mData;
    private String mUtfContent;
    private int mCtype;
    private int mPriority;
	
    public ChannelEvent(Channel target, int ctype, ByteBuffer data) {
        mTarget = target;
        mData = data;
        mCtype = ctype;
        mPriority = 0;
    }

    public ChannelEvent(Channel target, int ctype, int prio, ByteBuffer data) {
        this(target, ctype, data);
        mPriority = prio;
    }


    ChannelEvent(Channel target, String utfContent) {
        mData = null;
        mUtfContent = utfContent;
        mCtype = Frame.UTF8;
    }

    /**
     *  Returns the priority of the content, if a Message (onMessage), otherwise
     *  0. 
     *
     *  @return The priority of the content.
     */
    public int getPriority() {
        return mPriority;
    }


    static ChannelEvent fromFrame(Channel target, Frame frame) {
        return new ChannelEvent(target,
                                frame.getContentType(),
                                frame.getData());
    }


    static ChannelEvent fromDataFrame(Channel target, Frame frame) {
        return new ChannelEvent(target,
                                frame.getContentType(),
                                frame.getFlag(),
                                frame.getData());
    }

    /**
     *  Returns the Channel which this event belongs to.
     *
     *  @return Channel the underlying target channel
     */
    public Channel getChannel() {
        return mTarget;
    }


    /**
     *  Returns true if the content is flagged as Binary, else false
     *
     *  @return boolean true if content is flagged as Binary
     */
    public boolean isBinaryContent() {
        return mCtype == Frame.BINARY;
    }

    /**
     *  Returns true if the content is flagged as UTF-8, else false.
     *
     *  @return boolean true if content is flagged as UTF-8
     */
    public boolean isUtf8Content() {
        return mCtype == Frame.UTF8;
    }

    /**
     *  Returns the data associated with this ChannelData instance.
     *
     *  @return The content.
     */
    public ByteBuffer getData() {
        return mData;
    }

    /**
     *  Returns the data associated with this ChannelData instance as
     * an UTF-8 String.
     *
     *  @return The content or null if not of type UTF-8.
     */
    public String getString() {
        Charset charset;
        CharsetDecoder decoder;
        int pos;

        if (isUtf8Content() == false) {
            return null;
        }

        if (mUtfContent != null) {
            return mUtfContent;
        }

        pos = mData.position();
        charset = Charset.forName("UTF-8");
        decoder = charset.newDecoder();
        mUtfContent = null;

        try {
            mUtfContent = decoder.decode(mData).toString();
        } catch (CharacterCodingException ex) {
        } finally {
            mData.position(pos);
        }

        return mUtfContent;
    }
}
