package net.notdot.bdbdatastore.server;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.notdot.bdbdatastore.Indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.appengine.datastore_v3.DatastoreV3.Query;
import com.google.appengine.entity.Entity;
import com.google.appengine.entity.Entity.EntityProto;
import com.google.appengine.entity.Entity.Path;
import com.google.appengine.entity.Entity.Property;
import com.google.appengine.entity.Entity.Reference;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentLockedException;
import com.sleepycat.je.JoinCursor;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.SecondaryConfig;
import com.sleepycat.je.SecondaryDatabase;
import com.sleepycat.je.Sequence;
import com.sleepycat.je.SequenceConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;

public class AppDatastore {
	final Logger logger = LoggerFactory.getLogger(AppDatastore.class);
	
	protected String app_id;
	
	// The database environment, containing all the tables and indexes.
	protected Environment env;
	
	// The primary entities table. The primary key is the encoded Reference protocol buffer.
	// References are sorted first by kind, then by path, so we can also use this to satisfy
	// kind and ancestor queries.
	protected Database entities;
	
	// This table stores counter values. We can't store them in the entities table, because getSequence
	// inserts records in the database it's called on.
	protected Database sequences;
	
	// We define a single built-in index for satisfying equality queries on fields.
	protected SecondaryDatabase entities_by_property;
	
	// Cached sequences
	protected Map<Reference, Sequence> sequence_cache = new HashMap<Reference, Sequence>();
	
	/**
	 * @param basedir
	 * @param app_id
	 * @throws EnvironmentLockedException
	 * @throws DatabaseException
	 */
	public AppDatastore(String basedir, String app_id)
			throws EnvironmentLockedException, DatabaseException {
		this.app_id = app_id;
		
		File datastore_dir = new File(basedir, app_id);
		datastore_dir.mkdir();
		
		EnvironmentConfig envconfig = new EnvironmentConfig();
		envconfig.setAllowCreate(true);
		envconfig.setTransactional(true);
		envconfig.setSharedCache(true);
		env = new Environment(datastore_dir, envconfig);
		
		DatabaseConfig dbconfig = new DatabaseConfig();
		dbconfig.setAllowCreate(true);
		dbconfig.setTransactional(true);
		dbconfig.setBtreeComparator(SerializedEntityKeyComparator.class);
		entities = env.openDatabase(null, "entities", dbconfig);
		
		sequences = env.openDatabase(null, "sequences", dbconfig);

		SecondaryConfig secondconfig = new SecondaryConfig();
		secondconfig.setAllowCreate(true);
		secondconfig.setAllowPopulate(true);
		secondconfig.setBtreeComparator(SerializedPropertyIndexKeyComparator.class);
		secondconfig.setDuplicateComparator(SerializedEntityKeyComparator.class);
		secondconfig.setMultiKeyCreator(new SinglePropertyIndexer());
		secondconfig.setSortedDuplicates(true);
		secondconfig.setTransactional(true);
		entities_by_property = env.openSecondaryDatabase(null, "entities_by_property", entities, secondconfig);
	}
	
	public void close() throws DatabaseException {
		for(Sequence seq : this.sequence_cache.values())
			seq.close();
		sequences.close();
		entities_by_property.close();
		entities.close();
		env.close();
	}
	
	protected static Indexing.EntityKey toEntityKey(Reference ref) {
		Entity.Path path = ref.getPath();
		ByteString kind = path.getElement(path.getElementCount() - 1).getType();
		return Indexing.EntityKey.newBuilder().setKind(kind).setPath(path).build();
	}
	
	public EntityProto get(Reference ref, Transaction tx) throws DatabaseException {
		DatabaseEntry key = new DatabaseEntry(toEntityKey(ref).toByteArray());
		DatabaseEntry value = new DatabaseEntry();
		OperationStatus status = entities.get(tx, key, value, null);
		if(status == OperationStatus.SUCCESS) {
			try {
				return EntityProto.parseFrom(value.getData());
			} catch(InvalidProtocolBufferException ex) {
				logger.error("Invalid protocol buffer encountered parsing {}", ref);
			}
		}
		return null;
	}
	
