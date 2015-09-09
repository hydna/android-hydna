package com.hydna;

import android.os.Handler;
import android.os.Message;
import android.os.Looper;

import java.io.UnsupportedEncodingException;

import java.nio.ByteBuffer;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import java.net.MalformedURLException;
import java.net.URL;



/**
 *  This class is used as an interface to the library.
 *  A user of the library should use an instance of this class
 *  to communicate with a server.
 */
public class Channel {

    private int mPtr = 0;
    private String mPath;
    private byte[] mBinPath;
    private int mMode;
    private byte[] mToken;

    private boolean mConnected = false;
    private boolean mClosing = false;

    private Connection mConnection = null;

    final Handler mHandler;

    public void onConnect(ChannelEvent event) {}
    public void onMessage(ChannelEvent event) {}
    public void onSignal(ChannelEvent event) {}
    public void onClose(ChannelCloseEvent event) {}


    /**
     *  Initializes a new Channel instance with the Main Looper.
     */
    public Channel() {
        this(Looper.getMainLooper());
    }

    /**
     *  Initializes a new Channel instance with the Main Looper and then
     *  starts then call 'connect' with provided parameters.
     *
     *  @param url The URL to connect to,
     *  @param mode The mode in which to open the channel.
     */
    public Channel(URL url, int mode) throws ChannelException {
        this(Looper.getMainLooper());
        connect(url, mode);
    }

    /**
     *  Initializes a new Channel instance
     */
    public Channel(Looper looper) {
        mHandler = new Handler(looper) {
            @Override
            public void handleMessage(Message msg) {

                switch (msg.what) {

                    case Frame.RESOLVE:
                        handleResolveFrame((Frame)msg.obj);
                        break;

                    case Frame.OPEN:
                        handleOpenFrame((Frame)msg.obj);
                        break;

                    case Frame.DATA:
                        handleDataFrame((Frame)msg.obj);
                        break;

                    case Frame.SIGNAL:
                        handleSignalFrame((Frame)msg.obj);
                        break;

                    case 0x99:
                        destroy((ChannelException)msg.obj, null);
                        break;

                    default:
                        super.handleMessage(msg);
                        break;
                }
            }
        };
    }


    /**
     *  Get the underlying Path for this Channel
     *
     *  @return The path of this Channel.
     */
    public String getPath() {
        return mPath;
    }

    /**
     *  Checks the connected state for this Channel instance.
     *
     *  @return The connected state.
     */
    public boolean isConnected() {
        return mConnection != null && mConnected && mClosing == false;
    }

    /**
     *  Checks the closing state for this Channel instance.
     *
     *  @return The closing state.
     */
    public boolean isClosing() {
        return mClosing;
    }

    /**
     *  Checks if the channel is readable.
     *
     *  @return True if channel is readable.
     */
    public boolean isReadable() {
        return mConnected &&
               mClosing == false &&
               ((mMode & ChannelMode.READ) == ChannelMode.READ);
    }

    /**
     *  Checks if the channel is writable.
     *
     *  @return True if channel is writable.
     */
    public boolean isWritable() {
        return mConnected &&
               mClosing == false &&
               ((mMode & ChannelMode.WRITE) == ChannelMode.WRITE);
    }

    /**
     *  Checks if the channel can emit signals.
     *
     *  @return True if channel has signal support.
     */
    public boolean isEmitable() {
        return mConnected &&
               mClosing == false &&
               ((mMode & ChannelMode.EMIT) == ChannelMode.EMIT);
    }


    /**
     *  Connects the channel to the specified channel. If the connection
     *  fails, an exception is thrown.
     *
     *  @param url The URL to connect to,
     *  @param mode The mode in which to open the channel.
     */
    public void connect(String url, int mode)
        throws MalformedURLException, ChannelException {
        Pattern p = Pattern.compile("[a-zA-Z]://");
        Matcher m = p.matcher(url);
        connect(new URL(m.matches() == false ? "http://" + url : url), mode);
    }

