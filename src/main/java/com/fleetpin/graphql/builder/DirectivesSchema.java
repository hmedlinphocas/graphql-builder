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
package com.fleetpin.graphql.builder;

import com.fleetpin.graphql.builder.annotations.Directive;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLDirective;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;

class DirectivesSchema {

	private final Collection<RestrictTypeFactory<?>> global;
	private final Map<Class<? extends Annotation>, DirectiveCaller<?>> targets;
	private final Map<Class<? extends Annotation>, SDLDirective<?, ?>> schemaDirective;
	private Map<Class<? extends Annotation>, SDLProcessor> sdlProcessors;

	private DirectivesSchema(
		Collection<RestrictTypeFactory<?>> global,
		Map<Class<? extends Annotation>, DirectiveCaller<?>> targets,
		Map<Class<? extends Annotation>, SDLDirective<?, ?>> schemaDirective
	) {
		this.global = global;
		this.targets = targets;
		this.schemaDirective = schemaDirective;
	}

	//TODO:mess of exceptions
	public static DirectivesSchema build(List<RestrictTypeFactory<?>> globalDirectives, Set<Class<?>> dierctiveTypes) throws ReflectiveOperationException {
		Map<Class<? extends Annotation>, DirectiveCaller<?>> targets = new HashMap<>();
		Map<Class<? extends Annotation>, SDLDirective<?, ?>> graphqlDirective = new HashMap<>();
		for (Class<?> directiveType : dierctiveTypes) {
			if (!directiveType.isAnnotationPresent(Directive.class)) {
				continue;
			}
			if (!directiveType.isAnnotation()) {
				//TODO:better error management
				throw new RuntimeException("@Directive Annotation must only be placed on annotations");
			}

			var directive = directiveType.getAnnotation(Directive.class);
			Class<? extends DirectiveOperation<?>> caller = directive.value();
			//TODO: if target implents other things this won't lineup right
			Class<?> target = (Class<?>) ((ParameterizedType) caller.getGenericInterfaces()[0]).getActualTypeArguments()[0];
			if (!target.equals(directiveType)) {
				//TODO:better errors
				throw new RuntimeException("Annotation missmatch");
			}

			if (DirectiveCaller.class.isAssignableFrom(caller)) {
				//TODO error for no zero args constructor
				var callerInstance = (DirectiveCaller<?>) caller.getConstructor().newInstance();
				targets.put((Class<? extends Annotation>) directiveType, callerInstance);
			} else {
				var callerInstance = (SDLDirective<?, ?>) caller.getConstructor().newInstance();
				graphqlDirective.put((Class<? extends Annotation>) directiveType, callerInstance);
			}
		}

		return new DirectivesSchema(globalDirectives, targets, graphqlDirective);
	}

	private DirectiveCaller<?> get(Annotation annotation) {
		return targets.get(annotation.annotationType());
	}

	private <T extends Annotation> DataFetcher<?> wrap(DirectiveCaller<T> directive, T annotation, DataFetcher<?> fetcher) {
		return env -> {
			return directive.process(annotation, env, fetcher);
		};
	}

	public Stream<GraphQLDirective> getSchemaDirective() {
		return sdlProcessors.values().stream().map(f -> f.getDirective());
	}

	private DataFetcher<?> wrap(RestrictTypeFactory<?> directive, DataFetcher<?> fetcher) {
		//TODO: hate having this cache here would love to scope against the env object but nothing to hook into dataload caused global leak
		Map<DataFetchingEnvironment, CompletableFuture<RestrictType>> cache = Collections.synchronizedMap(new WeakHashMap<>());

		return env -> {
			return cache
				.computeIfAbsent(env, key -> directive.create(key).thenApply(t -> t))
				.thenCompose(restrict -> {
					try {
						Object response = fetcher.get(env);
						if (response instanceof CompletionStage) {
							return ((CompletionStage) response).thenCompose(r -> applyRestrict(restrict, r));
						}
						return applyRestrict(restrict, response);
					} catch (Exception e) {
						if (e instanceof RuntimeException) {
							throw (RuntimeException) e;
						}
						throw new RuntimeException(e);
					}
				});
		};
	}

	public boolean target(Method method, TypeMeta meta) {
		for (var global : this.global) {
			//TODO: extract class
			if (global.extractType().isAssignableFrom(meta.getType())) {
				return true;
			}
		}
		for (Annotation annotation : method.getAnnotations()) {
			if (get(annotation) != null) {
				return true;
			}
		}
		return false;
	}

	public DataFetcher<?> wrap(Method method, TypeMeta meta, DataFetcher<?> fetcher) {
		for (var g : global) {
			if (g.extractType().isAssignableFrom(meta.getType())) {
				fetcher = wrap(g, fetcher);
			}
		}
		for (Annotation annotation : method.getAnnotations()) {
			DirectiveCaller directive = (DirectiveCaller) get(annotation);
			if (directive != null) {
				fetcher = wrap(directive, annotation, fetcher);
			}
		}
		return fetcher;
	}

	private <T> CompletableFuture<Object> applyRestrict(RestrictType restrict, Object response) {
		if (response instanceof List) {
			return restrict.filter((List) response);
		} else if (response instanceof Publisher) {
			return CompletableFuture.completedFuture(new FilteredPublisher((Publisher) response, restrict));
		} else if (response instanceof Optional) {
			var optional = (Optional) response;
			if (optional.isEmpty()) {
				return CompletableFuture.completedFuture(response);
			}
			var target = optional.get();
			if (target instanceof List) {
				return restrict.filter((List) target);
			} else {
				return restrict
					.allow(target)
					.thenApply(allow -> {
						if (allow == Boolean.TRUE) {
							return response;
						} else {
							return Optional.empty();
						}
					});
			}
		} else {
			return restrict
				.allow(response)
				.thenApply(allow -> {
					if (allow == Boolean.TRUE) {
						return response;
					} else {
						return null;
					}
				});
		}
	}

	private static <T> CompletableFuture<List<T>> all(List<CompletableFuture<T>> toReturn) {
		return CompletableFuture
			.allOf(toReturn.toArray(CompletableFuture[]::new))
			.thenApply(__ ->
				toReturn
					.stream()
					.map(m -> {
						try {
							return m.get();
						} catch (InterruptedException | ExecutionException e) {
							throw new RuntimeException(e);
						}
					})
					.collect(Collectors.toList())
			);
	}

	public void addSchemaDirective(AnnotatedElement element, Class<?> location, Consumer<GraphQLAppliedDirective> builder) {
		for (Annotation annotation : element.getAnnotations()) {
			var processor = this.sdlProcessors.get(annotation.annotationType());
			if (processor != null) {
				processor.apply(annotation, location, builder);
			}
		}
	}

	public void processSDL(EntityProcessor entityProcessor) {
		Map<Class<? extends Annotation>, SDLProcessor> sdlProcessors = new HashMap<>();

		this.schemaDirective.forEach((k, v) -> {
				sdlProcessors.put(k, SDLProcessor.build(entityProcessor, v));
			});
		this.sdlProcessors = sdlProcessors;
	}
}
