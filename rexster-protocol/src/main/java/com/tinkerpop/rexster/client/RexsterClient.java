package com.tinkerpop.rexster.client;

import com.tinkerpop.pipes.util.iterators.SingleIterator;
import com.tinkerpop.rexster.protocol.BitWorks;
import com.tinkerpop.rexster.protocol.msg.ErrorResponseMessage;
import com.tinkerpop.rexster.protocol.msg.MsgPackScriptResponseMessage;
import com.tinkerpop.rexster.protocol.msg.RexProMessage;
import com.tinkerpop.rexster.protocol.msg.ScriptRequestMessage;
import org.apache.commons.collections.iterators.ArrayIterator;
import org.apache.commons.configuration.Configuration;
import org.apache.log4j.Logger;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.msgpack.MessagePack;
import org.msgpack.MessageTypeException;
import org.msgpack.template.Template;
import org.msgpack.type.Value;
import org.msgpack.unpacker.BufferUnpacker;
import org.msgpack.unpacker.Converter;
import org.msgpack.unpacker.UnpackerIterator;

import javax.script.Bindings;
import javax.script.SimpleBindings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.msgpack.template.Templates.TString;
import static org.msgpack.template.Templates.TValue;
import static org.msgpack.template.Templates.tMap;

/**
 * Basic client for sending Gremlin scripts to Rexster and receiving results as Map objects with String
 * keys and MsgPack Value objects. This client is only for sessionless communication with Rexster and
 * therefore all Gremlin scripts sent as requests to Rexster should be careful to handle their own
 * transactional semantics.  In other words, do not count on sending a script that mutates some aspect of the
 * graph in one request and then a second request later to commit the transaction as there is no guarantee that
 * the transaction will be handled properly.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class RexsterClient {
    private static final Logger logger = Logger.getLogger(RexsterClient.class);

    private final NIOConnection[] connections;
    private int currentConnection = 0;

    private final MessagePack msgpack = new MessagePack();
    private final int timeoutConnection;
    private final int timeoutWrite;
    private final int timeoutRead;
    private final int retries;
    private final int waitBetweenRetries;
    private final int asyncWriteQueueMaxBytes;
    private final int arraySizeLimit;
    private final int mapSizeLimit;
    private final int rawSizeLimit;
    private final String language;

    private final TCPNIOTransport transport;
    private final String[] hosts;
    private final int port;

    protected static ConcurrentHashMap<UUID, ArrayBlockingQueue<Object>> responses = new ConcurrentHashMap<UUID, ArrayBlockingQueue<Object>>();

    protected RexsterClient(final Configuration configuration, final TCPNIOTransport transport) {
        this.timeoutConnection = configuration.getInt(RexsterClientTokens.CONFIG_TIMEOUT_CONNECTION_MS);
        this.timeoutRead = configuration.getInt(RexsterClientTokens.CONFIG_TIMEOUT_READ_MS);
        this.timeoutWrite = configuration.getInt(RexsterClientTokens.CONFIG_TIMEOUT_WRITE_MS);
        this.retries = configuration.getInt(RexsterClientTokens.CONFIG_MESSAGE_RETRY_COUNT);
        this.waitBetweenRetries = configuration.getInt(RexsterClientTokens.CONFIG_MESSAGE_RETRY_WAIT_MS);
        this.asyncWriteQueueMaxBytes = configuration.getInt(RexsterClientTokens.CONFIG_MAX_ASYNC_WRITE_QUEUE_BYTES);
        this.arraySizeLimit = configuration.getInt(RexsterClientTokens.CONFIG_DESERIALIZE_ARRAY_SIZE_LIMIT);
        this.mapSizeLimit = configuration.getInt(RexsterClientTokens.CONFIG_DESERIALIZE_MAP_SIZE_LIMIT);
        this.rawSizeLimit = configuration.getInt(RexsterClientTokens.CONFIG_DESERIALIZE_RAW_SIZE_LIMIT);
        this.language = configuration.getString(RexsterClientTokens.CONFIG_LANGUAGE);

        this.transport = transport;
        this.port = configuration.getInt(RexsterClientTokens.CONFIG_PORT);
        final String hostname = configuration.getString(RexsterClientTokens.CONFIG_HOSTNAME);
        this.hosts = hostname.split(",");

        this.connections = new NIOConnection[this.hosts.length];
    }

    /**
     * Send a script to rexster that returns a list of Maps.
     */
    public List<Map<String, Object>> execute(final String script) throws RexProException, IOException {
        return execute(script, (Map<String, Object>) null);
    }

    /**
     * Send a script to rexster that returns a Map.
     *
     * Common usage is to retrieve graph elements (vertices and edges) which get serialized to Map by RexPro.
     * Data conversion during deserialization takes any whole numeric numbers and treats them as longs.  Floats
     * are treated as doubles.
     */
    public List<Map<String, Object>> execute(final String script, final Map<String, Object> parameters) throws RexProException, IOException {
        final List<Map<String,Value>> packResults = execute(script, parameters, null);
        final List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        try {
            for (Map<String,Value> map : packResults) {
                //Convert map
                final Map<String, Object> result = new HashMap<String, Object>();
                for (Map.Entry<String, Value> entry : map.entrySet()) {
                    result.put(entry.getKey(), entry.getValue());
                }
                results.add(result);
            }
        } catch (MessageTypeException e) {
            throw new IllegalArgumentException("Could not convert query result", e);
        }

        return results;
    }

    /**
     * Sends a RexProMessage, and returns the received RexProMessage response.
     */
    public RexProMessage execute(final RexProMessage rawMessage) throws RexProException, IOException {
        final ArrayBlockingQueue<Object> responseQueue = new ArrayBlockingQueue<Object>(1);
        final UUID requestId = rawMessage.requestAsUUID();
        responses.put(requestId, responseQueue);
        try {
            this.sendRequest(rawMessage);
        } catch (Throwable t) {
            throw new IOException(t);
        }

        Object resultMessage;
        try {
            final long beginTime = System.currentTimeMillis();
            resultMessage = responseQueue.poll(this.timeoutRead - (System.currentTimeMillis() - beginTime), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            responses.remove(requestId);
            throw new IOException(ex);
        }

        responses.remove(requestId);

        if (resultMessage == null) {
            throw new IOException(String.format("Message received response timeoutConnection (%s s)", this.timeoutConnection));
        } else if (!(resultMessage instanceof RexProMessage)) {
            logger.error(String.format("Rexster returned a message of type [%s]", resultMessage.getClass().getName()));
            throw new RexProException("RexsterClient doesn't support the message type returned.");
        }

        return (RexProMessage) resultMessage;
    }

    public <T> List<T> execute(final String script, final Template template) throws RexProException, IOException {
        return execute(script, null, template);
    }

    public <T> List<T> execute(final String script, final Map<String, Object> scriptArgs,
                               final Template template) throws RexProException, IOException {
        final ArrayBlockingQueue<Object> responseQueue = new ArrayBlockingQueue<Object>(1);
        final RexProMessage msgToSend = createNoSessionScriptRequest(script, scriptArgs);
        final UUID requestId = msgToSend.requestAsUUID();
        responses.put(requestId, responseQueue);

        try {
            this.sendRequest(msgToSend);
        } catch (Throwable t) {
            throw new IOException(t);
        }

        Object resultMessage;
        try {
            final long beginTime = System.currentTimeMillis();
            resultMessage = responseQueue.poll(this.timeoutRead - (System.currentTimeMillis() - beginTime), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            responses.remove(requestId);
            throw new IOException(ex);
        }

        responses.remove(requestId);

        if (resultMessage == null) {
            throw new IOException(String.format("Message received response timeoutConnection (%s s)", this.timeoutConnection));
        }

        if (resultMessage instanceof MsgPackScriptResponseMessage) {
            final MsgPackScriptResponseMessage msg = (MsgPackScriptResponseMessage) resultMessage;

            // when rexster returns an iterable it's read out of the unpacker as a single object much like a single
            // vertex coming back from rexster.  basically, this is the difference between g.v(1) and g.v(1).map.
            // the latter returns an iterable essentially putting a list inside of the results list here on the
            // client side. the idea here is to normalize all results to a list on the client side, and therefore,
            // iterables like those from g.v(1).map need to be unrolled into the results list.  Prefer this to
            // doing it on the server, because the server should return what is asked of it, in case other clients
            // want to process this differently.
            final List<T> results = new ArrayList<T>();
            if (msg.Results instanceof Iterable) {
                final Iterator<T> itty = ((Iterable) msg.Results).iterator();
                while(itty.hasNext()) {
                    results.add(itty.next());
                }
            } else {
                results.add((T)msg.Results);
            }

            return results;

        } else if (resultMessage instanceof ErrorResponseMessage) {
            logger.warn(String.format("Rexster returned an error response for [%s] with params [%s]",
                    script, scriptArgs));
            throw new RexProException(((ErrorResponseMessage) resultMessage).ErrorMessage);
        } else {
            logger.error(String.format("Rexster returned a message of type [%s]", resultMessage.getClass().getName()));
            throw new RexProException("RexsterClient doesn't support the message type returned.");
        }
    }

    void putResponse(final RexProMessage response) throws Exception {
        final UUID requestId = response.requestAsUUID();
        if (!responses.containsKey(requestId)) {
            // probably a timeout if we get here... ???
            return;
        }

        try {
            final ArrayBlockingQueue<Object> queue = responses.get(requestId);
            if (queue != null) {
                queue.put(response);
            }
            else {
                // no queue for some reason....why ???
            }
        }
        catch (InterruptedException e) {
            // just trap this one ???
        }
    }

    private NIOConnection nextConnection() {
        synchronized(connections) {
            if (currentConnection == Integer.MAX_VALUE) { currentConnection = 0; }
            currentConnection = (currentConnection + 1) % hosts.length;

            final NIOConnection connection = connections[currentConnection];
            if (connection == null || !connection.isOpen()) {
                connections[currentConnection] = openConnection(this.hosts[currentConnection]);
            }

            return connections[currentConnection];
        }
    }

    private NIOConnection openConnection(final String host) {
        try {
            final Future<Connection> future = this.transport.connect(host, port);
            final NIOConnection connection = (NIOConnection) future.get(this.timeoutConnection, TimeUnit.MILLISECONDS);
            connection.setMaxAsyncWriteQueueSize(asyncWriteQueueMaxBytes);
            return connection;
        } catch (Exception e) {
            return null;
        }
    }

    private void sendRequest(final RexProMessage toSend) throws Exception {
        boolean sent = false;
        int tries = this.retries;
        while (tries > 0 && !sent) {
            try {
                final NIOConnection connection = nextConnection();
                if (connection != null && connection.isOpen()) {
                    if (toSend.estimateMessageSize() + connection.getAsyncWriteQueue().spaceInBytes() <= connection.getMaxAsyncWriteQueueSize()) {
                        final GrizzlyFuture future = connection.write(toSend);
                        future.get(this.timeoutWrite, TimeUnit.MILLISECONDS);
                        sent = true;
                    } else {
                        throw new Exception("internal");
                    }
                }
            } catch (Exception ex) {
                tries--;
                final UUID requestId = toSend.requestAsUUID();
                if (tries == 0) {
                    responses.remove(requestId);
                } else {
                    Thread.sleep(this.waitBetweenRetries);
                }
            }
        }

        if (!sent) {
            throw new Exception("Could not send message.");
        }

    }

    public void close() throws IOException {
        for (NIOConnection connection : this.connections) {
            connection.close();
        }

        this.transport.stop();
    }

    private ScriptRequestMessage createNoSessionScriptRequest(final String script,
                                                              final Map<String, Object> scriptArguments) throws IOException, RexProException {
        final ScriptRequestMessage scriptMessage = new ScriptRequestMessage();
        scriptMessage.Script = script;
        scriptMessage.LanguageName = this.language;
        scriptMessage.metaSetInSession(false);
        scriptMessage.setRequestAsUUID(UUID.randomUUID());

        scriptMessage.validateMetaData();

        //attach bindings
        if (scriptArguments != null) {
            scriptMessage.Bindings.putAll(scriptArguments);
        }

        return scriptMessage;
    }

    private static Object convert(final Value v) {
        if (v.isNilValue()) return null;
        else if (v.isBooleanValue()) return v.asBooleanValue().getBoolean();
        else if (v.isIntegerValue()) return v.asIntegerValue().getLong();
        else if (v.isFloatValue()) return v.asFloatValue().getDouble();
        else if (v.isRawValue()) return v.asRawValue().getString();
        else if (v.isArrayValue()) {
            final Value[] arr = v.asArrayValue().getElementArray();
            final Object[] result = new Object[arr.length];
            for (int i = 0; i < result.length; i++) result[i] = convert(arr[i]);
            return result;
        } else if (v.isMapValue()) {
            final Map<Object, Object> result = new HashMap<Object, Object>();
            for (Map.Entry<Value, Value> entry : v.asMapValue().entrySet()) {
                result.put(convert(entry.getKey()), convert(entry.getValue()));
            }
            return result;
        } else {
            logger.trace(String.format("Cannot convert value:  {%s} [{%s}]", v, v.getType()));
            return v.toString();
        }
    }

}