    /**
     *  Connects the channel to the specified channel. If the connection
     *  fails, an exception is thrown.
     *
     *  @param url The URL to connect to,
     *  @param mode The mode in which to open the channel.
     */
    public void connect(URL url, int mode) throws ChannelException {

        if (isConnected()) {
            throw new ChannelException("Already connecting/connected");
        }

        if (mClosing) {
            throw new ChannelException("Channel is closing");
        }

        if (mode < ChannelMode.LISTEN ||
            mode > ChannelMode.READWRITEEMIT) {
            throw new ChannelException("Invalid channel mode");
        }


        mMode = mode;

        String tokens = "";

        if (url.getProtocol().equals("http") == false) {
            if (url.getProtocol().equals("https")) {
                throw new ChannelException("The protocol HTTPS is not supported");
            } else {
                throw new ChannelException("Bad protocol: '" + url.getProtocol() + "'");
            }
        }

        mPath = url.getPath();

        if (mPath.length() == 0 || mPath.charAt(0) != '/') {
            mPath = "/" + mPath;
        }

        try {
            mBinPath = mPath.getBytes("US-ASCII");
        } catch (UnsupportedEncodingException e) {
            throw new ChannelException("Unable to encode path");
        }

        tokens = url.getQuery();

        if (tokens != null && tokens != "") {
            mToken = getBytes(tokens, "Unable to encode token data");
        } else {
            tokens = null;
        }

        mConnection = Connection.getConnection(url.getProtocol(),
                                               url.getHost(),
                                               url.getPort() == -1 ? url.getDefaultPort() : url.getPort(),
                                               this);

        mConnection.enqueueFrame(Frame.resolveFrame(mBinPath));
    }

    /**
     *  Sends a UTF8 data message to the channel with priority 0.
     *
     *  @param data The payload to write to the channel.
     */
    public void send(String message) throws ChannelException {
        send(Frame.UTF8, 0, getBytes(message));
    }

    /**
     *  Sends a UTF8 data message to the channel with specified priority.
     *
     *  @param data The payload to write to the channel.
     *  @param priority The priority of the payload.
     */
    public void send(String message, int priority) throws ChannelException {
        send(Frame.UTF8, priority, getBytes(message));
    }

    /**
     *  Sends a binary data message to the channel with priority 0.
     *
     *  @param data The payload to write to the channel.
     */
    public void send(byte[] data) throws ChannelException {
        send(Frame.BINARY, 0, data);
    }

    /**
     *  Sends a binary data message with specified priority.
     *
     *  @param data The payload to write to the channel.
     *  @param priority The priority of the payload.
     */
    public void send(byte[] data, int priority) throws ChannelException {
        send(Frame.BINARY, priority, data);
    }

    /**
     *  Sends a binary data message to the channel with priority 0.
     *
     *  @param data The payload to write to the channel.
     */
    public void send(ByteBuffer buffer) throws ChannelException {
        send(Frame.BINARY, 0, getBytes(buffer));
    }

    /**
     *  Sends a binary data message with specified priority.
     *
     *  @param data The payload to write to the channel.
     *  @param priority The priority of the payload.
     */
    public void send(ByteBuffer buffer, int priority) throws ChannelException {
        send(Frame.BINARY, priority, getBytes(buffer));
    }

    /**
     *  Sends UTF8 signal to the channel.
     *
     *  @param data The data to write to the channel.
     *  @param type The type of the signal.
     */
    public void emit(String message) throws ChannelException {
        emit(Frame.UTF8, getBytes(message));
    }

    /**
     *  Sends a binary signal to the channel.
     *
     *  @param data The data to write to the channel.
     *  @param type The type of the signal.
     */
    public void emit(byte[] data) throws ChannelException {
        emit(Frame.BINARY, data);
    }

    /**
     *  Sends a binary signal to the channel.
     *
     *  @param data The data to write to the channel.
     *  @param type The type of the signal.
     */
    public void emit(ByteBuffer buffer) throws ChannelException {
        emit(Frame.BINARY, getBytes(buffer));
    }

    /**
     *  Closes the Channel instance without any message.
     */
    public void close() throws ChannelException {
        close(Frame.UTF8, null);
    }

    /**
     *  Closes the Channel instance with a UTF8 message.
     */
    public void close(String message) throws ChannelException {
        close(Frame.UTF8, getBytes(message));
    }

    /**
     *  Closes the Channel instance with a binary message.
     */
    public void close(byte[] data) throws ChannelException {
        close(Frame.BINARY, data);
    }

    /**
     *  Closes the Channel instance with a binary message.
     */
    public void close(ByteBuffer buffer) throws ChannelException {
        close(Frame.BINARY, getBytes(buffer));
    }

    byte[] getBinaryPath() {
        return mBinPath;
    }

    int getPtr() {
        return mPtr;
    }


    void postFrame(int opcode, Frame frame) {
        if (mHandler == null) {
            return;
        }

        Message message = mHandler.obtainMessage(opcode, frame);
        message.sendToTarget();
    }

    void postError(ChannelException error) {
        if (mHandler == null) {
            return;
        }

        Message message = mHandler.obtainMessage(0x99, error);
        message.sendToTarget();
    }