	protected long getId(Reference ref) throws DatabaseException {
		Sequence seq = this.sequence_cache.get(ref);
		if(seq == null) {
			synchronized(this.sequence_cache) {
				seq = this.sequence_cache.get(ref);
				if(seq == null) {
					SequenceConfig conf = new SequenceConfig();
					conf.setAllowCreate(true);
					conf.setCacheSize(DatastoreServer.properties.getInt("datastore.sequence.cache_size", 20));
					conf.setInitialValue(1);
					seq = sequences.openSequence(null, new DatabaseEntry(toEntityKey(ref).toByteArray()), conf);
					this.sequence_cache.put(ref, seq);
				}
			}
		}
		return seq.get(null, 1);
	}
	
	public Reference put(EntityProto entity, Transaction tx) throws DatabaseException {
		// Sort the properties for easy filtering on retrieval.
		List<Property> properties = new ArrayList<Property>(entity.getPropertyList());
		Collections.sort(properties, PropertyComparator.instance);
		entity = Entity.EntityProto.newBuilder(entity).clearProperty().addAllProperty(properties).build();
		
		// Generate and set the ID if necessary.
		Reference ref = entity.getKey();
		int pathLen = ref.getPath().getElementCount();
		Path.Element lastElement = ref.getPath().getElement(pathLen - 1);
		if(lastElement.getId() == 0 && !lastElement.hasName()) {
			long id = this.getId(ref);
			ref = Reference.newBuilder(ref).setPath(
					Path.newBuilder(ref.getPath())
					.setElement(pathLen - 1, 
							Path.Element.newBuilder(lastElement).setId(id))).build();
			if(ref.getPath().getElementCount() == 1) {
				entity = EntityProto.newBuilder(entity).setEntityGroup(ref.getPath()).setKey(ref).build();
			} else {
				entity = EntityProto.newBuilder(entity).setKey(ref).build();
			}
		}
		
		DatabaseEntry key = new DatabaseEntry(toEntityKey(ref).toByteArray());
		DatabaseEntry value = new DatabaseEntry(entity.toByteArray());
		OperationStatus status = entities.put(tx, key, value);
		if(status != OperationStatus.SUCCESS)
			throw new DatabaseException(String.format("Failed to put entity %s: put returned %s", entity.getKey(), status));
		return ref;
	}

	public Transaction newTransaction() throws DatabaseException {
		TransactionConfig conf = new TransactionConfig();
		return this.env.beginTransaction(null, conf);
	}

	public void delete(Reference ref, Transaction tx) throws DatabaseException {
		DatabaseEntry key = new DatabaseEntry(toEntityKey(ref).toByteArray());
		OperationStatus status = entities.delete(tx, key);
		if(status != OperationStatus.SUCCESS && status != OperationStatus.NOTFOUND) {
			throw new DatabaseException(String.format("Failed to delete entity %s: delete returned %s", ref, status));
		}
	}

	public AbstractDatastoreResultSet executeQuery(Query request) throws DatabaseException {
		QuerySpec query = new QuerySpec(request);
		
		AbstractDatastoreResultSet ret = getEntityQueryPlan(query);
		if(ret != null)
			return ret;
		ret = getAncestorQueryPlan(query);
		if(ret != null)
			return ret;
		ret = getSinglePropertyQueryPlan(query);
		if(ret != null)
			return ret;
		ret = getMergeJoinQueryPlan(query);
		if(ret != null)
			return ret;
		//TODO: Handle running out of query plans
		return null;
	}

	/* Attempts to generate a merge join multiple-equality query. */
	private AbstractDatastoreResultSet getMergeJoinQueryPlan(QuerySpec query) throws DatabaseException {
		if(query.hasAncestor())
			return null;

		// Check only equality filters are used
		if(query.getFilters().get(query.getFilters().size() - 1).getOperator() != 5)
			return null;
		
		// Check no sort orders are specified
		// TODO: Handle explicit specification of __key__ sort order
		if(query.getOrders().size() > 0)
			return null;
		
		Entity.Index index = query.getIndex();
		
		// Upper bound is equal to lower bound, since there's no inequality filter
		List<Entity.PropertyValue> values = new ArrayList<Entity.PropertyValue>(index.getPropertyCount());
		query.getLowerBound(values);
		if(values.size() != index.getPropertyCount())
			return null;
		
		// Construct the required cursors
		Cursor[] cursors = new Cursor[values.size()];
		for(int i = 0; i < values.size(); i++) {
			Indexing.PropertyIndexKey startKey = Indexing.PropertyIndexKey.newBuilder()
				.setKind(index.getEntityType())
				.setName(index.getProperty(i).getName())
				.setValue(values.get(i))
				.build();
			cursors[i] = this.entities_by_property.openCursor(null, null);
			getFirstResult(cursors[i], startKey, false);
		}
		
		// Construct a join cursor
		JoinCursor cursor = this.entities.join(cursors, null);
		
		return new JoinedDatastoreResultSet(cursor, query, cursors);
	}
	
