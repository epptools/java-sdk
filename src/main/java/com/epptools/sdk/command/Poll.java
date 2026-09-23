package com.epptools.sdk.command;

import com.epptools.sdk.Client;
import com.epptools.sdk.Frame;
import com.epptools.sdk.Response;
import com.epptools.sdk.ResultCode;
import com.epptools.sdk.exception.CommandException;

import java.util.function.Consumer;
import org.w3c.dom.Element;

/** The service message queue, reached as {@code client.poll()}. */
public final class Poll {
    private final Client client;

    public Poll(Client client) {
        this.client = client;
    }

    /** Request the next service message (1301 with a message, 1300 when the queue is empty). */
    public Response request() {
        Frame frame = client.frame();
        frame.verb("poll").setAttribute("op", "req");
        return client.request(frame);
    }

    /** Acknowledge a message, which DELETES it at the registry. There is no way to get it back. */
    public Response ack(String messageId) {
        Frame frame = client.frame();
        Element poll = frame.verb("poll");
        poll.setAttribute("op", "ack");
        poll.setAttribute("msgID", messageId);
        return client.request(frame);
    }

    /**
     * Read the queue to the end, handing each notice to your callback.
     *
     * The ORDER is the point: the message is acknowledged only AFTER your callback returns. An ack deletes the
     * notice at the registry permanently, so a loop that acks first and processes second loses every notice
     * whose processing fails - a transfer request, a delete notification, the outcome of a pending create -
     * with nothing left to retry from and no record that anything was lost.
     *
     * So if your callback throws, the notice is NOT acked: it stays at the head of the queue and the exception
     * reaches you. Fix the cause and drain again; nothing was lost. That also means a callback which always
     * throws sees the same notice every time, deliberately, because the alternative is discarding it.
     *
     * Delivery is at least once. If the acknowledgement itself fails - the connection drops between your
     * callback returning and the ack landing - the notice is still queued and the next drain hands it to you
     * again. Make the callback idempotent and use {@link Response#messageId()} as the de-duplication key.
     *
     * Returns the number of notices processed successfully and stops at the first empty queue (1300). Any other
     * reply carrying no notice is raised rather than read as "empty". A {@code limit} of 0 means "until the
     * queue is empty"; a queue that fills faster than you drain it would otherwise run forever.
     */
    public int drain(Consumer<Response> handler, int limit) {
        int processed = 0;
        while (limit == 0 || processed < limit) {
            Response notice = request();
            // Only 1300 means the queue is empty. Inferring emptiness from "no msgQ" would make a refusal - the
            // session closed, the account suspended - look exactly like a drained queue, and the loop would
            // report success while nothing had been read.
            if (notice.code() == ResultCode.SUCCESS_NO_MESSAGES) {
                break;
            }
            String messageId = notice.messageId();
            if (messageId == null) {
                throw CommandException.forCode(notice.code(),
                        "poll returned neither a message nor an empty queue (EPP " + notice.code() + ": "
                                + (notice.message() == null ? "no message" : notice.message()) + ")",
                        notice);
            }
            handler.accept(notice);
            ack(messageId);
            processed++;
        }
        return processed;
    }

    public int drain(Consumer<Response> handler) {
        return drain(handler, 0);
    }
}