    void handleResolveFrame(Frame frame) {

        if (mPtr != 0) {
            return;
        }

        if (frame.getFlag() != Frame.OPEN_ALLOW) {
            destroy(new ChannelException("Unable to resolve path"), null);
            return;
        }

        mPtr = frame.getPtr();

        Frame openFrame = Frame.openFrame(mPtr, mMode, mToken);
        mConnection.enqueueFrame(openFrame);
    }

    void handleOpenFrame(Frame frame) {

        if (mConnected) {
            return;
        }

        if (frame.getFlag() == Frame.OPEN_ALLOW) {
            mConnected = true;
            onConnect(ChannelEvent.fromFrame(this, frame));
        } else {
            destroy(ChannelException.fromOpenError(frame), null);
        }

    }

    void handleSignalFrame(Frame frame) {
        switch (frame.getFlag()) {

            case Frame.SIG_EMIT:
                onSignal(ChannelEvent.fromFrame(this, frame));
                break;

            case Frame.SIG_END:
                destroy(null, ChannelCloseEvent.fromFrame(this, frame));
                break;

            default:
                destroy(ChannelException.fromFrame(frame), null);
                break;

        }
    }


    void handleDataFrame(Frame frame) {
        onMessage(ChannelEvent.fromDataFrame(this, frame));
    }


    /**
     *  Internally destroy channel.
     *
     *  @param error The cause of the destroy.
     */
    void destroy(ChannelException error, ChannelEvent event) {
        Connection connection = mConnection;
        int ptr = mPtr;
        boolean closing = mClosing;
        Frame frame;

        mConnection = null;

        if (connection != null) {

            // Tell server that we received the end signal
            if (event != null && closing == false) {
                frame = Frame.endFrame(ptr);
                connection.enqueueFrame(frame);
            }

            connection.deallocChannel(this);
        }

        mPtr = 0;
        mConnected = false;
        mClosing = false;

        if (closing) {
            // Always create a clean close event if user is responsible
            onClose(ChannelCloseEvent.empty(this));
            return;
        }

        if (event instanceof ChannelCloseEvent) {
            onClose((ChannelCloseEvent)event);
            return;
        }

        onClose(ChannelCloseEvent.fromError(this, error));
    }

    /**
     *  Sends a binary data message with specified priority and ContentType.
     *
     *  @param ctype The ContentType of the payload
     *  @param priority The priority of the payload.
     *  @param data The payload to write to the channel.
     */
    void send(int ctype, int priority, byte[] data)
        throws ChannelException {

        if (isConnected() == false) {
            throw ChannelException.notConnected();
        }

        if (data == null || data.length == 0) {
            throw new ChannelException("Payload data cannot be zero-length");
        }

        if (priority < 0 || priority > 7) {
            throw new ChannelException("Priority must be between 0 - 7");
        }

        if (isWritable() == false) {
            throw ChannelException.badPermission("write");
        }

        Frame frame = Frame.dataFrame(mPtr, ctype, priority, data);
        mConnection.enqueueFrame(frame);
    }

    /**
     *  Sends data signal to the channel.
     *
     *  @param data The data to write to the channel.
     *  @param type The type of the signal.
     */
    void emit(int ctype, byte[] data) throws ChannelException {

        if (isConnected() == false) {
            throw ChannelException.notConnected();
        }

        if (isEmitable() == false) {
            throw ChannelException.badPermission("emit");
        }

        Frame frame = Frame.emitFrame(mPtr, ctype, data);
        mConnection.enqueueFrame(frame);
    }

    /**
     *  Closes the Channel instance.
     */
    void close(int ctype, byte[] data) throws ChannelException {

        if (isConnected() == false) {
            throw ChannelException.notConnected();
        }

        if (mClosing) {
            throw new ChannelException("Channel is already closing");
        }

        mClosing = true;

        Frame frame = Frame.endFrame(mPtr, ctype, data);
        mConnection.enqueueFrame(frame);
    }

    byte[] getBytes(ByteBuffer buffer) {
        byte[] data = null;
        if (buffer != null) {
          data = new byte[buffer.remaining()];
          buffer.get(data);
        }
        return data;
    }

    byte[] getBytes(String message) throws ChannelException {
        return getBytes(message, null);
    }

    byte[] getBytes(String message, String errmsg) throws ChannelException {
        try {
            return message.getBytes("UTF-8");
        } catch (UnsupportedEncodingException err) {
            throw new ChannelException(errmsg == null ? err.getMessage() : errmsg);
        }
    }
}
