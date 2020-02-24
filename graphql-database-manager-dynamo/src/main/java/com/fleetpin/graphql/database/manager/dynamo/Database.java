/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.fleetpin.graphql.database.manager.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpin.graphql.database.manager.*;
import com.fleetpin.graphql.database.manager.access.ForbiddenWriteException;
import com.fleetpin.graphql.database.manager.access.ModificationPermission;
import com.fleetpin.graphql.database.manager.util.DynamoDbUtil;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderOptions;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Database {
	private String organisationId;
	private final DatabaseDriver dynamo;

	private final ObjectMapper mapper;
	
	private final DataLoader<DatabaseKey, Table> items;
	private final DataLoader<DatabaseQueryKey, List<Table>> queries;

	private final Function<Table, CompletableFuture<Boolean>> putAllow;
	
	Database(ObjectMapper mapper, String organisationId, DatabaseDriver dynamo, ModificationPermission putAllow) {
		this.mapper = mapper;
		this.organisationId = organisationId;
		this.dynamo = dynamo;
		this.putAllow = putAllow;

		items = new DataLoader<DatabaseKey, Table>(keys -> {
			return dynamo.get(keys);
		}, DataLoaderOptions.newOptions().setMaxBatchSize(dynamo.maxBatchSize())); // will auto call global
		
		queries = new DataLoader<DatabaseQueryKey, List<Table>>(keys -> {
			return merge(keys.stream().map(key -> dynamo.query(key)));
		}, DataLoaderOptions.newOptions().setBatchingEnabled(false)); // will auto call global
	}


	public <T extends Table> CompletableFuture<List<T>> query(Class<T> type) {
		return queries.load(KeyFactory.createDatabaseQueryKey(organisationId, type))
				.thenApply(items -> items.stream().map(item -> (T) item).filter(Objects::nonNull).collect(Collectors.toList()));
	}

	public <T extends Table> CompletableFuture<List<T>> queryGlobal(Class<T> type, String id) {
		return dynamo.queryGlobal(type, id)
				.thenApply(items -> items.stream().map(item -> (T) item).collect(Collectors.toList()));
	}
	public <T extends Table> CompletableFuture<T> queryGlobalUnique(Class<T> type, String id) {
		return queryGlobal(type, id).thenApply(items -> {
			if(items.size() > 1) {
				throw new RuntimeException("expected single linkage");
			}
			if(items.size() == 0) {
				return null;
			}
			return items.get(0);
		});
	}

	public <T extends Table> CompletableFuture<List<T>> querySecondary(Class<T> type, String id) {
		return dynamo.querySecondary(type, organisationId, id)
				.thenApply(items -> items.stream().map(item -> (T) item).collect(Collectors.toList()));
	}
	public <T extends Table> CompletableFuture<T> querySecondaryUnique(Class<T> type, String id) {
		return querySecondary(type, id).thenApply(items -> {
			if(items.size() > 1) {
				throw new RuntimeException("expected single linkage");
			}
			if(items.size() == 0) {
				return null;
			}
			return items.get(0);
		});
	}
	public <T extends Table> CompletableFuture<Optional<T>> getOptional(Class<T> type, String id) {
		if(id == null) {
			return CompletableFuture.completedFuture(Optional.empty());
		}
		return items.load(KeyFactory.createDatabaseKey(organisationId, type, id)).thenApply(item -> {
			if(item == null) {
				return Optional.empty();
			}else {
				return Optional.of((T) item);
			}
		});
	}
	
	public <T extends Table> CompletableFuture<T> get(Class<T> type, String id) {
		return items.load(KeyFactory.createDatabaseKey(organisationId, type, id)).thenApply(item -> {
		return (T) item;
		});
	}

	public <T extends Table> CompletableFuture<T> delete(T entity, boolean deleteLinks) {
		if(!deleteLinks) {
			if(!TableAccess.getTableLinks(entity).isEmpty()) {
				throw new RuntimeException("deleting would leave dangling links");
			}
		}
		return putAllow.apply(entity).thenCompose(allow -> {
			if(!allow) {
				throw new ForbiddenWriteException("Delete not allowed for " + DynamoDbUtil.table(entity.getClass()) + " with id " + entity.getId());
			}
    		items.clear(KeyFactory.createDatabaseKey(organisationId, entity.getClass(), entity.getId()));
    		queries.clear(KeyFactory.createDatabaseQueryKey(organisationId, entity.getClass()));
    		
    		if(deleteLinks) {
    			return deleteLinks(entity).thenCompose(t -> dynamo.delete(organisationId, entity));
    		}
    		
    		return dynamo.delete(organisationId, entity);
		});
	}

	public <T extends Table> CompletableFuture<List<T>> getLinks(final T entry) {
		return dynamo.getViaLinks(organisationId, entry, entry.getClass(), items)
			.thenApply(items -> items.stream().filter(Objects::nonNull).map(item -> (T) item).collect(Collectors.toList()));
	}

	public <T extends Table> CompletableFuture<T> getLink(final T entry) {
		return getLinks(entry).thenApply(items -> {
			if (items.size() > 1) {
				throw new RuntimeException("Bad data"); // TODO: more info in failure
			}
			return (T) items.stream().findFirst().orElse(null);
		});

	}
	
	public <T extends Table> CompletableFuture<Optional<T>> getLinkOptional(final T entry) {
		return getLink(entry).thenApply(t -> Optional.ofNullable(t));

	}

	public <T extends Table> CompletableFuture<T> deleteLinks(T entity) {
		return putAllow.apply(entity).thenCompose(allow -> {
			if(!allow) {
				throw new ForbiddenWriteException("Delete links not allowed for " + DynamoDbUtil.table(entity.getClass()) + " with id " + entity.getId());
			}
			return dynamo.deleteLinks(organisationId, entity).thenCompose(t -> put(entity));
		});
	}

	public <T extends Table> CompletableFuture<T> put(T entity) {
		return putAllow.apply(entity).thenCompose(allow -> {
			if(!allow) {
				throw new ForbiddenWriteException("put not allowed for " + DynamoDbUtil.table(entity.getClass()) + " with id " + entity.getId());
			}
    		items.clear(KeyFactory.createDatabaseKey(organisationId, entity.getClass(), entity.getId()));
    		queries.clear(KeyFactory.createDatabaseQueryKey(organisationId, entity.getClass()));
    		return dynamo.put(organisationId, entity);
		});
	}
	public <T extends Table> CompletableFuture<T> putGlobal(T entity) {
		return putAllow.apply(entity).thenCompose(allow -> {
			if(!allow) {
				throw new ForbiddenWriteException("put global not allowed for " + DynamoDbUtil.table(entity.getClass()) + " with id " + entity.getId());
			}
    		items.clear(KeyFactory.createDatabaseKey("global", entity.getClass(), entity.getId()));
    		queries.clear(KeyFactory.createDatabaseQueryKey("global", entity.getClass()));
    		return dynamo.put("global", entity);
		});
		
	}

	private <T> CompletableFuture<List<T>> merge(Stream<CompletableFuture<T>> stream) {
		List<CompletableFuture<T>> list = stream.collect(Collectors.toList());
		
		return CompletableFuture.allOf(list.toArray(CompletableFuture[]::new)).thenApply(__ -> {
			List<T> toReturn = new ArrayList<>(list.size());
			for(var item: list) {
				try {
					toReturn.add(item.get());
				} catch (InterruptedException | ExecutionException e) {
					throw new RuntimeException(e);
				}
			}
			return toReturn; 
		});
		
	}

	private static final Executor DELAYER = CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS);
	@SuppressWarnings("rawtypes")
	public void start(CompletableFuture<?> toReturn) {
		if(toReturn.isDone()) {
			return;
		}
		
		if(items.dispatchDepth() > 0 || queries.dispatchDepth() > 0) {
			CompletableFuture[] all = new CompletableFuture[] {items.dispatch(), queries.dispatch()};
			CompletableFuture.allOf(all).whenComplete((response, error) -> {
				//go around again
				start(toReturn);
			});
		}else {
			CompletableFuture.supplyAsync(() -> null, DELAYER).acceptEither(toReturn, __ -> start(toReturn));
		}
	}


	public <T extends Table> CompletableFuture<T> links(T entity, Class<? extends Table> class1, List<String> targetIds) {
		return putAllow.apply(entity).thenCompose(allow -> {
			if(!allow) {
				throw new ForbiddenWriteException("Link not allowed for " + DynamoDbUtil.table(entity.getClass()) + " with id " + entity.getId());
			}
    		items.clear(KeyFactory.createDatabaseKey(organisationId, entity.getClass(), entity.getId()));
    		queries.clear(KeyFactory.createDatabaseQueryKey(organisationId, entity.getClass()));
    		for(String id: targetIds) {
    			items.clear(KeyFactory.createDatabaseKey(organisationId, class1, id));
    		}
    		queries.clear(KeyFactory.createDatabaseQueryKey(organisationId, class1));
    		return dynamo.link(organisationId, entity, class1, targetIds);
		});
	}


	public <T extends Table> CompletableFuture<T> link(T entity, Class<? extends Table> class1, String targetId) {
		if(targetId == null) {
			return links(entity, class1, Collections.emptyList());	
		}else {
			return links(entity, class1, Arrays.asList(targetId));
		}
	}
	

	public <T extends Table> CompletableFuture<List<T>> get(Class<T> class1, List<String> ids) {
		if(ids == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		return TableUtil.all(ids.stream().map(id -> get(class1, id)).collect(Collectors.toList()));
	}


	public void setOrganisationId(String organisationId) {
		this.organisationId = organisationId;
	}


	public String getSourceOrganisationId(Table table) {
		return TableAccess.getTableSourceOrganisation(table);
	}

	public String newId() {
		return dynamo.newId();
	}


	public Set<String> getLinkIds(Table entity, Class<? extends Table> type) {
		return Collections.unmodifiableSet(TableAccess.getTableLinks(entity).get(DynamoDbUtil.table(type)));
	}
	
}
