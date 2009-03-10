package edu.washington.cs.cse490h.donut.service;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.thrift.TException;

import com.google.inject.Inject;

import edu.washington.cs.cse490h.donut.business.Node;
import edu.washington.cs.cse490h.donut.business.Pair;
import edu.washington.cs.cse490h.donut.service.application.DonutHashTableService;
import edu.washington.cs.cse490h.donut.util.KeyIdUtil;
import edu.washington.edu.cs.cse490h.donut.service.Constants;
import edu.washington.edu.cs.cse490h.donut.service.DataNotFoundException;
import edu.washington.edu.cs.cse490h.donut.service.EntryKey;
import edu.washington.edu.cs.cse490h.donut.service.KeyId;
import edu.washington.edu.cs.cse490h.donut.service.NodeNotFoundException;
import edu.washington.edu.cs.cse490h.donut.service.NotResponsibleForId;
import edu.washington.edu.cs.cse490h.donut.service.TNode;
import edu.washington.edu.cs.cse490h.donut.service.KeyLocator.Iface;

/**
 * @author alevy
 */
public class NodeLocator implements Iface {

    private static Logger               LOGGER;
    private final Node                  node;
    private final LocatorClientFactory  clientFactory;
    private final DonutHashTableService service;

    static {
        LOGGER = Logger.getLogger(NodeLocator.class.getName());
        LOGGER.setLevel(Level.WARNING);
    }

    @Inject
    public NodeLocator(Node node, DonutHashTableService service, LocatorClientFactory clientFactory) {
        this.node = node;
        this.service = service;
        this.clientFactory = clientFactory;
    }

    public TNode findSuccessor(KeyId entryId) throws TException {
        LOGGER.info("Request for entity [" + printNode(this.node.getTNode()) + "]: Id - \""
                + entryId.toString() + "\"");
        TNode next = node.closestPrecedingNode(entryId);
        if (next.equals(node.getTNode())) {
            LOGGER.info("I am predecessor [" + printNode(this.node.getTNode()) + "]: Id \""
                    + entryId.toString() + "\"");
            return node.getSuccessor();
        }
        try {
            LOGGER.info("I am NOT the predecessor [" + printNode(this.node.getTNode()) + "]: Id \""
                    + entryId.toString() + "\" \n" + "Connecting to " + next.getPort());
            TNode successor = clientFactory.get(next).findSuccessor(entryId);
            clientFactory.release(next);
            return successor;
        } catch (RetryFailedException e) {
            throw new TException(e);
        }
    }

    public byte[] get(EntryKey key) throws TException, DataNotFoundException {
        LOGGER.info("Get entity with id \"" + key.toString() + "\".");
        Pair<byte[], Integer> data = service.get(key);
        if (data == null) {
            throw new DataNotFoundException();
        }
        return data.head();
    }

    public void put(EntryKey key, byte[] data) throws TException, NotResponsibleForId {
        if (!KeyIdUtil.isAfterXButBeforeEqualY(key.getId(), node.getPredecessor().getNodeId(), node
                .getNodeId())) {
            LOGGER.info("Not responsible for entity with id \"" + key.toString() + "\".");
            throw new NotResponsibleForId(key.getId());
        }
        LOGGER.info("Put \"" + data + "\" into entity with id \"" + key.toString() + "\".");
        service.put(key, data, Constants.SUCCESSOR_LIST_SIZE);
        TNode successor = node.getSuccessor();
        try {
            clientFactory.get(successor).replicatePut(key, data, Constants.SUCCESSOR_LIST_SIZE - 1);
            clientFactory.release(successor);
        } catch (RetryFailedException e) {
            throw new TException(e);
        }
    }

    public void remove(EntryKey key) throws TException, NotResponsibleForId {
        if (!KeyIdUtil.isAfterXButBeforeEqualY(key.getId(), node.getPredecessor().getNodeId(), node
                .getNodeId())) {
            LOGGER.info("Not responsible for entity with id \"" + key.toString() + "\".");
            throw new NotResponsibleForId(key.getId());
        }
        LOGGER.info("Remove entity with id \"" + key.toString() + "\".");
        service.remove(key);
        TNode successor = node.getSuccessor();
        try {
            clientFactory.get(successor).replicateRemove(key, Constants.SUCCESSOR_LIST_SIZE - 1);
            clientFactory.release(successor);
        } catch (RetryFailedException e) {
            throw new TException(e);
        }
    }

    public void replicatePut(EntryKey key, byte[] data, int numReplicas) throws TException {
        LOGGER.info("Put \"" + data + "\" into entity with id \"" + key.toString() + "\".");
        service.put(key, data, numReplicas);
        if (numReplicas > 0) {
            TNode successor = node.getSuccessor();
            try {
                clientFactory.get(successor).replicatePut(key, data, numReplicas - 1);
                clientFactory.release(successor);
            } catch (RetryFailedException e) {
                throw new TException(e);
            }
        }
    }

    public void replicateRemove(EntryKey key, int numReplicas) throws TException {
        LOGGER.info("Remove entity with id \"" + key.toString() + "\".");
        service.remove(key);
        if (numReplicas > 0) {
            TNode successor = node.getSuccessor();
            try {
                clientFactory.get(successor).replicateRemove(key, numReplicas - 1);
                clientFactory.release(successor);
            } catch (RetryFailedException e) {
                throw new TException(e);
            }
        }
    }

    public DonutHashTableService getService() {
        return service;
    }

    /*
     * Should do nothing if connection completes. If the connection fails, then a TException is
     * thrown.
     */
    public void ping() throws TException {

    }

    public TNode getPredecessor() throws TException, NodeNotFoundException {
        TNode predecessor = node.getPredecessor();
        if (predecessor == null) {
            throw new NodeNotFoundException();
        }
        return predecessor;
    }

    public List<TNode> notify(TNode n) throws TException {
        if (node.getPredecessor() == null
                || KeyIdUtil.isAfterXButBeforeEqualY(n.getNodeId(), node.getPredecessor()
                        .getNodeId(), node.getNodeId())) {
            node.setPredecessor(n);
        }
        return node.getSuccessorList();
    }

    public List<TNode> getFingers() throws TException {
        return node.getFingers();
    }

    public String printNode(TNode n) {
        if (n == null)
            return "NULL";
        else
            return "" + n.getName();
    }

}
