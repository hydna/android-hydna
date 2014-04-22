package com.hydna;

import java.nio.ByteBuffer;

public class ChannelCloseEvent extends ChannelEvent  {

    boolean mWasClean = true;
    boolean mWasDenied = false;

    public ChannelCloseEvent(Channel target, int ctype, ByteBuffer data) {
        super(target, ctype, data);
    }

    ChannelCloseEvent(Channel target, String message) {
        super(target, message);
    }

    /**
     *  Checks if the channel can emit signals.
     *
     *  @return True if channel has signal support.
     */
    public String getReason() {
        return getString();
    }

    /**
     *  Returns 'true' if channel was closed clean without error, else 'false'.
     *
     *  @return True if was closed clean without error.
     */
    public boolean wasClean() {
        return mWasClean;
    }

    /**
     *  Returns 'true' if the open to channel was denied, else 'false'.
     *
     *  @return True if was closed due to an open deny.
     */
    public boolean wasDenied() {
        return mWasDenied;
    }

    static ChannelCloseEvent empty(Channel target) {
        return new ChannelCloseEvent(target,
                                     Frame.UTF8,
                                     ByteBuffer.allocate(0));
    }

    static ChannelCloseEvent fromFrame(Channel target, Frame frame) {
        return new ChannelCloseEvent(target,
                                     frame.getContentType(),
                                     frame.getData());
    }

    static ChannelCloseEvent fromError(Channel target, ChannelException error) {
        ChannelCloseEvent event;

        if (error == null) {
            event = new ChannelCloseEvent(target, "Unknown reason");
        } else {
            event = new ChannelCloseEvent(target, error.getMessage());
            event.mWasClean = false;
            event.mWasDenied = error.isOpenDeniedError();
        }

        return event;
    }
}
