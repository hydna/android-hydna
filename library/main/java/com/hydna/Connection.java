package com.hydna;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import java.lang.SecurityException;

/**
 *  This class is used internally by the Channel class.
 *  A user of the library should not create an instance of this class.
 */
public class Connection implements Runnable {

    private static Object LOCK = new Object();

    private static Map<String, ArrayList<Connection>> mConnections;

    private boolean mDestroying = false;

    private String mId;
    private String mHost;
    private short mPort;

    private SocketChannel mSocketChannel;
    private Socket mSocket;

    private ConcurrentHashMap<Integer, Channel> mChannelsByRoute;
    private ConcurrentHashMap<ByteBuffer, Channel> mChannelsByPath;

    private Thread mThread;

    private Sender mSender;

    static {
        mConnections = new HashMap<String, ArrayList<Connection>>();
    }

    class SenderCallback {
        void willTerminate(ChannelException error) {};
    }


    private class Sender implements Runnable {

        public final BlockingQueue<Frame> queue;

        private Thread mThread;
        private SocketChannel mChannel;
        private SenderCallback mCallback;
        private long mLastSendTick;

        public Sender() {
            queue = new LinkedBlockingQueue<Frame>();
            mThread = new Thread(this);
        }

        void start(SocketChannel channel, SenderCallback callback) {
            mChannel = channel;
            mCallback = callback;
            mThread.start();
        }

        public void run() {

            Frame heartbeatFrame = Frame.create(0, 0, 0, 0);

            try {
                sendFrame(heartbeatFrame);
            } catch (Exception e) {
                mCallback.willTerminate(new ChannelException(e.getMessage()));
                return;
            }

            for (;;) {

                Frame frame;

                try {
                    if (queue.isEmpty()) {
                        if (System.currentTimeMillis() - mLastSendTick >= 10000) {
                            try {
                                sendFrame(heartbeatFrame);
                            } catch (Exception e) {
                                mCallback.willTerminate(new ChannelException(e.getMessage()));
                                return;
                            }
                        }
                        Thread.sleep(1);
                        continue;
                    }
                    frame = queue.take();
                } catch (InterruptedException e) {
                    mCallback.willTerminate(new ChannelException(e.getMessage()));
                    return;
                }

                if (frame.isNullFrame()) {
                    // A null frame indicates that we are done.
                    return;
                }


                try {
                    sendFrame(frame);
                } catch (Exception e) {
                    mCallback.willTerminate(new ChannelException(e.getMessage()));
                    return;
                }
            }
        }

        void sendFrame(Frame frame) throws IOException {
            int n = -1;
            ByteBuffer data = frame.getBytes();
            int size = data.capacity();
            int offset = 0;

            while(offset < size) {
                n = mChannel.write(data);
                offset += n;
            }
            mLastSendTick = System.currentTimeMillis();
        }
    }

    /**
     *  Return an available connection or create a new one.
     *
     *  @param host The host associated with the connection.
     *  @param port The port associated with the connection.
     *  @param channel The Channel.
     *  @return The connection.
     */
    static Connection getConnection(String protocol,
                                    String host,
                                    int port,
                                    Channel channel) throws ChannelException {
        Connection connection = null;
        ArrayList<Connection> connections;
        String id;
        ByteBuffer path;

        id = protocol + host + port;
        path = ByteBuffer.wrap(channel.getBinaryPath());

        synchronized(LOCK) {
            if ((connections = mConnections.get(id)) == null) {
                connections = new ArrayList<Connection>();
                mConnections.put(id, connections);
            } else {
                for (Connection conn : connections) {
                    if (conn.isAvailable() &&
                        conn.mChannelsByPath.containsKey(path) == false) {
                        connection = conn;
                        break;
                    }
                }
            }

            if (connection == null) {
                connection = new Connection(id, host, port);
                connections.add(connection);
                connection.mChannelsByPath.put(path, channel);

                try {
                    connection.mThread.start();
                } catch (IllegalThreadStateException e) {
                    connections.remove(connection);
                    throw new ChannelException("Could not create connection thread");
                }
            }

        }

        return connection;
    }


    static void disposeConnection(Connection connection) {
        String id;

        id = connection.mId;

        if (id != null) {

            connection.mId = null;

            ArrayList<Connection> connections;

            if ((connections = mConnections.get(id)) == null) {
                return;
            }

            connections.remove(connection);
        }
    }

