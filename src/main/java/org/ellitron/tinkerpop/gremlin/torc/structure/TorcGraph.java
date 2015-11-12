/*
 * Copyright 2015 Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// TODO: Change license to match RAMCloud
package org.ellitron.tinkerpop.gremlin.torc.structure;

import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;

import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

import edu.stanford.ramcloud.*;
import edu.stanford.ramcloud.transactions.*;
import edu.stanford.ramcloud.ClientException.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;

import org.apache.commons.configuration.Configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.PropertyConfigurator;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.util.AbstractTransaction;
import org.ellitron.tinkerpop.gremlin.torc.structure.util.TorcHelper;

/**
 *
 * @author Jonathan Ellithorpe <jde@cs.stanford.edu>

 TODO: Make TorcGraph thread safe. Currently every TorcGraph method that 
 performs reads or writes to the database uses a ThreadLocal RAMCloudTransaction 
 to isolate transactions from different threads. Each ThreadLocal RAMCloudTransaction 
 object, however, is constructed with a reference to the same RAMCloud client 
 object, which itself is not thread safe. Therefore, TorcGraph, although 
 using separate RAMCloudTransaction objects for each thread, it not actually 
 thread safe. To fix this problem, each ThreadLocal RAMCloudTransaction object 
 needs to be constructed with its own RAMCloud client object.
 */
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
public final class TorcGraph implements Graph {
    private static final Logger LOGGER = LoggerFactory.getLogger(TorcGraph.class);
    
    static {
        BasicConfigurator.configure();
    }
    
    // Configuration keys.
    public static final String CONFIG_GRAPH_NAME = "gremlin.ramcloud.graphName";
    public static final String CONFIG_COORD_LOC = "gremlin.ramcloud.coordinatorLocator";
    public static final String CONFIG_NUM_MASTER_SERVERS = "gremlin.ramcloud.numMasterServers";
    public static final String CONFIG_LOG_LEVEL = "gremlin.ramcloud.logLevel";
    
    // Constants.
    private static final String ID_TABLE_NAME = "idTable";
    private static final String VERTEX_TABLE_NAME = "vertexTable";
    private static final String EDGE_TABLE_NAME = "edgeTable";
    private static final int MAX_TX_RETRY_COUNT = 100;
    private static final int NUM_ID_COUNTERS = 16;
    
    // Normal private members.
    private final Configuration configuration;
    private String coordinatorLocator;
    private int totalMasterServers;
    private RAMCloud ramcloud;
    private long idTableId, vertexTableId, edgeTableId;
    private String graphName;
    private long nextLocalVertexId = 1;
    private RAMCloudGraphTransaction ramcloudGraphTransaction = new RAMCloudGraphTransaction();
    
    boolean initialized = false;
    
