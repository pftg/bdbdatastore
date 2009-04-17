package net.notdot.bdbdatastore.server;

import java.util.ArrayList;
import java.util.List;

import net.notdot.protorpc.RpcFailedError;

import com.google.appengine.datastore_v3.DatastoreV3;
import com.google.appengine.entity.Entity;
import com.google.protobuf.ByteString;

public class QuerySpec {
	protected String app;
	protected ByteString kind;
	protected Entity.Reference ancestor = null;
	protected List<FilterSpec> filters = new ArrayList<FilterSpec>();
	protected List<DatastoreV3.Query.Order> orders = new ArrayList<DatastoreV3.Query.Order>();
	protected int offset = 0;
	protected int limit = -1;
	
	protected Entity.Index index = null;
	
	public QuerySpec(DatastoreV3.Query query) {
		this.app = query.getApp();
		if(!query.hasKind())
			throw new RpcFailedError("Queries must specify a kind",
					DatastoreV3.Error.ErrorCode.BAD_REQUEST.getNumber());
		this.kind = query.getKind();
		if(query.hasAncestor())
			this.ancestor = query.getAncestor();
		this.filters = FilterSpec.FromQuery(query);
		this.orders = query.getOrderList();
		if(query.hasOffset())
			this.offset = query.getOffset();
		if(query.hasLimit())
			this.limit = query.getLimit();
	}
	
	public Entity.Index getIndex() {
		// TODO: Refactor this to support multiple possible indexes to satisfy the same query.
		if(this.index == null) {
			Entity.Index.Builder builder = Entity.Index.newBuilder();
			builder.setEntityType(this.kind);
			builder.setAncestor(this.ancestor != null);
			ByteString inequalityprop = null;
			
			// Add all equality filters
			for(FilterSpec filter : this.filters) {
				if(filter.getOperator() == DatastoreV3.Query.Filter.Operator.EQUAL.getNumber()) {
					builder.addProperty(Entity.Index.Property.newBuilder().setName(filter.getName()));
				} else {
					if(inequalityprop != null && !filter.getName().equals(inequalityprop))
						throw new RpcFailedError("Only one inequality property is permitted per query",
								DatastoreV3.Error.ErrorCode.BAD_REQUEST.getNumber());
					inequalityprop = filter.getName();
				}
			}
			
			if(inequalityprop != null && this.orders.size() > 0 && !this.orders.get(0).getProperty().equals(inequalityprop))
				throw new RpcFailedError("First sort order must match inequality property",
						DatastoreV3.Error.ErrorCode.BAD_REQUEST.getNumber());
			
			// If there's no sort orders, add the inequality, ascending
			if(inequalityprop != null && this.orders.size() == 0)
				builder.addProperty(Entity.Index.Property.newBuilder().setName(inequalityprop));
				
			// Add the sort orders
			for(DatastoreV3.Query.Order order : this.orders) {
				builder.addProperty(Entity.Index.Property.newBuilder()
						.setName(order.getProperty())
						.setDirection(Entity.Index.Property.Direction.valueOf(order.getDirection())));
			}
			
			this.index = builder.build();
		}
		return this.index;
	}
	
	public boolean getLowerBound(List<Entity.PropertyValue> lowerBound) {
		boolean lowerExclusive = false;
		Entity.PropertyValue inequalityMin = null;
		for(FilterSpec filter : this.filters) {
			switch(filter.getOperator()) {
			case 1: // Less than
			case 2: // Less than or equal
				break;
			case 3: // Greater than
				if(inequalityMin == null || PropertyValueComparator.instance.compare(filter.getValue(), inequalityMin) < 0) {
					inequalityMin = filter.getValue();
					lowerExclusive = true;
				}
				break;
			case 4: // Greater than or equal
				if(inequalityMin == null || PropertyValueComparator.instance.compare(filter.getValue(), inequalityMin) <= 0) {
					inequalityMin = filter.getValue();
					lowerExclusive = false;
				}
				break;
			case 5: // Equal
				lowerBound.add(filter.getValue());
			}
		}
		if(inequalityMin != null)
			lowerBound.add(inequalityMin);
		return lowerExclusive;
	}

	public boolean getUpperBound(List<Entity.PropertyValue> upperBound) {
		boolean upperExclusive = false;
		Entity.PropertyValue inequalityMax = null;
		for(FilterSpec filter : this.filters) {
			switch(filter.getOperator()) {
			case 1: // Less than
				if(inequalityMax == null || PropertyValueComparator.instance.compare(filter.getValue(), inequalityMax) > 0) {
					inequalityMax = filter.getValue();
					upperExclusive = true;
				}
				break;
			case 2: // Less than or equal
				if(inequalityMax == null || PropertyValueComparator.instance.compare(filter.getValue(), inequalityMax) >= 0) {
					inequalityMax = filter.getValue();
					upperExclusive = false;
				}
				break;
			case 3: // Greater than
			case 4: // Greater than or equal
				break;
			case 5: // Equal
				upperBound.add(filter.getValue());
			}
		}
		if(inequalityMax != null)
			upperBound.add(inequalityMax);
		return upperExclusive;
	}

	public String getApp() {
		return app;
	}

	public ByteString getKind() {
		return kind;
	}

	public Entity.Reference getAncestor() {
		return ancestor;
	}
	
	public boolean hasAncestor() {
		return ancestor != null;
	}

	public List<FilterSpec> getFilters() {
		return filters;
	}

	public List<DatastoreV3.Query.Order> getOrders() {
		return orders;
	}

	public int getOffset() {
		return offset;
	}

	public int getLimit() {
		return limit;
	}
}