    /**
     *  Initializes a new Channel instance.
     *
     *  @param host The host the connection should connect to.
     *  @param port The port the connection should connect to.
     */
    public Connection(String id, String host, int port) {
        mId = id;
        mHost = host;
        mPort = (short)port;

        mChannelsByRoute = new ConcurrentHashMap<Integer, Channel>();
        mChannelsByPath = new ConcurrentHashMap<ByteBuffer, Channel>();

        mThread = new Thread(this);
        mSender = new Sender();
    }

    /**
     *  Decrease the reference count.
     *
     *  @param channelPtr The channel to dealloc.
     */
    void deallocChannel(Channel channel) {

        if (mDestroying) {
            return;
        }

        synchronized (LOCK) {
            int ptr = channel.getPtr();

            if (ptr > 0) {
                mChannelsByRoute.remove(ptr);
            }

            mChannelsByPath.remove(ByteBuffer.wrap(channel.getBinaryPath()));

            if (mChannelsByPath.size() == 0) {
                destroy(null);
            }
        }

    }

    public void run() {
        try {
            connect();
            handshakeHandler();
            SenderCallback callback = new SenderCallback() {
                @Override
                void willTerminate(ChannelException error) {
                    destroy(error);
                }
            };
            mSender.start(mSocketChannel, callback);
            receiveHandler();
        } catch (UnknownHostException e) {
            destroy(ChannelException.unableToResolve(mHost));
        } catch (UnresolvedAddressException e) {
            destroy(ChannelException.unableToResolve(mHost));
        } catch (IOException e) {
            destroy(ChannelException.unableToConnect(mHost, mPort));
        } catch (ChannelException e) {
            destroy(e);
        } catch (SecurityException e) {
            destroy(new ChannelException(e.getMessage()));
        }
    }

    /**
     *  Connect the connection.
     */
    private void connect()
        throws UnresolvedAddressException, IOException, SecurityException {

        InetAddress ip = InetAddress.getByName(mHost);
        SocketAddress address = new InetSocketAddress(ip, mPort);

        mSocketChannel = SocketChannel.open(address);
        mSocket = mSocketChannel.socket();

        try {
            mSocket.setTcpNoDelay(true);
        } catch (SocketException e) {
            System.err.println("WARNING: Could not set TCP_NODELAY");
        }

        DataOutputStream stream = new DataOutputStream(mSocket.getOutputStream());

        stream.writeBytes("GET / HTTP/1.1\r\n" +
                          "Connection: upgrade\r\n" +
                          "Upgrade: winksock/1\r\n" +
                          "Host: " + mHost);

        stream.writeBytes("\r\n\r\n");
    }


    /**
     *  Handle the Handshake response frame.
     */
    private void handshakeHandler() throws ChannelException, IOException {
        boolean fieldsLeft = true;
        boolean gotResponse = false;
        BufferedReader stream;


        stream = new BufferedReader(new InputStreamReader(mSocket.getInputStream()));

        while (fieldsLeft) {
            String line;

            try {
                line = stream.readLine();
                if (line.length() == 0) {
                    fieldsLeft = false;
                }
            } catch (IOException e) {
                throw ChannelException.badHttpResponse();
            }

            if (fieldsLeft) {
                // First line i a response, all others are fields
                if (!gotResponse) {
                    int code = 0;
                    int pos1, pos2;

                    // Take the response code from "HTTP/1.1 101
                    // Switching Protocols"
                    pos1 = line.indexOf(" ");
                    if (pos1 != -1) {
                        pos2 = line.indexOf(" ", pos1 + 1);

                        if (pos2 != -1) {
                            try {
                                code = Integer.parseInt(line.substring(pos1 + 1, pos2));
                            } catch (NumberFormatException e) {
                                throw ChannelException.badHttpResponse();
                            }
                        }
                    }

                    if (code != 101) {
                        throw ChannelException.unexpectedResponseCode(code);
                    }

                    gotResponse = true;
                } else {
                    line = line.toLowerCase();
                    int pos;

                    pos = line.indexOf("upgrade: ");
                    if (pos != -1) {
                        String header = line.substring(9);
                        if (!header.equals("winksock/1")) {
                            throw new ChannelException("Bad protocol version: " +
                                                   header);
                        }
                    }
                }
            }
        }
    }


