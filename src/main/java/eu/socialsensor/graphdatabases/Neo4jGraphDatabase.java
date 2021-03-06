package eu.socialsensor.graphdatabases;


import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import eu.socialsensor.insert.Insertion;
import eu.socialsensor.insert.Neo4jMassiveInsertion;
import eu.socialsensor.insert.Neo4jSingleInsertion;
import eu.socialsensor.main.BenchmarkingException;
import eu.socialsensor.main.GraphDatabaseType;
import eu.socialsensor.utils.Utils;
import org.neo4j.graphalgo.GraphAlgoFactory;
import org.neo4j.graphalgo.PathFinder;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.kernel.impl.traversal.MonoDirectionalTraversalDescription;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserters;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * Neo4j graph database implementation
 *
 * @author sotbeis, sotbeis@iti.gr
 * @author Alexander Patrikalakis
 */
public class Neo4jGraphDatabase extends GraphDatabaseBase<Iterator<Node>, Iterator<Relationship>, Node, Relationship> {

    protected GraphDatabaseService neo4jGraph = null;
    private Schema schema = null;

    private BatchInserter inserter = null;


    public enum RelTypes implements RelationshipType {
        SIMILAR
    }


    public static Label NODE_LABEL = DynamicLabel.label("Node");


    public Neo4jGraphDatabase(File dbStorageDirectoryIn) {
        super(GraphDatabaseType.NEO4J, dbStorageDirectoryIn);
    }


