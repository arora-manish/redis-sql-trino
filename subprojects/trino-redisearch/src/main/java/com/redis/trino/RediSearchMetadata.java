package com.redis.trino;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.airlift.slice.Slice;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorOutputMetadata;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableLayout;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableProperties;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.LimitApplicationResult;
import io.trino.spi.connector.NotFoundException;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.statistics.ComputedStatistics;

public class RediSearchMetadata implements ConnectorMetadata {

	private final RediSearchSession rediSearchSession;
	private final String schemaName;
	private final AtomicReference<Runnable> rollbackAction = new AtomicReference<>();

	public RediSearchMetadata(RediSearchSession rediSearchSession) {
		this.rediSearchSession = requireNonNull(rediSearchSession, "rediSearchSession is null");
		this.schemaName = rediSearchSession.getConfig().getDefaultSchema();
	}

	@Override
	public List<String> listSchemaNames(ConnectorSession session) {
		return ImmutableList.of(schemaName);
	}

	@Override
	public RediSearchTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName) {
		requireNonNull(tableName, "tableName is null");

		if (tableName.getSchemaName().equals(schemaName)) {
			try {
				return rediSearchSession.getTable(tableName).getTableHandle();
			} catch (TableNotFoundException e) {
				// ignore and return null
			}
		}
		return null;
	}

	@Override
	public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle) {
		requireNonNull(tableHandle, "tableHandle is null");
		SchemaTableName tableName = getTableName(tableHandle);
		return getTableMetadata(session, tableName);
	}

	@Override
	public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> optionalSchemaName) {
		ImmutableList.Builder<SchemaTableName> tableNames = ImmutableList.builder();
		for (String tableName : rediSearchSession.getAllTables()) {
			tableNames.add(new SchemaTableName(schemaName, tableName));
		}
		return tableNames.build();
	}

	@Override
	public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle) {
		RediSearchTableHandle table = (RediSearchTableHandle) tableHandle;
		List<RediSearchColumnHandle> columns = rediSearchSession.getTable(table.getSchemaTableName()).getColumns();

		ImmutableMap.Builder<String, ColumnHandle> columnHandles = ImmutableMap.builder();
		for (RediSearchColumnHandle columnHandle : columns) {
			columnHandles.put(columnHandle.getName(), columnHandle);
		}
		return columnHandles.build();
	}

	@Override
	public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session,
			SchemaTablePrefix prefix) {
		requireNonNull(prefix, "prefix is null");
		ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();
		for (SchemaTableName tableName : listTables(session, prefix)) {
			try {
				columns.put(tableName, getTableMetadata(session, tableName).getColumns());
			} catch (NotFoundException e) {
				// table disappeared during listing operation
			}
		}
		return columns.buildOrThrow();
	}

	private List<SchemaTableName> listTables(ConnectorSession session, SchemaTablePrefix prefix) {
		if (prefix.getTable().isEmpty()) {
			return listTables(session, prefix.getSchema());
		}
		return ImmutableList.of(prefix.toSchemaTableName());
	}

	@Override
	public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle,
			ColumnHandle columnHandle) {
		return ((RediSearchColumnHandle) columnHandle).toColumnMetadata();
	}

	@Override
	public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting) {
		rediSearchSession.createTable(tableMetadata.getTable(), buildColumnHandles(tableMetadata));
	}

	@Override
	public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle) {
		RediSearchTableHandle table = (RediSearchTableHandle) tableHandle;
		rediSearchSession.dropTable(table.getSchemaTableName());
	}

	@Override
	public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column) {
		rediSearchSession.addColumn(((RediSearchTableHandle) tableHandle).getSchemaTableName(), column);
	}

	@Override
	public void dropColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column) {
		rediSearchSession.dropColumn(((RediSearchTableHandle) tableHandle).getSchemaTableName(),
				((RediSearchColumnHandle) column).getName());
	}

	@Override
	public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata,
			Optional<ConnectorTableLayout> layout) {
		List<RediSearchColumnHandle> columns = buildColumnHandles(tableMetadata);

		rediSearchSession.createTable(tableMetadata.getTable(), columns);

		setRollback(() -> rediSearchSession.dropTable(tableMetadata.getTable()));

		return new RediSearchOutputTableHandle(tableMetadata.getTable(),
				columns.stream().filter(c -> !c.isHidden()).collect(toList()));
	}

	@Override
	public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session,
			ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments,
			Collection<ComputedStatistics> computedStatistics) {
		clearRollback();
		return Optional.empty();
	}

	@Override
	public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle) {
		RediSearchTableHandle table = (RediSearchTableHandle) tableHandle;
		List<RediSearchColumnHandle> columns = rediSearchSession.getTable(table.getSchemaTableName()).getColumns();

		return new RediSearchInsertTableHandle(table.getSchemaTableName(),
				columns.stream().filter(column -> !column.isHidden()).collect(toImmutableList()));
	}

	@Override
	public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session,
			ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments,
			Collection<ComputedStatistics> computedStatistics) {
		return Optional.empty();
	}

	@Override
	public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle table) {
		RediSearchTableHandle handle = (RediSearchTableHandle) table;
		return new ConnectorTableProperties(handle.getConstraint(), Optional.empty(), Optional.empty(),
				Optional.empty(), ImmutableList.of());
	}

	@Override
	public Optional<LimitApplicationResult<ConnectorTableHandle>> applyLimit(ConnectorSession session,
			ConnectorTableHandle table, long limit) {
		RediSearchTableHandle handle = (RediSearchTableHandle) table;

		if (limit == 0) {
			return Optional.empty();
		}

		if (handle.getLimit().isPresent() && handle.getLimit().getAsInt() <= limit) {
			return Optional.empty();
		}

		return Optional.of(new LimitApplicationResult<>(new RediSearchTableHandle(handle.getSchemaTableName(),
				handle.getConstraint(), OptionalInt.of(toIntExact(limit))), true, false));
	}

	@Override
	public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session,
			ConnectorTableHandle table, Constraint constraint) {
		RediSearchTableHandle handle = (RediSearchTableHandle) table;

		TupleDomain<ColumnHandle> oldDomain = handle.getConstraint();
		TupleDomain<ColumnHandle> newDomain = oldDomain.intersect(constraint.getSummary());
		if (oldDomain.equals(newDomain)) {
			return Optional.empty();
		}

		handle = new RediSearchTableHandle(handle.getSchemaTableName(), newDomain, handle.getLimit());

		return Optional.of(new ConstraintApplicationResult<>(handle, constraint.getSummary(), false));
	}

	private void setRollback(Runnable action) {
		checkState(rollbackAction.compareAndSet(null, action), "rollback action is already set");
	}

	private void clearRollback() {
		rollbackAction.set(null);
	}

	public void rollback() {
		Optional.ofNullable(rollbackAction.getAndSet(null)).ifPresent(Runnable::run);
	}

	private static SchemaTableName getTableName(ConnectorTableHandle tableHandle) {
		return ((RediSearchTableHandle) tableHandle).getSchemaTableName();
	}

	private ConnectorTableMetadata getTableMetadata(ConnectorSession session, SchemaTableName tableName) {
		RediSearchTableHandle tableHandle = rediSearchSession.getTable(tableName).getTableHandle();

		List<ColumnMetadata> columns = ImmutableList
				.copyOf(getColumnHandles(session, tableHandle).values().stream().map(RediSearchColumnHandle.class::cast)
						.map(RediSearchColumnHandle::toColumnMetadata).collect(toList()));

		return new ConnectorTableMetadata(tableName, columns);
	}

	private static List<RediSearchColumnHandle> buildColumnHandles(ConnectorTableMetadata tableMetadata) {
		return tableMetadata.getColumns().stream()
				.map(m -> new RediSearchColumnHandle(m.getName(), m.getType(), m.isHidden())).collect(toList());
	}
}
