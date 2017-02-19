package org.rouplex.platform.tcp;

import org.rouplex.platform.*;
import org.rouplex.platform.rr.*;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.LinkedHashSet;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ChannelQueue {
    private enum RequestServiceType {
        Sync, AsyncSingle, AsyncMultiple
    }

    private final RouplexTcpServer rouplexTcpServer;
    private final SelectionKey selectionKey;
    private final RouplexService serviceProvider;
    private final RequestServiceType requestServiceType;

    private int maxRequests = Integer.MAX_VALUE;
    private int requestExpirationMillis = Integer.MAX_VALUE;
    private int replyExpirationMillis = Integer.MAX_VALUE;

    private final LinkedHashSet<Request<byte[]>> requests = new LinkedHashSet<Request<byte[]>>();
    final LinkedHashSet<Reply<ByteBuffer>> replies = new LinkedHashSet<Reply<ByteBuffer>>();

    ChannelQueue(RouplexTcpServer rouplexTcpServer, SelectionKey selectionKey) {
        this.rouplexTcpServer = rouplexTcpServer;
        this.selectionKey = selectionKey;

        this.serviceProvider = rouplexTcpServer.serviceProvider; // just a shortcut
        if (serviceProvider instanceof SyncReplyService) {
            requestServiceType = RequestServiceType.Sync;
        } else if (serviceProvider instanceof AsyncReplyService) {
            requestServiceType = RequestServiceType.AsyncSingle;
        } else if (serviceProvider instanceof AsyncRepliesService) {
            requestServiceType = RequestServiceType.AsyncMultiple;
        } else {
            throw new Error("Implementation error: Unknown serviceProvider type: " + serviceProvider);
        }
    }

    /**
     * Size of the request queue, beyond which new or old requests get dropped, depending on the value of
     * {@link #setMaxRequests(int)}. It can be set dynamically and it must be honored eventually.
     *
     * @param maxRequests
     *         Any non-negative value
     */
    void setMaxRequests(int maxRequests) {
        this.maxRequests = maxRequests;
    }

    /**
     * @param requestExpirationMillis
     *         if set to -1, the requests don't time out ever. Requests coming when the requests queue is full, will be
     *         cancelled and put straight to replies queue (without making it to request queue).
     *         if set to 0, the requests don't time out normally. Requests coming when the requests queue is full, will
     *         be added to the requests queue after the oldest one gets dropped.
     *         if set to Integer.MAX_VALUE, the requests don't time out ever. Requests coming when the requests queue
     *         is full, will be simply dropped.
     *         Any other value expires the request after the specified value in milliseconds.
     */
    void setRequestExpirationMillis(int requestExpirationMillis) {
        this.requestExpirationMillis = requestExpirationMillis;
    }

    void setReplyExpirationMillis(int replyExpirationMillis) {
        this.replyExpirationMillis = replyExpirationMillis;
    }

    // serialized calls
    boolean addRequest(byte[] payload) {
        boolean cancel = false;

        synchronized (requests) {
            if (requests.size() == maxRequests) {
                switch (requestExpirationMillis) {
                    case Integer.MAX_VALUE:
                        return false;
                    case -1:
                        cancel = true;
                        break;
                    case 0:
                        // edge condition where the queue maxRequests is set to 0 and is rejecting every incoming request
                        if (requests.isEmpty()) {
                            return false;
                        }

                        requests.remove(requests.iterator().next());
                }
            }
        }

        switch (requestServiceType) {
            case Sync:
                reply(((SyncReplyService<byte[], ByteBuffer>) serviceProvider).serviceRequest(payload));
                return true;
            case AsyncSingle:
                RequestWithAsyncReply<byte[], ByteBuffer> requestWithAsyncReply =
                        new RequestWithAsyncReply<byte[], ByteBuffer>(
                                cancel ? null : payload, System.currentTimeMillis() + requestExpirationMillis) {

                            @Override
                            public boolean setReply(ByteBuffer reply) {
                                synchronized (requests) {
                                    if (requests.remove(this)) {
                                        reply(reply);
                                        return true;
                                    }

                                    return false;
                                }
                            }

                            @Override
                            public void cancel(int code) {
                                super.cancel(code);
                                synchronized (requests) {
                                    requests.remove(this);
                                }
                            }
                        };

                if (cancel) {
                    requestWithAsyncReply.cancel(1);
                } else {
                    synchronized (requests) {
                        requests.add(requestWithAsyncReply);
                    }
                }

                ((AsyncReplyService<byte[], ByteBuffer>) serviceProvider).serviceRequest(requestWithAsyncReply);
                break;
            case AsyncMultiple:
                RequestWithAsyncReplies<byte[], ByteBuffer> requestWithAsyncReplies =
                        new RequestWithAsyncReplies<byte[], ByteBuffer>(
                                cancel ? null : payload, System.currentTimeMillis() + requestExpirationMillis) {

                            @Override
                            public boolean addReply(ByteBuffer reply) {
                                synchronized (requests) {
                                    if (requests.contains(this)) {
                                        if (reply != null) {
                                            reply(reply);
                                        } else {
                                            requests.remove(this);
                                        }
                                        return true;
                                    }
                                    return false;
                                }
                            }

                            @Override
                            public void cancel(int code) {
                                super.cancel(code);
                                synchronized (requests) {
                                    requests.remove(this);
                                }
                            }
                        };

                if (cancel) {
                    requestWithAsyncReplies.cancel(1);
                } else {
                    synchronized (requests) {
                        requests.add(requestWithAsyncReplies);
                    }
                }

                ((AsyncRepliesService<byte[], ByteBuffer>) serviceProvider).serviceRequest(requestWithAsyncReplies);
                break;
        }

        return true;
    }

    protected void reply(ByteBuffer reply) {
        synchronized (replies) {
            replies.add(new Reply<ByteBuffer>(reply, System.currentTimeMillis() + replyExpirationMillis));

            if (replies.size() == 1) {
                rouplexTcpServer.addPendingWriteRegistration(selectionKey);
            }
        }
    }

    Reply<ByteBuffer> pollFirstReply() {
        synchronized (replies) {
            return replies.isEmpty() ? null : replies.iterator().next();
        }
    }

    void removeReply(Reply<ByteBuffer> reply) {
        synchronized (replies) {
            replies.remove(reply);
        }
    }
}