    @Override
    public void open() {
        neo4jGraph = new GraphDatabaseFactory().newEmbeddedDatabase(dbStorageDirectory);
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                neo4jGraph.schema().awaitIndexesOnline(10L, TimeUnit.MINUTES);
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unknown error", e);
            }
        }
    }


    @Override
    public void createGraphForSingleLoad() {
        neo4jGraph = new GraphDatabaseFactory().newEmbeddedDatabase(dbStorageDirectory);
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                schema = neo4jGraph.schema();
                schema.indexFor(NODE_LABEL).on(NODE_ID).create();
                schema.indexFor(NODE_LABEL).on(COMMUNITY).create();
                schema.indexFor(NODE_LABEL).on(NODE_COMMUNITY).create();
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unknown error", e);
            }
        }
    }


    @Override
    public void createGraphForMassiveLoad() {
        Map<String, String> config = new HashMap<>();
        config.put("cache_type", "none");
        config.put("use_memory_mapped_buffers", "true");
        config.put("neostore.nodestore.db.mapped_memory", "200M");
        config.put("neostore.relationshipstore.db.mapped_memory", "1000M");
        config.put("neostore.propertystore.db.mapped_memory", "250M");
        config.put("neostore.propertystore.db.strings.mapped_memory", "250M");

        try {
            inserter = BatchInserters.inserter(dbStorageDirectory, config);
            createDeferredSchema();

        } catch (IOException e) {
            throw new BenchmarkingException("Bad database storage directory, check the path", e);
        }
    }


    private void createDeferredSchema() {
        inserter.createDeferredSchemaIndex(NODE_LABEL).on(NODE_ID).create();
        inserter.createDeferredSchemaIndex(NODE_LABEL).on(COMMUNITY).create();
        inserter.createDeferredSchemaIndex(NODE_LABEL).on(NODE_COMMUNITY).create();
    }


    @Override
    public void singleModeLoading(File dataPath, File resultsPath, int scenarioNumber) {
        Insertion neo4jSingleInsertion = new Neo4jSingleInsertion(this.neo4jGraph, resultsPath);
        neo4jSingleInsertion.createGraph(dataPath, scenarioNumber);
    }


    @Override
    public void massiveModeLoading(File dataPath) {
        Insertion neo4jMassiveInsertion = new Neo4jMassiveInsertion(this.inserter);
        neo4jMassiveInsertion.createGraph(dataPath, 0 /* scenarioNumber */);
    }


    @Override
    public void shutdown() {
        if (neo4jGraph == null) {
            return;
        }
        neo4jGraph.shutdown();
    }


    @Override
    public void delete() {
        Utils.deleteRecursively(dbStorageDirectory);
    }


    @Override
    public void shutdownMassiveGraph() {
        if (inserter == null) {
            return;
        }
        inserter.shutdown();

        File store_lock = new File("graphDBs/Neo4j", "store_lock");
        store_lock.delete();
        if (store_lock.exists()) {
            throw new BenchmarkingException("could not remove store_lock");
        }

        File lock = new File("graphDBs/Neo4j", "lock");
        lock.delete();
        if (lock.exists()) {
            throw new BenchmarkingException("could not remove lock");
        }

        inserter = null;
    }


    @Override
    public void shortestPath(Node n1, Integer i) {
        TraversalDescription td = new MonoDirectionalTraversalDescription();

        PathFinder<Path> finder = GraphAlgoFactory.shortestPath(PathExpanders.forType(RelTypes.SIMILAR), 5);
        Node n2 = getVertex(i);
        Path path = finder.findSinglePath(n1, n2);

    }


    //TODO can unforced option be pulled into configuration?
    private Transaction beginUnforcedTransaction() {
        return neo4jGraph.beginTx();
    }


    @Override
    public int getNodeCount() {
        int nodeCount;
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {

                nodeCount = (int) org.neo4j.helpers.collection.Iterables.count(neo4jGraph.getAllNodes());
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get node count", e);
            }
        }

        return nodeCount;
    }


    @Override
    public Set<Integer> getNeighborsIds(int nodeId) {
        Set<Integer> neighbors = new HashSet<>();
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                Node n = neo4jGraph.findNode(NODE_LABEL, NODE_ID, String.valueOf(nodeId));
                for (Relationship relationship : n.getRelationships(RelTypes.SIMILAR, Direction.OUTGOING)) {
                    Node neighbour = relationship.getOtherNode(n);
                    String neighbourId = (String) neighbour.getProperty(NODE_ID);
                    neighbors.add(Integer.valueOf(neighbourId));
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get neighbors ids", e);
            }
        }

        return neighbors;
    }


    @Override
    public double getNodeWeight(int nodeId) {
        double weight;
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                Node n = neo4jGraph.findNode(NODE_LABEL, NODE_ID, String.valueOf(nodeId));
                weight = getNodeOutDegree(n);
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get node weight", e);
            }
        }

        return weight;
    }


    public double getNodeInDegree(Node node) {
        Iterable<Relationship> rel = node.getRelationships(Direction.OUTGOING, RelTypes.SIMILAR);
        return org.neo4j.helpers.collection.Iterables.count(rel);
    }


    public double getNodeOutDegree(Node node) {
        Iterable<Relationship> rel = node.getRelationships(Direction.INCOMING, RelTypes.SIMILAR);
        return org.neo4j.helpers.collection.Iterables.count(rel);
    }


    @Override
    public void initCommunityProperty() {
        int communityCounter = 0;

        // maybe commit changes every 1000 transactions?
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                for (Node n : neo4jGraph.getAllNodes()) {
                    n.setProperty(NODE_COMMUNITY, communityCounter);
                    n.setProperty(COMMUNITY, communityCounter);
                    communityCounter++;
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to initialize community property", e);
            }
        }
    }


    @Override
    public Set<Integer> getCommunitiesConnectedToNodeCommunities(int nodeCommunities) {
        Set<Integer> communities = new HashSet<>();
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                ResourceIterator<Node> nodes = neo4jGraph.findNodes(Neo4jGraphDatabase.NODE_LABEL, NODE_COMMUNITY, nodeCommunities);
                while (nodes.hasNext()) {
                    Node n = nodes.next();
                    for (Relationship r : n.getRelationships(RelTypes.SIMILAR, Direction.OUTGOING)) {
                        Node neighbour = r.getOtherNode(n);
                        Integer community = (Integer) (neighbour.getProperty(COMMUNITY));
                        communities.add(community);
                    }
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get communities connected to node communities", e);
            }
        }

        return communities;
    }


    @Override
    public Set<Integer> getNodesFromCommunity(int community) {
        Set<Integer> nodes = new HashSet<>();
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                ResourceIterator<Node> nodesIter = neo4jGraph.findNodes(NODE_LABEL, COMMUNITY, community);
                while (nodesIter.hasNext()) {
                    Node n = nodesIter.next();
                    String nodeIdString = (String) (n.getProperty(NODE_ID));
                    nodes.add(Integer.valueOf(nodeIdString));
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get nodes from community", e);
            }
        }
        return nodes;
    }


    @Override
    public Set<Integer> getNodesFromNodeCommunity(int nodeCommunity) {
        Set<Integer> nodes = new HashSet<>();

        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                ResourceIterator<Node> nodesIter = neo4jGraph.findNodes(NODE_LABEL, NODE_COMMUNITY, nodeCommunity);
                while (nodesIter.hasNext()) {
                    Node n = nodesIter.next();
                    String nodeIdString = (String) (n.getProperty(NODE_ID));
                    nodes.add(Integer.valueOf(nodeIdString));
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get nodes from node community", e);
            }
        }

        return nodes;
    }


    @Override
    public double getEdgesInsideCommunity(int nodeCommunity, int communityNodes) {
        double edges = 0;
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                ResourceIterator<Node> nodes = neo4jGraph.findNodes(NODE_LABEL, NODE_COMMUNITY, nodeCommunity);

                ResourceIterator<Node> comNodes = neo4jGraph.findNodes(NODE_LABEL, COMMUNITY, communityNodes);
                while (nodes.hasNext()) {
                    Node node = nodes.next();
                    Iterable<Relationship> relationships = node.getRelationships(RelTypes.SIMILAR, Direction.OUTGOING);
                    for (Relationship r : relationships) {
                        Node neighbor = r.getOtherNode(node);
                        if (Iterables.contains(comNodes.stream().collect(Collectors.toList()), neighbor)) {
                            edges++;
                        }
                    }
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get edges inside community", e);
            }
        }

        return edges;
    }


    @Override
    public double getCommunityWeight(int community) {
        double communityWeight = 0;
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                ResourceIterator<Node> iter = neo4jGraph.findNodes(NODE_LABEL, COMMUNITY, community);

                if (Iterators.size(iter) > 1) {
                    while (iter.hasNext()) {
                        Node n = iter.next();
                        communityWeight += getNodeOutDegree(n);
                    }
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get community weight", e);
            }
        }

        return communityWeight;
    }


    @Override
    public double getNodeCommunityWeight(int nodeCommunity) {
        double nodeCommunityWeight = 0;
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                ResourceIterator<Node> iter = neo4jGraph.findNodes(NODE_LABEL, NODE_COMMUNITY, nodeCommunity);

                if (Iterators.size(iter) > 1) {
                    while (iter.hasNext()) {
                        Node n = iter.next();
                        nodeCommunityWeight += getNodeOutDegree(n);
                    }
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get node community weight", e);
            }
        }

        return nodeCommunityWeight;
    }


    @Override
    public void moveNode(int nodeCommunity, int toCommunity) {
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                ResourceIterator<Node> fromIter = neo4jGraph.findNodes(NODE_LABEL, NODE_COMMUNITY, nodeCommunity);
                while (fromIter.hasNext()) {
                    Node node = fromIter.next();
                    node.setProperty(COMMUNITY, toCommunity);
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to move node", e);
            }
        }
    }


    @Override
    public double getGraphWeightSum() {
        int edgeCount;

        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                edgeCount = (int) org.neo4j.helpers.collection.Iterables.count(neo4jGraph.getAllRelationships());
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get graph weight sum", e);
            }
        }

        return (double) edgeCount;
    }


    @Override
    public int reInitializeCommunities() {
        Map<Integer, Integer> initCommunities = new HashMap<>();
        int communityCounter = 0;

        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                for (Node n : neo4jGraph.getAllNodes()) {
                    Integer communityId = (Integer) (n.getProperty(COMMUNITY));
                    if (!initCommunities.containsKey(communityId)) {
                        initCommunities.put(communityId, communityCounter);
                        communityCounter++;
                    }
                    int newCommunityId = initCommunities.get(communityId);
                    n.setProperty(COMMUNITY, newCommunityId);
                    n.setProperty(NODE_COMMUNITY, newCommunityId);
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to reinitialize communities", e);
            }
        }

        return communityCounter;
    }


    @Override
    public int getCommunity(int nodeCommunity) {
        Integer community;

        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                Node node = neo4jGraph.findNode(NODE_LABEL, NODE_COMMUNITY, nodeCommunity);
                community = (Integer) (node.getProperty(COMMUNITY));
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get community", e);
            }
        }

        return community;
    }


    @Override
    public int getCommunityFromNode(int nodeId) {
        Integer community;
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                Node node = neo4jGraph.findNode(NODE_LABEL, NODE_ID, String.valueOf(nodeId));
                community = (Integer) (node.getProperty(COMMUNITY));
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get community from node", e);
            }
        }

        return community;
    }


    @Override
    public int getCommunitySize(int community) {
        Set<Integer> nodeCommunities = new HashSet<>();

        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                ResourceIterator<Node> nodes = neo4jGraph.findNodes(NODE_LABEL, COMMUNITY, community);
                while (nodes.hasNext()) {
                    Node n = nodes.next();
                    Integer nodeCommunity = (Integer) (n.getProperty(COMMUNITY));
                    nodeCommunities.add(nodeCommunity);
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to get community size", e);
            }
        }

        return nodeCommunities.size();
    }


    @Override
    public Map<Integer, List<Integer>> mapCommunities(int numberOfCommunities) {
        Map<Integer, List<Integer>> communities = new HashMap<>();

        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                for (int i = 0; i < numberOfCommunities; i++) {
                    ResourceIterator<Node> nodesIter = neo4jGraph.findNodes(NODE_LABEL, COMMUNITY, i);
                    List<Integer> nodes = new ArrayList<>();
                    while (nodesIter.hasNext()) {
                        Node n = nodesIter.next();
                        String nodeIdString = (String) (n.getProperty(NODE_ID));
                        nodes.add(Integer.valueOf(nodeIdString));
                    }
                    communities.put(i, nodes);
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to map communities", e);
            }
        }

        return communities;
    }


    @Override
    public boolean nodeExists(int nodeId) {
        try (final Transaction tx = beginUnforcedTransaction()) {
            try {
                ResourceIterator<Node> nodesIter = neo4jGraph.findNodes(NODE_LABEL, NODE_ID, nodeId);
                if (nodesIter.hasNext()) {
                    tx.success();
                    return true;
                }
                tx.success();
            } catch (Exception e) {
                tx.failure();
                throw new BenchmarkingException("unable to determine if node exists", e);
            }
        }
        return false;
    }


    @Override
    public Iterator<Node> getVertexIterator() {
        return neo4jGraph.getAllNodes().iterator();
    }


    @Override
    public Iterator<Relationship> getNeighborsOfVertex(Node v) {
        return v.getRelationships(Neo4jGraphDatabase.RelTypes.SIMILAR, Direction.BOTH).iterator();
    }


    @Override
    public void cleanupVertexIterator(Iterator<Node> it) {
        // NOOP
    }


    @Override
    public Node getOtherVertexFromEdge(Relationship r, Node n) {
        return r.getOtherNode(n);
    }


    @Override
    public Iterator<Relationship> getAllEdges() {
        return neo4jGraph.getAllRelationships().iterator();
    }


    @Override
    public Node getSrcVertexFromEdge(Relationship edge) {
        return edge.getStartNode();
    }


    @Override
    public Node getDestVertexFromEdge(Relationship edge) {
        return edge.getEndNode();
    }


    @Override
    public boolean edgeIteratorHasNext(Iterator<Relationship> it) {
        return it.hasNext();
    }


    @Override
    public Relationship nextEdge(Iterator<Relationship> it) {
        return it.next();
    }


    @Override
    public void cleanupEdgeIterator(Iterator<Relationship> it) {
        //NOOP
    }


    @Override
    public boolean vertexIteratorHasNext(Iterator<Node> it) {
        return it.hasNext();
    }


    @Override
    public Node nextVertex(Iterator<Node> it) {
        return it.next();
    }


    @Override
    public Node getVertex(Integer i) {
        // note, this probably should be run in the context of an active transaction.
        return neo4jGraph.findNode(Neo4jGraphDatabase.NODE_LABEL, NODE_ID, i.toString());
    }

}