    /**
     *  Handles all incoming data.
     */
    public void receiveHandler() {
        int size;

        ByteBuffer header = ByteBuffer.allocate(Frame.HEADER_SIZE + 2);
        header.order(ByteOrder.BIG_ENDIAN);
        ByteBuffer data;

        int offset = 0;
        int n = 1;

        for (;;) {
            try {
                while(offset < Frame.HEADER_SIZE + 2 && n >= 0) {
                    n = mSocketChannel.read(header);
                    offset += n;
                }
            } catch (Exception e) {
                n = -1;
            }

            if (n <= 0) {
                destroy(new ChannelException("Could not read from the connection"));
                break;
            }

            header.flip();

            size = (int)header.getShort() & 0xFFFF;
            data = ByteBuffer.allocate(size - Frame.HEADER_SIZE);
            data.order(ByteOrder.BIG_ENDIAN);

            try {
                while(offset < size + 2 && n >= 0) {
                    n = mSocketChannel.read(data);
                    offset += n;
                }
            } catch (Exception e) {
                n = -1;
            }

            if (n <= 0) {
                destroy(new ChannelException("Could not read from the connection"));
                break;
            }

            data.flip();

            Frame frame = Frame.fromHeader(header, data);

            switch (frame.getOp()) {

                case Frame.KEEPALIVE:
                    break;

                case Frame.OPEN:
                    processOpenFrame(frame);
                    break;

                case Frame.SIGNAL:
                case Frame.DATA:
                    processFrame(frame);
                    break;

                case Frame.RESOLVE:
                    processResolveFrame(frame);
                    break;
            }

            offset = 0;
            n = 1;
            header.clear();
        }
    }

    public void enqueueFrame(Frame frame) {
        if (mSender != null) {
            mSender.queue.add(frame);
        }
    }

    /**
     *  Process an open frame.
     *
     *  @param addr The address that should receive the open frame.
     *  @param errcode The error code of the open frame.
     *  @param payload The content of the open frame.
     */
    private void processOpenFrame(Frame frame) {
        Channel channel;

        int ptr = frame.getPtr();

        if ((channel = mChannelsByRoute.get(ptr)) == null) {
            // Ignore if no pointer is defined.
            return;
        }

        channel.postFrame(Frame.OPEN, frame);
    }

    void processFrame(Frame frame) {
        int op = frame.getOp();

        if (op == Frame.DATA && frame.hasPayload() == false) {
            destroy(ChannelException.protocolError());
            return;
        }

        int ptr = frame.getPtr();

        if (ptr == 0) {
            Iterator<Channel> it = mChannelsByRoute.values().iterator();
            while (it.hasNext()) {
                Channel channel = it.next();
                channel.postFrame(op, frame.clone());
            }
        } else {
            Channel channel = null;
            if ((channel = mChannelsByRoute.get(ptr)) == null) {
                destroy(ChannelException.protocolError());
                return;
            }

            channel.postFrame(op, frame);
        }

    }

    private void processResolveFrame(Frame frame) {
        Channel channel;

        channel = mChannelsByPath.get(frame.getData());

        if (channel == null) {
            return;
        }

        mChannelsByRoute.put(frame.getPtr(), channel);

        channel.postFrame(Frame.RESOLVE, frame);
    }

    boolean isAvailable() {
        return mDestroying == false &&
               mSocket != null &&
               mSocket.isClosed() == false &&
               mSocket.isInputShutdown() == false &&
               mSocket.isOutputShutdown() == false;
    }

    void willTerminate(ChannelException error) {
       destroy(error);

    }

    /**
     *  Destroy the connection.
     *
     *  @error The cause of the destroy.
     */
    private void destroy(ChannelException error) {
        synchronized (LOCK) {

            if (mDestroying) {
                return;
            }

            mDestroying = true;

            disposeConnection(this);
        }

        if (mSender != null) {
            try {
				mSender.queue.put(Frame.nullFrame);
			} catch (InterruptedException e) {
			}
            mSender = null;
        }

        if (mSocketChannel != null) {
            try {
                mSocketChannel.close();
            } catch (IOException e) {
            } finally {
                mSocketChannel = null;
            }
        }

        for (Channel channel : mChannelsByPath.values()) {
            channel.postError(error);
        }

        mChannelsByRoute.clear();
        mChannelsByPath.clear();
    }
}