	/* Attempts to generate a query on a single-property index. */
	private AbstractDatastoreResultSet getSinglePropertyQueryPlan(QuerySpec query) throws DatabaseException {
		if(query.hasAncestor())
			return null;
		
		Entity.Index index = query.getIndex();
		if(index.getPropertyCount() > 1)
			return null;
		
		List<Entity.PropertyValue> values = new ArrayList<Entity.PropertyValue>(1);
		Indexing.PropertyIndexKey.Builder lowerBound = Indexing.PropertyIndexKey.newBuilder()
			.setKind(index.getEntityType())
			.setName(index.getProperty(0).getName());
		boolean exclusiveMin = query.getLowerBound(values);
		if(values.size() == 1) {
			lowerBound.setValue(values.get(0));
		} else if(values.size() > 1) {
			return null;
		}
		
		Indexing.PropertyIndexKey.Builder upperBound = Indexing.PropertyIndexKey.newBuilder()
			.setKind(index.getEntityType())
			.setName(index.getProperty(0).getName());
		values.clear();
		boolean exclusiveMax = query.getUpperBound(values);
		if(values.size() == 1) {
			upperBound.setValue(values.get(0));
		} else if(values.size() > 1) {
			return null;
		}
		
		Cursor cursor = this.entities_by_property.openCursor(null, null);
		MessagePredicate predicate = new PropertyIndexPredicate(upperBound.build(), exclusiveMax);
		return new DatastoreResultSet(cursor, lowerBound.build(), exclusiveMin, query, predicate);
	}


	/* Attempts to generate a query by ancestor and entity */
	private AbstractDatastoreResultSet getAncestorQueryPlan(QuerySpec query) throws DatabaseException {
		//TODO: Explicitly handle __key__ sort order
		if(!query.hasAncestor() || query.getFilters().size() > 0 || query.getOrders().size() > 0)
			return null;
		
		Cursor cursor = this.entities.openCursor(null, null);
		Indexing.EntityKey startKey = Indexing.EntityKey.newBuilder()
			.setKind(query.getKind())
			.setPath(query.getAncestor().getPath())
			.build();
		MessagePredicate predicate = new KeyPredicate(startKey);
		return new DatastoreResultSet(cursor, startKey, false, query, predicate);
	}

	/* Attempts to generate a query plan for a scan by entity only */
	private AbstractDatastoreResultSet getEntityQueryPlan(QuerySpec query) throws DatabaseException {
		//TODO: Handle __key__ sort order and filter specifications.
		if(query.hasAncestor() || query.getFilters().size() > 0 || query.getOrders().size() > 0)
			return null;
		
		Cursor cursor = this.entities.openCursor(null, null);
		Indexing.EntityKey startKey = Indexing.EntityKey.newBuilder().setKind(query.getKind()).build();
		MessagePredicate predicate = new KeyPredicate(startKey);
		return new DatastoreResultSet(cursor, startKey, false, query, predicate);
	}

	private void getFirstResult(Cursor cursor, Message startKey, boolean exclusiveMin) throws DatabaseException {
		byte[] startKeyBytes = startKey.toByteArray();
		DatabaseEntry key = new DatabaseEntry(startKeyBytes);
		DatabaseEntry data = new DatabaseEntry();
		OperationStatus status = cursor.getSearchKeyRange(key, data, null);
		if(status == OperationStatus.SUCCESS && exclusiveMin && Arrays.equals(startKeyBytes, key.getData())) {
			status = cursor.getNextNoDup(key, data, null);
		}
	}
}