    private TorcGraph(final Configuration configuration) {
        this.configuration = configuration;
        
        graphName = configuration.getString(CONFIG_GRAPH_NAME);
        coordinatorLocator = configuration.getString(CONFIG_COORD_LOC);
        totalMasterServers = configuration.getInt(CONFIG_NUM_MASTER_SERVERS);
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    private void initialize() {
        try {
            ramcloud = new RAMCloud(coordinatorLocator);
        } catch(ClientException e) {
            System.out.println(e.toString());
            throw e;
        }
        
        idTableId = ramcloud.createTable(graphName + "_" + ID_TABLE_NAME, totalMasterServers);
        vertexTableId = ramcloud.createTable(graphName + "_" + VERTEX_TABLE_NAME, totalMasterServers);
        edgeTableId = ramcloud.createTable(graphName + "_" + EDGE_TABLE_NAME, totalMasterServers);
        
        initialized = true;
    }
    
    public static TorcGraph open(final Configuration configuration) {
        return new TorcGraph(configuration);
    }
    
    @Override
    public Vertex addVertex(final Object... keyValues) {
        if (!initialized)
            initialize();
        
        ramcloudGraphTransaction.readWrite();
        RAMCloudTransaction tx = ramcloudGraphTransaction.threadLocalTx.get();
        
        // Validate key/value pairs
        if (keyValues.length % 2 != 0)
            throw Element.Exceptions.providedKeyValuesMustBeAMultipleOfTwo();
        for (int i = 0; i < keyValues.length; i = i + 2) {
            if (!(keyValues[i] instanceof String) && !(keyValues[i] instanceof T))
                throw Element.Exceptions.providedKeyValuesMustHaveALegalKeyOnEvenIndices();
            if (!(keyValues[i+1] instanceof String))
                if (!keyValues[i].equals(T.id))
                    throw Property.Exceptions.dataTypeOfPropertyValueNotSupported(keyValues[i+1]);
        }
        
        final String label = ElementHelper.getLabelValue(keyValues).orElse(Vertex.DEFAULT_LABEL);
        
        Optional opVertId = ElementHelper.getIdValue(keyValues);
        byte[] vertexId;
        if (opVertId.isPresent()) {
            if (opVertId.get() instanceof Long) {
                vertexId = TorcHelper.makeVertexId(0, (Long) opVertId.get());
            } else if (opVertId.get() instanceof String) {
                vertexId = TorcHelper.makeVertexId(0, Long.decode((String) opVertId.get()));
            } else if (opVertId.get() instanceof byte[]) {
                vertexId = (byte[]) opVertId.get();
                
                if (vertexId[0] < 0)
                    throw Vertex.Exceptions.userSuppliedIdsOfThisTypeNotSupported();
            } else {
                throw Vertex.Exceptions.userSuppliedIdsOfThisTypeNotSupported();
            }
            
            try {
                tx.read(vertexTableId, TorcHelper.getVertexLabelKey(vertexId));
                System.out.println("Vertex with id already exists: " + TorcHelper.stringifyVertexId(vertexId));
                //throw Graph.Exceptions.vertexWithIdAlreadyExists(TorcHelper.stringifyVertexId(vertexId));
            } catch (ObjectDoesntExistException e) {
                // Good!
            }
        } else {
            long id_counter = (long) (Math.random()*NUM_ID_COUNTERS);
            long id = ramcloud.incrementInt64(idTableId, Long.toString(id_counter).getBytes(), 1, null);
            
            vertexId = TorcHelper.makeVertexId(id_counter + (1<<63), id);
        }
        
        // Create property map.
        Map<String, String> properties = new HashMap<>();
        for (int i = 0; i < keyValues.length; i = i + 2) {
            if (keyValues[i] instanceof String)
                properties.put((String)keyValues[i], (String)keyValues[i+1]);
        }
        
        tx.write(vertexTableId, TorcHelper.getVertexLabelKey(vertexId), label);
        tx.write(vertexTableId, TorcHelper.getVertexPropertiesKey(vertexId), TorcHelper.serializeProperties(properties).array());
        
        return new TorcVertex(this, vertexId, label);
    }
    
    @Override
    public <C extends GraphComputer> C compute(final Class<C> type) throws IllegalArgumentException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public GraphComputer compute() throws IllegalArgumentException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Iterator<Vertex> vertices(final Object... vertexIds) {
        if (!initialized)
            initialize();
        
        ramcloudGraphTransaction.readWrite();
        RAMCloudTransaction tx = ramcloudGraphTransaction.threadLocalTx.get();
        
        ElementHelper.validateMixedElementIds(TorcVertex.class, vertexIds);
        
        List<Vertex> list = new ArrayList<>();
        if (vertexIds.length > 0) {
            if (vertexIds[0] instanceof TorcVertex) {
                return Arrays.asList((Vertex[]) vertexIds).iterator();
            }
            
            for (int i = 0; i < vertexIds.length; ++i) {
                byte[] vertexId;
                
                if (vertexIds[i] instanceof Long) {
                    vertexId = TorcHelper.makeVertexId(0, (Long) vertexIds[i]);
                } else if (vertexIds[i] instanceof BigInteger) {
                    long lower = ((BigInteger) vertexIds[i]).longValue();
                    long upper = ((BigInteger) vertexIds[i]).shiftRight(Long.SIZE).longValue();
                    vertexId = TorcHelper.makeVertexId(upper, lower);
                } else if (vertexIds[i] instanceof byte[]) {
                    if (TorcHelper.validateVertexId(vertexIds[i])) {
                        vertexId = (byte[]) vertexIds[i];
                    } else {
                        throw Graph.Exceptions.elementNotFound(TorcVertex.class, TorcHelper.stringifyVertexId((byte[]) vertexIds[i]));
                    }
                } else {
                    throw Graph.Exceptions.elementNotFound(TorcVertex.class, vertexIds[i]);
                }
                
                RAMCloudObject obj;
                try {
                    obj = tx.read(vertexTableId, TorcHelper.getVertexLabelKey(vertexId));
                } catch (ObjectDoesntExistException e) {
                    throw Graph.Exceptions.elementNotFound(TorcVertex.class, vertexIds[i]);
                }

                list.add(new TorcVertex(this, vertexId, obj.getValue()));
            }

            return list.iterator();
        } else {
            long max_id[] = new long[NUM_ID_COUNTERS];
            
            for (int i = 0; i < NUM_ID_COUNTERS; ++i) {
                try {
                    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    buffer.put(tx.read(idTableId, Long.toString(i)).getValueBytes());
                    buffer.flip();
                    max_id[i] = buffer.getLong();
                } catch (ObjectDoesntExistException e) {
                    max_id[i] = 0;
                }
            }
            
            for (int i = 0; i < NUM_ID_COUNTERS; ++i) {
                for (long j = 1; j <= max_id[i]; ++j) {
                    byte[] vertexId = TorcHelper.makeVertexId(i + (1<<63), j);
                    try {
                        RAMCloudObject obj = tx.read(vertexTableId, TorcHelper.getVertexLabelKey(vertexId));
                        list.add(new TorcVertex(this, vertexId, obj.getValue()));
                    } catch (ObjectDoesntExistException e) {
                        // Continue...
                    }
                }
            }
            
            return list.iterator();
        }
    }

    @Override
    public Iterator<Edge> edges(final Object... edgeIds) {
        if (!initialized)
            initialize();
        
        ramcloudGraphTransaction.readWrite();
        RAMCloudTransaction tx = ramcloudGraphTransaction.threadLocalTx.get();
        
        ElementHelper.validateMixedElementIds(TorcEdge.class, edgeIds);
        
        List<Edge> list = new ArrayList<>();
        if (edgeIds.length > 0) {
            if (edgeIds[0] instanceof TorcEdge) {
                return Arrays.asList((Edge[]) edgeIds).iterator();
            }
            
            for (int i = 0; i < edgeIds.length; ++i) {
                if (TorcHelper.validateEdgeId(edgeIds[i]))
                    list.add(new TorcEdge(this, (byte[]) edgeIds[i], TorcHelper.parseLabelFromEdgeId((byte[]) edgeIds[i])));
                else 
                    // throw Edge.Exceptions.userSuppliedIdsOfThisTypeNotSupported();
                    /**
                     * TODO: Work out what's the right thing to do here. Must
                     * throw this for now to support
                     * shouldHaveExceptionConsistencyWhenFindEdgeByIdThatIsNonExistentViaIterator
                     */
                    throw Graph.Exceptions.elementNotFound(TorcEdge.class, edgeIds[i]);
            }

            return list.iterator();
        } else {
            long max_id[] = new long[NUM_ID_COUNTERS];
            
            for (int i = 0; i < NUM_ID_COUNTERS; ++i) {
                try {
                    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    buffer.put(tx.read(idTableId, Integer.toString(i)).getValueBytes());
                    buffer.flip();
                    max_id[i] = buffer.getLong();
                } catch (ObjectDoesntExistException e) {
                    max_id[i] = 0;
                }
            }
            
            for (int i = 0; i < NUM_ID_COUNTERS; ++i) {
                for (long j = 1; j <= max_id[i]; ++j) {
                    byte[] vertexId = TorcHelper.makeVertexId(i + (1<<63), j);
                    try {
                        RAMCloudObject obj = tx.read(vertexTableId, TorcHelper.getVertexEdgeLabelListKey(vertexId));
                        List<String> edgeLabels = TorcHelper.deserializeEdgeLabelList(obj);
                        
                        for (String label : edgeLabels) {
                            try {
                                obj = tx.read(vertexTableId, TorcHelper.getVertexEdgeListKey(vertexId, label, TorcEdgeDirection.DIRECTED_OUT));
                                List<byte[]> neighborIds = TorcHelper.parseNeighborIdsFromEdgeList(obj);
                                for (byte[] neighborId : neighborIds) {
                                    list.add(new TorcEdge(this, TorcHelper.makeEdgeId(vertexId, neighborId, label, TorcEdge.Directionality.DIRECTED), label));
                                }
                            } catch (ObjectDoesntExistException e) {
                                // Continue...
                            }
                            
                            /**
                             * TODO: Decide whether or not to include the new
                             * bi-directional edges in this list. Not sure
                             * whether or not including these RAMCloudGraph
                             * specific bi-directional edges is considered a
                             * violation of the API.
                             */
//                            try {
//                                obj = tx.read(vertexTableId, TorcHelper.getVertexEdgeListKey(vertexId, label, TorcEdgeDirection.UNDIRECTED));
//                                List<byte[]> neighborIds = TorcHelper.parseNeighborIdsFromEdgeList(obj);
//                                for (byte[] neighborId : neighborIds) {
//                                    BigInteger myId = new BigInteger(vertexId);
//                                    BigInteger theirId = new BigInteger(neighborId);
//                                    // Prevent double counting
//                                    if (myId.compareTo(theirId) < 0)
//                                        list.add(new TorcEdge(this, TorcHelper.makeEdgeId(vertexId, neighborId, label, TorcEdge.Directionality.UNDIRECTED), label));
//                                }
//                            } catch (ObjectDoesntExistException e) {
//                                // Continue...
//                            }
                        }
                    } catch (ObjectDoesntExistException e) {
                        // Continue...
                    }
                }
            }
            
            return list.iterator();
        }
    }

    @Override
    public Transaction tx() {
        if (!initialized)
            initialize();
        
        return ramcloudGraphTransaction;
    }

    @Override
    public Variables variables() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Configuration configuration() {
        return configuration;
    }

    @Override
    public void close() {
        if (initialized) {
            ramcloudGraphTransaction.close();
            ramcloud.disconnect();
        }
    }
    
    public void deleteDatabase() {
        if (!initialized)
            initialize();
        
        ramcloudGraphTransaction.close();
        ramcloud.dropTable(graphName + "_" + ID_TABLE_NAME);
        ramcloud.dropTable(graphName + "_" + VERTEX_TABLE_NAME);
        ramcloud.dropTable(graphName + "_" + EDGE_TABLE_NAME);
    }
    
    public void deleteDatabaseAndCloseConnection() {
        if (!initialized)
            initialize();
        
        ramcloudGraphTransaction.close();
        ramcloud.dropTable(graphName + "_" + ID_TABLE_NAME);
        ramcloud.dropTable(graphName + "_" + VERTEX_TABLE_NAME);
        ramcloud.dropTable(graphName + "_" + EDGE_TABLE_NAME);
        ramcloud.disconnect();
    }
    
    @Override
    public String toString() {
        return StringFactory.graphString(this, "coordLoc:" + this.coordinatorLocator + " graphName:" + this.graphName);
    }
    
    @Override
    public Features features() {
        return new RAMCloudGraphFeatures();
    }

    /** Methods called by TorcVertex. */
    
    void removeVertex(final TorcVertex vertex) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
    Edge addEdge(final TorcVertex vertex1, final TorcVertex vertex2, final String label, final TorcEdge.Directionality directionality, final Object[] keyValues) {
        ramcloudGraphTransaction.readWrite();
        RAMCloudTransaction tx = ramcloudGraphTransaction.threadLocalTx.get();
        
        // Validate that these key/value pairs are all strings
        if (keyValues.length % 2 != 0) {
            throw Element.Exceptions.providedKeyValuesMustBeAMultipleOfTwo();
        }
        for (int i = 0; i < keyValues.length; i = i + 2) {
            if (keyValues[i] instanceof T) {
                if (keyValues[i].equals(T.id)) {
                    throw Edge.Exceptions.userSuppliedIdsNotSupported();
                } else {
                    throw new UnsupportedOperationException("Not supported yet.");
                }
            }
            if (!(keyValues[i] instanceof String)) {
                throw Element.Exceptions.providedKeyValuesMustHaveALegalKeyOnEvenIndices();
            }
            if (!(keyValues[i + 1] instanceof String)) {
                throw Property.Exceptions.dataTypeOfPropertyValueNotSupported(keyValues[i + 1]);
            }
        }

        // Create property map.
        Map<String, String> properties = new HashMap<>();
        for (int i = 0; i < keyValues.length; i = i + 2) {
            properties.put((String) keyValues[i], (String) keyValues[i + 1]);
        }

        ByteBuffer serializedProperties = TorcHelper.serializeProperties(properties);
        int serializedEdgeLength = Long.BYTES * 2 + Short.BYTES + serializedProperties.array().length;

        // Update vertex1's edge list
        String edgeListKey;
        if (directionality == TorcEdge.Directionality.UNDIRECTED) {
            edgeListKey = TorcHelper.getVertexEdgeListKey(vertex1.id, label, TorcEdgeDirection.UNDIRECTED);
        } else {
            edgeListKey = TorcHelper.getVertexEdgeListKey(vertex1.id, label, TorcEdgeDirection.DIRECTED_OUT);
        }
        try {
            RAMCloudObject edgeListRCObj = tx.read(vertexTableId, edgeListKey);

            ByteBuffer newEdgeList = ByteBuffer.allocate(serializedEdgeLength + edgeListRCObj.getValueBytes().length);

            newEdgeList.put(vertex2.id);
            newEdgeList.putShort((short) serializedProperties.array().length);
            newEdgeList.put(serializedProperties.array());
            newEdgeList.put(edgeListRCObj.getValueBytes());

            tx.write(vertexTableId, edgeListKey, newEdgeList.array());
        } catch (ObjectDoesntExistException e) {
            ByteBuffer newEdgeList = ByteBuffer.allocate(serializedEdgeLength);
            
            newEdgeList.put(vertex2.id);
            newEdgeList.putShort((short) serializedProperties.array().length);
            newEdgeList.put(serializedProperties.array());
            
            tx.write(vertexTableId, edgeListKey, newEdgeList.array());
            
            String edgeLabelListKey = TorcHelper.getVertexEdgeLabelListKey(vertex1.id);
            try {
                RAMCloudObject edgeLabelListRCObj = tx.read(vertexTableId, edgeLabelListKey);
                List<String> edgeLabelList = TorcHelper.deserializeEdgeLabelList(edgeLabelListRCObj);
                if (!edgeLabelList.contains(label)) {
                    edgeLabelList.add(label);
                    tx.write(vertexTableId, edgeLabelListKey, TorcHelper.serializeEdgeLabelList(edgeLabelList).array());
                }
            } catch (ObjectDoesntExistException e2) {
                List<String> edgeLabelList = new ArrayList<>();
                edgeLabelList.add(label);
                tx.write(vertexTableId, edgeLabelListKey, TorcHelper.serializeEdgeLabelList(edgeLabelList).array());
            }
        }
        
        // Update vertex2's edge list
        if (directionality == TorcEdge.Directionality.UNDIRECTED) {
            edgeListKey = TorcHelper.getVertexEdgeListKey(vertex2.id, label, TorcEdgeDirection.UNDIRECTED);
        } else {
            edgeListKey = TorcHelper.getVertexEdgeListKey(vertex2.id, label, TorcEdgeDirection.DIRECTED_IN);
        }
        try {
            RAMCloudObject edgeListRCObj = tx.read(vertexTableId, edgeListKey);

            ByteBuffer newEdgeList = ByteBuffer.allocate(serializedEdgeLength + edgeListRCObj.getValueBytes().length);

            newEdgeList.put(vertex1.id);
            newEdgeList.putShort((short) serializedProperties.array().length);
            newEdgeList.put(serializedProperties.array());
            newEdgeList.put(edgeListRCObj.getValueBytes());

            tx.write(vertexTableId, edgeListKey, newEdgeList.array());
        } catch (ObjectDoesntExistException e) {
            ByteBuffer newEdgeList = ByteBuffer.allocate(serializedEdgeLength);
            
            newEdgeList.put(vertex1.id);
            newEdgeList.putShort((short) serializedProperties.array().length);
            newEdgeList.put(serializedProperties.array());
            
            tx.write(vertexTableId, edgeListKey, newEdgeList.array());
            
            String edgeLabelListKey = TorcHelper.getVertexEdgeLabelListKey(vertex2.id);
            try {
                RAMCloudObject edgeLabelListRCObj = tx.read(vertexTableId, edgeLabelListKey);
                List<String> edgeLabelList = TorcHelper.deserializeEdgeLabelList(edgeLabelListRCObj);
                if (!edgeLabelList.contains(label)) {
                    edgeLabelList.add(label);
                    tx.write(vertexTableId, edgeLabelListKey, TorcHelper.serializeEdgeLabelList(edgeLabelList).array());
                }
            } catch (ObjectDoesntExistException e2) {
                List<String> edgeLabelList = new ArrayList<>();
                edgeLabelList.add(label);
                tx.write(vertexTableId, edgeLabelListKey, TorcHelper.serializeEdgeLabelList(edgeLabelList).array());
            }
        }
        
        return new TorcEdge(this, TorcHelper.makeEdgeId(vertex1.id, vertex2.id, label, directionality), label);
    }
    
    Iterator<Edge> vertexEdges(final TorcVertex vertex, final EnumSet<TorcEdgeDirection> edgeDirections, final String[] edgeLabels) {
        ramcloudGraphTransaction.readWrite();
        RAMCloudTransaction tx = ramcloudGraphTransaction.threadLocalTx.get();
        
        List<Edge> edges = new ArrayList<>();
        List<String> labels = Arrays.asList(edgeLabels);
        
        if (labels.isEmpty()) {
            try {
                RAMCloudObject vertEdgeLabelListRCObj = tx.read(vertexTableId, TorcHelper.getVertexEdgeLabelListKey(vertex.id));
                labels = TorcHelper.deserializeEdgeLabelList(vertEdgeLabelListRCObj);
                if (labels.isEmpty())
                    return edges.iterator();
            } catch (ObjectDoesntExistException e) {
                return edges.iterator();
            }
        }
        
        for (String label : labels) {
            for (TorcEdgeDirection dir : edgeDirections) {
                try {
                    String edgeListKey = TorcHelper.getVertexEdgeListKey(vertex.id, label, dir);
                    RAMCloudObject edgeListRCObj = tx.read(vertexTableId, edgeListKey);
                    List<byte[]> neighborIds = TorcHelper.parseNeighborIdsFromEdgeList(edgeListRCObj);
                    for (byte[] neighborId : neighborIds) {
                        byte[] edgeId;
                        switch(dir) {
                            case DIRECTED_OUT:
                                edgeId = TorcHelper.makeEdgeId(vertex.id, neighborId, label, TorcEdge.Directionality.DIRECTED);
                                edges.add(new TorcEdge(this, edgeId, label));
                                break;
                            case DIRECTED_IN:
                                edgeId = TorcHelper.makeEdgeId(neighborId, vertex.id, label, TorcEdge.Directionality.DIRECTED);
                                edges.add(new TorcEdge(this, edgeId, label));
                                break;
                            case UNDIRECTED:
                                edgeId = TorcHelper.makeEdgeId(vertex.id, neighborId, label, TorcEdge.Directionality.UNDIRECTED);
                                edges.add(new TorcEdge(this, edgeId, label));
                                break;
                        }
                    }
                } catch (ObjectDoesntExistException e) {
                    // Catch and ignore. This case is ok.
                }
            }
        }
        
        return edges.iterator();
    }

    Iterator<Vertex> vertexNeighbors(final TorcVertex vertex, final EnumSet<TorcEdgeDirection> edgeDirections, final String[] edgeLabels) {
        ramcloudGraphTransaction.readWrite();
        RAMCloudTransaction tx = ramcloudGraphTransaction.threadLocalTx.get();
        
        List<Vertex> vertices = new ArrayList<>();
        List<String> labels = Arrays.asList(edgeLabels);
        
        if (labels.isEmpty()) {
            try {
                RAMCloudObject vertEdgeLabelListRCObj = tx.read(vertexTableId, TorcHelper.getVertexEdgeLabelListKey(vertex.id));
                labels = TorcHelper.deserializeEdgeLabelList(vertEdgeLabelListRCObj);
                if (labels.isEmpty())
                    return vertices.iterator();
            } catch (ObjectDoesntExistException e) {
                return vertices.iterator();
            }
        }
        
        for (String label : labels) {
            for (TorcEdgeDirection dir : edgeDirections) {
                try {
                    String edgeListKey = TorcHelper.getVertexEdgeListKey(vertex.id, label, dir);
                    RAMCloudObject edgeListRCObj = tx.read(vertexTableId, edgeListKey);
                    List<byte[]> neighborIds = TorcHelper.parseNeighborIdsFromEdgeList(edgeListRCObj);
                    for (byte[] neighborId : neighborIds) {
                        RAMCloudObject neighborLabelRCObj = tx.read(vertexTableId, TorcHelper.getVertexLabelKey(neighborId));
                        vertices.add(new TorcVertex(this, neighborId, neighborLabelRCObj.getValue()));
                    }
                } catch (ObjectDoesntExistException e) {
                    // Catch and ignore. This case is ok.
                }
            }
        }
        
        return vertices.iterator();
    }

    <V> Iterator<VertexProperty<V>> getVertexProperties(final TorcVertex vertex, final String[] propertyKeys) {
        ramcloudGraphTransaction.readWrite();
        RAMCloudTransaction tx = ramcloudGraphTransaction.threadLocalTx.get();
        
        RAMCloudObject obj = tx.read(vertexTableId, TorcHelper.getVertexPropertiesKey(vertex.id));
        
        Map<String, String> properties = TorcHelper.deserializeProperties(obj);
        
        List<VertexProperty<V>> propList = new ArrayList<>();
        
        if (propertyKeys.length > 0) {
            for (String key : propertyKeys) {
                if (properties.containsKey(key)) {
                    propList.add(new TorcVertexProperty(vertex, key, properties.get(key)));
                } else {
                    throw Property.Exceptions.propertyDoesNotExist(vertex, key);
                }
            }
        } else {
            for (Map.Entry<String, String> property : properties.entrySet()) {
                propList.add(new TorcVertexProperty(vertex, property.getKey(), property.getValue()));
            }
        }
        
        return propList.iterator();
    }

    <V> VertexProperty<V> setVertexProperty(final TorcVertex vertex, final VertexProperty.Cardinality cardinality, final String key, final V value, final Object[] keyValues) {
        ramcloudGraphTransaction.readWrite();
        RAMCloudTransaction tx = ramcloudGraphTransaction.threadLocalTx.get();
        
        if (keyValues != null) 
            throw VertexProperty.Exceptions.metaPropertiesNotSupported();
        
        if (!(value instanceof String))
            throw Property.Exceptions.dataTypeOfPropertyValueNotSupported(value);
        
        RAMCloudObject obj = tx.read(vertexTableId, TorcHelper.getVertexPropertiesKey(vertex.id));

        Map<String, String> properties = TorcHelper.deserializeProperties(obj);

        if (properties.containsKey(key)) {
            if (cardinality == VertexProperty.Cardinality.single) {
                properties.put(key, (String) value);
            } else if (cardinality == VertexProperty.Cardinality.list) {
                throw VertexProperty.Exceptions.multiPropertiesNotSupported();
            } else if (cardinality == VertexProperty.Cardinality.set) {
                throw VertexProperty.Exceptions.multiPropertiesNotSupported();
            } else {
                throw new UnsupportedOperationException("Do not recognize Cardinality of this type: " + cardinality.toString());
            }
        } else {
            properties.put(key, (String) value);
        }

        tx.write(vertexTableId, TorcHelper.getVertexPropertiesKey(vertex.id), TorcHelper.serializeProperties(properties).array());

        return new TorcVertexProperty(vertex, key, value);
    }

    /** Methods called by TorcEdge. */
    
    void removeEdge(final TorcEdge edge) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    Iterator<Vertex> edgeVertices(final TorcEdge edge, final Direction direction) {
        ramcloudGraphTransaction.readWrite();
        RAMCloudTransaction tx = ramcloudGraphTransaction.threadLocalTx.get();
        
        List<Vertex> list = new ArrayList<>();
        
        if (direction.equals(Direction.OUT) || direction.equals(Direction.BOTH)) {
            byte[] outVertexId = TorcHelper.parseOutVertexIdFromEdgeId(edge.id);
            RAMCloudObject obj = tx.read(vertexTableId, TorcHelper.getVertexLabelKey(outVertexId));
            list.add(new TorcVertex(this, outVertexId, obj.getValue()));
        }
        
        if (direction.equals(Direction.IN) || direction.equals(Direction.BOTH)) {
            byte[] inVertexId = TorcHelper.parseInVertexIdFromEdgeId(edge.id);
            RAMCloudObject obj = tx.read(vertexTableId, TorcHelper.getVertexLabelKey(inVertexId));
            list.add(new TorcVertex(this, inVertexId, obj.getValue()));
        }
        
        return list.iterator();
    }

    <V> Iterator<Property<V>> getEdgeProperties(final TorcEdge edge, final String[] propertyKeys) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    <V> Property<V> setEdgeProperty(final TorcEdge edge, final String key, final V value) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    class RAMCloudGraphTransaction extends AbstractTransaction {

        protected final ThreadLocal<RAMCloudTransaction> threadLocalTx = ThreadLocal.withInitial(() -> null);

        public RAMCloudGraphTransaction() {
            super(TorcGraph.this);
        }

        @Override
        public void doOpen() {
            threadLocalTx.set(new RAMCloudTransaction(TorcGraph.this.ramcloud));
        }

        @Override
        public void doCommit() throws AbstractTransaction.TransactionException {
            try {
                if (!threadLocalTx.get().commitAndSync())
                    throw new AbstractTransaction.TransactionException("RAMCloud commitAndSync failed.");
            } catch (ClientException ex) {
                throw new AbstractTransaction.TransactionException(ex);
            } finally {
                threadLocalTx.get().close();
                threadLocalTx.remove();
            }
        }

        @Override
        public void doRollback() throws AbstractTransaction.TransactionException {
            try {
                threadLocalTx.get().close();
            } catch (Exception e) {
                throw new AbstractTransaction.TransactionException(e);
            } finally {
                threadLocalTx.remove();
            }
        }

        @Override
        public boolean isOpen() {
            return (threadLocalTx.get() != null);
        }
    }
    
    public class RAMCloudGraphFeatures implements Features {

        private RAMCloudGraphFeatures() {
        }

        @Override
        public Features.GraphFeatures graph() {
            return new RAMCloudGraphGraphFeatures();
        }

        @Override
        public Features.VertexFeatures vertex() {
            return new RAMCloudGraphVertexFeatures();
        }
        
        @Override
        public Features.EdgeFeatures edge() {
            return new RAMCloudGraphEdgeFeatures();
        }

        @Override
        public String toString() {
            return StringFactory.featureString(this);
        }
    }

    /**
     * TODO: Turn on supportsPersistence and supportsTransactions, and test.
     */
    public class RAMCloudGraphGraphFeatures implements Features.GraphFeatures {

        private RAMCloudGraphGraphFeatures() {
        }

        @Override
        public boolean supportsComputer() {
            return false;
        }

        @Override
        public boolean supportsPersistence() {
            return false;
        }

        @Override
        public boolean supportsConcurrentAccess() {
            return false;
        }

        @Override
        public boolean supportsTransactions() {
            return false;
        }

        @Override
        public boolean supportsThreadedTransactions() {
            return false;
        }

        @Override
        public Features.VariableFeatures variables() {
            return new RAMCloudGraphVariableFeatures() {
            };
        }

    }
    
    public class RAMCloudGraphVertexFeatures implements Features.VertexFeatures {

        private RAMCloudGraphVertexFeatures() {
        }
        
        @Override
        public VertexProperty.Cardinality getCardinality(final String key) {
            return VertexProperty.Cardinality.single;
        }

        @Override
        public boolean supportsAddVertices() {
            return true;
        }

        @Override
        public boolean supportsRemoveVertices() {
            return false;
        }

        @Override
        public boolean supportsMultiProperties() {
            return false;
        }

        @Override
        public boolean supportsMetaProperties() {
            return false;
        }
        
        @Override
        public Features.VertexPropertyFeatures properties() {
            return new RAMCloudGraphVertexPropertyFeatures();
        }
        
        @Override
        public boolean supportsAddProperty() {
            return true;
        }
        
        @Override
        public boolean supportsRemoveProperty() {
            return true;
        }
                
        @Override
        public boolean supportsUserSuppliedIds() {
            return false;
        }
        
        @Override
        public boolean supportsNumericIds() {
            return false;
        }
        
        @Override
        public boolean supportsStringIds() {
            return false;
        }
        
        @Override
        public boolean supportsUuidIds() {
            return false;
        }
        
        @Override
        public boolean supportsCustomIds() {
            return false;
        }
        
        @Override
        public boolean supportsAnyIds() {
            return false;
        }
    }

    public class RAMCloudGraphEdgeFeatures implements Features.EdgeFeatures {

        private RAMCloudGraphEdgeFeatures() {
        }
        
        @Override
        public boolean supportsAddEdges() {
            return true;
        }

        @Override
        public boolean supportsRemoveEdges() {
            return false;
        }

        @Override
        public Features.EdgePropertyFeatures properties() {
            return new RAMCloudGraphEdgePropertyFeatures() {
            };
        }
        
        @Override
        public boolean supportsAddProperty() {
            return false;
        }
        
        @Override
        public boolean supportsRemoveProperty() {
            return false;
        }
                
        @Override
        public boolean supportsUserSuppliedIds() {
            return false;
        }
        
        @Override
        public boolean supportsNumericIds() {
            return false;
        }
        
        @Override
        public boolean supportsStringIds() {
            return false;
        }
        
        @Override
        public boolean supportsUuidIds() {
            return false;
        }
        
        @Override
        public boolean supportsCustomIds() {
            return false;
        }
        
        @Override
        public boolean supportsAnyIds() {
            return false;
        }
    }

    public class RAMCloudGraphVertexPropertyFeatures implements Features.VertexPropertyFeatures {

        private RAMCloudGraphVertexPropertyFeatures() {
        }
        
        @Override
        public boolean supportsAddProperty() {
            return false;
        }

        @Override
        public boolean supportsRemoveProperty() {
            return false;
        }

        @Override
        public boolean supportsUserSuppliedIds() {
            return false;
        }
        
        @Override
        public boolean supportsNumericIds() {
            return false;
        }
        
        @Override
        public boolean supportsStringIds() {
            return false;
        }

        @Override
        public boolean supportsUuidIds() {
            return false;
        }

        @Override
        public boolean supportsCustomIds() {
            return false;
        }

        @Override
        public boolean supportsAnyIds() {
            return false;
        }
        
        @Override
        public boolean supportsBooleanValues() {
            return false;
        }
        
        @Override
        public boolean supportsByteValues() {
            return false;
        }
        
        @Override
        public boolean supportsDoubleValues() {
            return false;
        }
        
        @Override
        public boolean supportsFloatValues() {
            return false;
        }
        
        @Override
        public boolean supportsIntegerValues() {
            return false;
        }
        
        @Override
        public boolean supportsLongValues() {
            return false;
        }
        
        @Override
        public boolean supportsMapValues() {
            return false;
        }
        
        @Override
        public boolean supportsMixedListValues() {
            return false;
        }
        
        @Override
        public boolean supportsBooleanArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsByteArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsDoubleArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsFloatArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsIntegerArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsStringArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsLongArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsSerializableValues() {
            return false;
        }
        
        @Override
        public boolean supportsStringValues() {
            return true;
        }
        
        @Override
        public boolean supportsUniformListValues() {
            return false;
        }
    }
    
    public class RAMCloudGraphEdgePropertyFeatures implements Features.EdgePropertyFeatures {

        private RAMCloudGraphEdgePropertyFeatures() {
        }
        
        @Override
        public boolean supportsBooleanValues() {
            return false;
        }
        
        @Override
        public boolean supportsByteValues() {
            return false;
        }
        
        @Override
        public boolean supportsDoubleValues() {
            return false;
        }
        
        @Override
        public boolean supportsFloatValues() {
            return false;
        }
        
        @Override
        public boolean supportsIntegerValues() {
            return false;
        }
        
        @Override
        public boolean supportsLongValues() {
            return false;
        }
        
        @Override
        public boolean supportsMapValues() {
            return false;
        }
        
        @Override
        public boolean supportsMixedListValues() {
            return false;
        }
        
        @Override
        public boolean supportsBooleanArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsByteArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsDoubleArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsFloatArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsIntegerArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsStringArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsLongArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsSerializableValues() {
            return false;
        }
        
        @Override
        public boolean supportsStringValues() {
            return true;
        }
        
        @Override
        public boolean supportsUniformListValues() {
            return false;
        }
    }
    
    public class RAMCloudGraphVariableFeatures implements Features.VariableFeatures {

        private RAMCloudGraphVariableFeatures() {
        }
        
        @Override
        public boolean supportsBooleanValues() {
            return false;
        }
        
        @Override
        public boolean supportsByteValues() {
            return false;
        }
        
        @Override
        public boolean supportsDoubleValues() {
            return false;
        }
        
        @Override
        public boolean supportsFloatValues() {
            return false;
        }
        
        @Override
        public boolean supportsIntegerValues() {
            return false;
        }
        
        @Override
        public boolean supportsLongValues() {
            return false;
        }
        
        @Override
        public boolean supportsMapValues() {
            return false;
        }
        
        @Override
        public boolean supportsMixedListValues() {
            return false;
        }
        
        @Override
        public boolean supportsBooleanArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsByteArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsDoubleArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsFloatArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsIntegerArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsStringArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsLongArrayValues() {
            return false;
        }
        
        @Override
        public boolean supportsSerializableValues() {
            return false;
        }
        
        @Override
        public boolean supportsStringValues() {
            return false;
        }
        
        @Override
        public boolean supportsUniformListValues() {
            return false;
        }
    }
}