package com.thinkaurelius.titan.graphdb.transaction;

import com.google.common.base.Preconditions;
import com.google.common.collect.*;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.attribute.Interval;
import com.thinkaurelius.titan.core.attribute.PointInterval;
import com.thinkaurelius.titan.exceptions.InvalidEntityException;
import com.thinkaurelius.titan.graphdb.edgequery.InternalEdgeQuery;
import com.thinkaurelius.titan.graphdb.edgequery.StandardEdgeQuery;
import com.thinkaurelius.titan.graphdb.edges.InternalEdge;
import com.thinkaurelius.titan.graphdb.edges.factory.EdgeFactory;
import com.thinkaurelius.titan.graphdb.edgetypes.manager.EdgeTypeManager;
import com.thinkaurelius.titan.graphdb.edgetypes.system.SystemPropertyType;
import com.thinkaurelius.titan.graphdb.vertices.InternalNode;
import com.thinkaurelius.titan.graphdb.vertices.NewEmptyNode;
import com.thinkaurelius.titan.graphdb.vertices.factory.NodeFactory;
import com.thinkaurelius.titan.traversal.AllRelationshipsIterable;
import com.thinkaurelius.titan.util.datastructures.Factory;
import com.thinkaurelius.titan.util.datastructures.IterablesUtil;
import com.thinkaurelius.titan.util.datastructures.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractGraphTx implements GraphTx {

	private static final Logger log = LoggerFactory.getLogger(AbstractGraphTx.class);
	
	protected final EdgeTypeManager etManager;

	
	protected final NodeFactory nodeFactory;
	protected final EdgeFactory edgeFactory;
	
	private ConcurrentMap<PropertyType,ConcurrentMap<Object,Node>> keyIndex;
    private ConcurrentMap<PropertyType,Multimap<Object,Node>> attributeIndex;
	private Set<InternalNode> newNodes;
	
	private boolean isOpen;
	private final GraphTransactionConfig config;
	
	private final Lock keyedPropertyCreateLock = new ReentrantLock();

	
	public AbstractGraphTx(NodeFactory nodeFac, EdgeFactory edgeFac,
			EdgeTypeManager etManage, GraphTransactionConfig config, boolean trackNewNodes) {
		etManager = etManage;
		nodeFactory = nodeFac;
		edgeFactory = edgeFac;
		edgeFactory.setTransaction(this);
		

		this.config=config;
		isOpen = true;

		if (!config.isReadOnly() && trackNewNodes) newNodes = Collections.newSetFromMap(new ConcurrentHashMap<InternalNode,Boolean>(10,0.75f,2));
		else newNodes=null;

		keyIndex = new ConcurrentHashMap<PropertyType,ConcurrentMap<Object,Node>>(20,0.75f,2);
        attributeIndex = new ConcurrentHashMap<PropertyType,Multimap<Object,Node>>(20,0.75f,2);
	}


	protected void verifyWriteAccess() {
		if (config.isReadOnly()) throw new UnsupportedOperationException("Cannot create new entities in read-only transaction!");
	}
	
	/* ---------------------------------------------------------------
	 * Node and Edge creation
	 * ---------------------------------------------------------------
	 */
	

	@Override
	public Node createNode() {
		verifyWriteAccess();
		InternalNode n = nodeFactory.createNew(this);
		if (newNodes!=null) newNodes.add(n);
		return n;
	}
	
	
	@Override
	public Property createProperty(PropertyType relType, Node node,	Object attribute) {
		//Check that attribute of keyed propertyType is unique
		if (relType.isKeyed()) {
			keyedPropertyCreateLock.lock();
			if (config.doVerifyKeyUniqueness() && getNodeByKey(relType, attribute)!=null)
				throw new InvalidEntityException("The specified attribute is already used as a key for the given property type: " + attribute);
		}
			
		InternalEdge e = edgeFactory.createNewProperty(relType, (InternalNode)node, attribute);
		addedEdge(e);
		if (relType.isKeyed()) keyedPropertyCreateLock.unlock();
		return (Property)e;
	}


	@Override
	public Property createProperty(String relType, Node node, Object attribute) {
		return createProperty(getPropertyType(relType),node,attribute);
	}


	@Override
	public Relationship createRelationship(RelationshipType relType, Node start, Node end) {
		InternalEdge e = edgeFactory.createNewRelationship(relType, (InternalNode)start, (InternalNode)end);
		addedEdge(e);
		return (Relationship)e;
	}


	@Override
	public Relationship createRelationship(String relType, Node start, Node end) {
		return createRelationship(getRelationshipType(relType),start,end);
	}
	

	@Override
	public EdgeTypeMaker createEdgeType() {
		verifyWriteAccess();
		return etManager.getEdgeTypeMaker(this);
	}


	@Override
	public boolean containsEdgeType(String name) {
		Map<Object,Node> subindex = keyIndex.get(SystemPropertyType.EdgeTypeName);
		if (subindex==null || !subindex.containsKey(name)) return false;
		else return true;
	}

	@Override
	public PropertyType getPropertyType(String name) {
		EdgeType et = getEdgeType(name);
		if (et==null) {
            if (config.doAutoCreateEdgeTypes()) return config.getAutoEdgeTypeMaker().makePropertyType(name,createEdgeType());
			else throw new IllegalArgumentException("PropertyType with given name does not exist: " + name);
        } else if (et.isPropertyType()) {
			return (PropertyType)et;
		} else throw new IllegalArgumentException("The EdgeType of given name is not a PropertyType!");

	}
	

	@Override
	public RelationshipType getRelationshipType(String name) {
		EdgeType et = getEdgeType(name);
		if (et==null) {
            if (config.doAutoCreateEdgeTypes()) return config.getAutoEdgeTypeMaker().makeRelationshipType(name,createEdgeType());
            throw new IllegalArgumentException("RelationType with given name does not exist: " + name);
        } else if (et.isRelationshipType()) {
			return (RelationshipType)et;
		} else throw new IllegalArgumentException("The EdgeType of given name is not a RelationshipType! " + name);
	}

	
	@Override
	public EdgeType getEdgeType(String name) {
		Map<Object,Node> subindex = keyIndex.get(SystemPropertyType.EdgeTypeName);
		if (subindex==null) {
			return null;
		} else {
			return (EdgeType)subindex.get(name);
		}
		
	}

	

	@Override
	public void addedEdge(InternalEdge edge) {
		verifyWriteAccess();
		Preconditions.checkArgument(edge.isNew());
	}
	
	@Override
	public void deletedEdge(InternalEdge edge) {
		verifyWriteAccess();
		if (edge.isProperty() && !edge.isInline()) {
			Property prop = (Property)edge;
			if (prop.getPropertyType().getIndexType().hasIndex()) {
				removeKeyFromIndex(prop);
			}
		}
	}
	
	
	@Override
	public void loadedEdge(InternalEdge edge) {
		if (edge.isProperty() && !edge.isInline()) {
			Property prop = (Property)edge;
            if (prop.getPropertyType().getIndexType().hasIndex()) {
			    addProperty2Index(prop);
            }
		}
	}
	
	@Override
	public InternalEdgeQuery makeEdgeQuery(InternalNode n) {
		return new StandardEdgeQuery(n);
	}

	@Override
	public EdgeQuery makeEdgeQuery(long nodeid) {
		return new StandardEdgeQuery((InternalNode)getNode(nodeid));
	}

	@Override
	public Iterable<? extends Node> getAllNodes() {
		if (config.isReadOnly()) return IterablesUtil.emptyIterable();
		else if (newNodes==null) 
			throw new UnsupportedOperationException("Iterating over all nodes is not supported in this transaction.");
		else return newNodes;
	}


	@Override
	public Iterable<Relationship> getAllRelationships() {
		return AllRelationshipsIterable.of(getAllNodes());
	}

	
	/* ---------------------------------------------------------------
	 * Index Handling
	 * ---------------------------------------------------------------
	 */	
	
	private void addProperty2Index(Property property) {
	    addProperty2Index(property.getPropertyType(), property.getAttribute(), property.getStart());
	}

	private static Factory<ConcurrentMap<Object,Node>> keyIndexFactory = new Factory<ConcurrentMap<Object,Node>>() {
		@Override
		public ConcurrentMap<Object, Node> create() {
			return new ConcurrentHashMap<Object,Node>(10,0.75f,4);
		}
	};

    private static Factory<Multimap<Object,Node>> attributeIndexFactory = new Factory<Multimap<Object,Node>>() {
        @Override
        public Multimap<Object, Node> create() {
            Multimap<Object,Node> map = ArrayListMultimap.create(10,20);
            return map;
            //return Multimaps.synchronizedSetMultimap(map);
        }
    };
	
	protected void addProperty2Index(PropertyType type, Object att, Node node) {
        Preconditions.checkArgument(type.getIndexType().hasIndex());
		if (type.isKeyed()) {
            //TODO ignore NO-ENTRTY
            ConcurrentMap<Object,Node> subindex = Maps.putIfAbsent(keyIndex, type, keyIndexFactory);

            Node oth = subindex.putIfAbsent(att, node);
            if (oth!=null && !oth.equals(node)) {
                throw new IllegalArgumentException("The key is already used by another node!");
            }
        } else {
            Multimap<Object,Node> subindex = Maps.putIfAbsent(attributeIndex, type, attributeIndexFactory);
            subindex.put(att,node);
        }
	}
	
	private void removeKeyFromIndex(Property property) {
        Preconditions.checkArgument(property.getPropertyType().getIndexType().hasIndex());
        
		PropertyType type = property.getPropertyType();
		if (type.isKeyed()) {
            Map<Object,Node> subindex = keyIndex.get(type);
            Preconditions.checkNotNull(subindex);
            Node n = subindex.remove(property.getAttribute());
            Preconditions.checkArgument(n!=null && n.equals(property.getStart()));
            //TODO Set to NO-ENTRY node object
        } else {
            boolean hasIdenticalProperty = false;
            for (Property p2 : property.getStart().getProperties(type)) {
                if (!p2.equals(property) && p2.getAttribute().equals(property.getAttribute())) {
                    hasIdenticalProperty=true;
                    break;
                }
            }
            if (!hasIdenticalProperty) {
                Multimap<Object,Node> subindex = attributeIndex.get(type);
                Preconditions.checkNotNull(subindex);
                boolean removed = subindex.remove(property.getAttribute(),property.getStart());
                assert removed;
            }
        }

	}

    // #### Keyed Properties #####

	@Override
	public Node getNodeByKey(PropertyType type, Object key) {
		Preconditions.checkArgument(type.isKeyed());
		Map<Object,Node> subindex = keyIndex.get(type);
		if (subindex==null) {
			return null;
		} else {
            //TODO: check for NO-ENTRY and return null
			return subindex.get(key);
		}
	}
	
	@Override
	public Node getNodeByKey(String type, Object key) {
        if (!containsEdgeType(type)) return null;
		return getNodeByKey(getPropertyType(type),key);
	}

    // #### General Indexed Properties #####

	@Override
	public Set<Node> getNodesByAttribute(String type, Object attribute) {
        if (!containsEdgeType(type)) return ImmutableSet.of();
		else return getNodesByAttribute(getPropertyType(type), attribute);
	}
	
	@Override
	public Set<Node> getNodesByAttribute(PropertyType type, Object attribute) {
		return getNodesByAttribute(type,new PointInterval<Object>(attribute));
	}
	
	@Override
	public Set<Node> getNodesByAttribute(PropertyType type, Interval<?> interval) {
        Preconditions.checkArgument(type.getIndexType().hasIndex());
        //First, get stuff from disk
        long[] nodeids = getNodeIDsByAttributeFromDisk(type, interval);
        Set<Node> nodes = new HashSet<Node>(nodeids.length);
        for (int i=0;i<nodeids.length;i++)
            nodes.add(getExistingNode(nodeids[i]));
        //Next, the in-memory stuff
        Multimap<Object,Node> subindex = attributeIndex.get(type);
        if (subindex!=null) {
            if (interval.isPoint()) {
                nodes.addAll(subindex.get(interval.getStartPoint()));
            } else {
                for (Object candidate : subindex.keySet()) {
                    if (interval.inInterval(candidate)) {
                        nodes.addAll(subindex.get(candidate));
                    }
                }
            }
        }
		return nodes;
	}


	@Override
	public Set<Node> getNodesByAttribute(String type, Interval<?> interval) {
        if (!containsEdgeType(type)) return ImmutableSet.of();
		return getNodesByAttribute(getPropertyType(type),interval);
	}

	
	/* ---------------------------------------------------------------
	 * Transaction Handling
	 * ---------------------------------------------------------------
	 */	
	
	private void close() {
		keyIndex.clear(); keyIndex=null;
		isOpen=false;
	}

	@Override
	public synchronized void flush() {
		
	}

    @Override
    public synchronized void rollingCommit() {

    }
	
	
	@Override
	public synchronized void commit() {
		close();
	}

	@Override
	public synchronized void abort() {
		close();
	}
	
	
	@Override
	public EdgeFactory getEdgeFactory() {
		return edgeFactory;
	}
	
	@Override
	public boolean isOpen() {
		return isOpen;
	}
	
	@Override
	public boolean isClosed() {
		return !isOpen;
	}
	
	@Override
	public GraphTransactionConfig getTxConfiguration() {
		return config;
	}

	@Override
	public boolean hasModifications() {
		return !config.isReadOnly() && newNodes!=null && !newNodes.isEmpty();
	}















	
}
